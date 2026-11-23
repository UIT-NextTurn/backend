package com.nextturn.service.calendar.impl;

import com.nextturn.constant.ValkeyKeyConstant;
import com.nextturn.dto.calendar.SlotHoldingResponse;
import com.nextturn.entity.TenantTimeSlot;
import com.nextturn.enums.TimeSlotStatus;
import com.nextturn.exception.calendar.CalendarErrorCode;
import com.nextturn.exception.calendar.CalendarException;
import com.nextturn.infrastructure.valkey.ValkeyDistributedLockSupport;
import com.nextturn.repository.calendar.TenantTimeSlotRepository;
import com.nextturn.service.BaseService;
import com.nextturn.service.calendar.TenantTimeSlotGeneratorService;
import com.nextturn.service.calendar.TenantTimeSlotHoldingService;
import com.nextturn.infrastructure.logging.LogContract;
import com.nextturn.infrastructure.logging.LogDomain;
import com.nextturn.infrastructure.logging.LogOutcome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Default implementation of {@link TenantTimeSlotHoldingService} providing distributed locking
 * on Valkey, pessimistic row locking in MySQL, temporary holding leases, and expired lease cleanup.
 */
@Service
public class TenantTimeSlotHoldingServiceImpl extends BaseService implements TenantTimeSlotHoldingService {

    private static final Logger log = LoggerFactory.getLogger(TenantTimeSlotHoldingServiceImpl.class);
    private static final int CLEANUP_BATCH_SIZE = 100;
    private static final int MAX_CLEANUP_BATCHES_PER_RUN = 100;

    private final TenantTimeSlotRepository timeSlotRepository;
    private final TenantTimeSlotGeneratorService slotGeneratorService;
    private final StringRedisTemplate redisTemplate;

    public TenantTimeSlotHoldingServiceImpl(
            TenantTimeSlotRepository timeSlotRepository,
            TenantTimeSlotGeneratorService slotGeneratorService,
            @Autowired(required = false) StringRedisTemplate redisTemplate,
            TransactionTemplate transactionTemplate
    ) {
        super(transactionTemplate);
        this.timeSlotRepository = timeSlotRepository;
        this.slotGeneratorService = slotGeneratorService;
        this.redisTemplate = redisTemplate;
    }

    @Override
    public SlotHoldingResponse holdSlot(String slotId, int holdingMinutes) {
        String tenantCode = resolveCurrentTenantCode();
        String lockKey = ValkeyKeyConstant.slotLockKey(tenantCode, slotId);

        // Step 1: Distributed Lock on Valkey (SET NX PX 5000ms) with unique owner token
        String lockOwnerToken = null;
        boolean lockAcquired = true;
        if (redisTemplate != null) {
            try {
                String candidateToken = UUID.randomUUID().toString();
                Boolean setSuccess = redisTemplate.opsForValue()
                        .setIfAbsent(lockKey, candidateToken, Duration.ofSeconds(5));
                lockAcquired = Boolean.TRUE.equals(setSuccess);
                if (lockAcquired) {
                    lockOwnerToken = candidateToken;
                }
            } catch (Exception ex) {
                LogContract.warn(log, LogDomain.CALENDAR, "slot_lock_acquire")
                        .outcome(LogOutcome.DEGRADED)
                        .entity("slot", slotId)
                        .cause(ex)
                        .emit();
            }
        }

        if (!lockAcquired) {
            LogContract.warn(log, LogDomain.CALENDAR, "slot_lock_rejected")
                    .outcome(LogOutcome.REJECTED)
                    .tenant(tenantCode)
                    .entity("slot", slotId)
                    .reason("Race condition detected")
                    .emit();
            throw new CalendarException(
                    CalendarErrorCode.SLOT_ALREADY_HOLDING_OR_BOOKED,
                    "Khung giờ này vừa có người khác nhanh tay chọn trước. Vui lòng chọn một khung giờ khác."
            );
        }

        try {
            // Step 2: Pessimistic Row Lock (SELECT ... FOR UPDATE) in MySQL
            return executeInTransaction(status -> {
                TenantTimeSlot slot = timeSlotRepository.findByIdForUpdate(slotId)
                        .orElseThrow(() -> new CalendarException(CalendarErrorCode.SLOT_NOT_FOUND));

                LocalDateTime now = LocalDateTime.now();

                // If currently held, verify expiration
                if (slot.getStatus() == TimeSlotStatus.HOLDING) {
                    if (slot.getHoldingExpiresAt() != null && slot.getHoldingExpiresAt().isAfter(now)) {
                        LogContract.warn(log, LogDomain.CALENDAR, "slot_hold_conflict")
                                .outcome(LogOutcome.REJECTED)
                                .entity("slot", slotId)
                                .field("expiresAt", slot.getHoldingExpiresAt())
                                .emit();
                        throw new CalendarException(
                                CalendarErrorCode.SLOT_ALREADY_HOLDING_OR_BOOKED,
                                "Khung giờ này vừa có người khác nhanh tay chọn trước. Vui lòng chọn một khung giờ khác."
                        );
                    }
                    LogContract.info(log, LogDomain.CALENDAR, "slot_hold_lease_reset")
                            .outcome(LogOutcome.SUCCESS)
                            .entity("slot", slotId)
                            .field("expiredAt", slot.getHoldingExpiresAt())
                            .emit();
                }

                if (slot.getStatus() == TimeSlotStatus.BOOKED ||
                        (slot.getBookedCount() != null && slot.getBookedCount() >= slot.getCapacityPerSlot())) {
                    LogContract.warn(log, LogDomain.CALENDAR, "slot_fully_booked")
                            .outcome(LogOutcome.REJECTED)
                            .entity("slot", slotId)
                            .emit();
                    throw new CalendarException(
                            CalendarErrorCode.SLOT_ALREADY_HOLDING_OR_BOOKED,
                            "Khung giờ này vừa có người khác nhanh tay chọn trước. Vui lòng chọn một khung giờ khác."
                    );
                }

                int leaseMinutes = holdingMinutes > 0 ? holdingMinutes : 10;
                String token = UUID.randomUUID().toString();
                LocalDateTime expiresAt = now.plusMinutes(leaseMinutes);

                slot.setStatus(TimeSlotStatus.HOLDING);
                slot.setHoldingToken(token);
                slot.setHoldingExpiresAt(expiresAt);

                TenantTimeSlot saved = timeSlotRepository.save(slot);
                timeSlotRepository.flush();

                // Synchronize available slots in Valkey
                slotGeneratorService.syncAvailableSlotsToValkey(saved.getService().getId(), saved.getWorkDate());

                LogContract.info(log, LogDomain.CALENDAR, "slot_hold_acquired")
                        .outcome(LogOutcome.SUCCESS)
                        .entity("slot", slotId)
                        .field("expiresAt", expiresAt)
                        .emit();

                return SlotHoldingResponse.builder()
                        .slotId(saved.getId())
                        .holdingToken(token)
                        .holdingExpiresAt(expiresAt)
                        .status(TimeSlotStatus.HOLDING)
                        .message(String.format("Khung giờ đã được giữ chỗ tạm thời trong %d phút", leaseMinutes))
                        .build();
            });
        } finally {
            // Release distributed lock atomically via Lua only if still owned by this request
            if (redisTemplate != null && lockOwnerToken != null) {
                try {
                    ValkeyDistributedLockSupport.releaseLock(redisTemplate, lockKey, lockOwnerToken);
                } catch (Exception ex) {
                    LogContract.warn(log, LogDomain.CALENDAR, "slot_lock_release")
                            .outcome(LogOutcome.DEGRADED)
                            .field("lockKey", lockKey)
                            .cause(ex)
                            .emit();
                }
            }
        }
    }

    @Override
    public void releaseHeldSlot(String slotId, String holdingToken) {
        executeInTransactionWithoutResult(status -> {
            TenantTimeSlot slot = timeSlotRepository.findByIdForUpdate(slotId)
                    .orElseThrow(() -> new CalendarException(CalendarErrorCode.SLOT_NOT_FOUND));

            if (slot.getHoldingToken() == null || !slot.getHoldingToken().equals(holdingToken)) {
                LogContract.warn(log, LogDomain.CALENDAR, "slot_release_rejected")
                        .outcome(LogOutcome.REJECTED)
                        .entity("slot", slotId)
                        .reason("Token mismatch")
                        .emit();
                throw new CalendarException(
                        CalendarErrorCode.HOLDING_TOKEN_INVALID,
                        "Mã giữ chỗ không hợp lệ hoặc không khớp."
                );
            }

            if (slot.getStatus() == TimeSlotStatus.HOLDING) {
                slot.setStatus(TimeSlotStatus.AVAILABLE);
                slot.setHoldingToken(null);
                slot.setHoldingExpiresAt(null);
                TenantTimeSlot saved = timeSlotRepository.save(slot);
                timeSlotRepository.flush();

                slotGeneratorService.syncAvailableSlotsToValkey(saved.getService().getId(), saved.getWorkDate());
                LogContract.info(log, LogDomain.CALENDAR, "slot_released")
                        .outcome(LogOutcome.SUCCESS)
                        .entity("slot", slotId)
                        .emit();
            }
        });
    }

    @Override
    public void confirmSlot(String slotId, String holdingToken) {
        executeInTransactionWithoutResult(status -> {
            TenantTimeSlot slot = timeSlotRepository.findByIdForUpdate(slotId)
                    .orElseThrow(() -> new CalendarException(CalendarErrorCode.SLOT_NOT_FOUND));

            if (slot.getHoldingToken() == null || !slot.getHoldingToken().equals(holdingToken)) {
                LogContract.warn(log, LogDomain.CALENDAR, "slot_confirm_rejected")
                        .outcome(LogOutcome.REJECTED)
                        .entity("slot", slotId)
                        .reason("Token mismatch")
                        .emit();
                throw new CalendarException(
                        CalendarErrorCode.HOLDING_TOKEN_INVALID,
                        "Mã giữ chỗ không hợp lệ hoặc không khớp."
                );
            }

            if (slot.getHoldingExpiresAt() != null && slot.getHoldingExpiresAt().isBefore(LocalDateTime.now())) {
                LogContract.warn(log, LogDomain.CALENDAR, "slot_confirm_rejected")
                        .outcome(LogOutcome.REJECTED)
                        .entity("slot", slotId)
                        .reason("Holding lease expired")
                        .emit();
                throw new CalendarException(
                        CalendarErrorCode.HOLDING_TOKEN_EXPIRED,
                        "Thời gian giữ chỗ của bạn đã hết hạn. Vui lòng chọn lại khung giờ."
                );
            }

            int currentBooked = slot.getBookedCount() != null ? slot.getBookedCount() : 0;
            int newBooked = currentBooked + 1;
            slot.setBookedCount(newBooked);

            if (newBooked >= slot.getCapacityPerSlot()) {
                slot.setStatus(TimeSlotStatus.BOOKED);
            } else {
                slot.setStatus(TimeSlotStatus.AVAILABLE);
            }

            slot.setHoldingToken(null);
            slot.setHoldingExpiresAt(null);
            TenantTimeSlot saved = timeSlotRepository.save(slot);
            timeSlotRepository.flush();

            slotGeneratorService.syncAvailableSlotsToValkey(saved.getService().getId(), saved.getWorkDate());
            LogContract.info(log, LogDomain.CALENDAR, "slot_confirmed")
                    .outcome(LogOutcome.SUCCESS)
                    .entity("slot", slotId)
                    .field("booked", newBooked + "/" + slot.getCapacityPerSlot())
                    .field("status", slot.getStatus())
                    .emit();
        });
    }

    @Override
    public int releaseExpiredHoldingSlots() {
        int totalReleased = 0;
        for (int batchIndex = 0; batchIndex < MAX_CLEANUP_BATCHES_PER_RUN; batchIndex++) {
            int batchReleased = releaseExpiredBatch(LocalDateTime.now());
            totalReleased += batchReleased;
            if (batchReleased < CLEANUP_BATCH_SIZE) {
                break;
            }
        }
        return totalReleased;
    }

    private int releaseExpiredBatch(LocalDateTime now) {
        return executeInTransaction(status -> {
            // FOR UPDATE SKIP LOCKED skips any slot currently held/confirmed by an active checkout transaction
            List<TenantTimeSlot> expired = timeSlotRepository.findExpiredHoldingSlots(
                    now, PageRequest.of(0, CLEANUP_BATCH_SIZE));
            if (expired.isEmpty()) {
                return 0;
            }

            Set<ServiceDateKey> affectedServiceDates = new LinkedHashSet<>();
            for (TenantTimeSlot slot : expired) {
                slot.setStatus(TimeSlotStatus.AVAILABLE);
                slot.setHoldingToken(null);
                slot.setHoldingExpiresAt(null);
                affectedServiceDates.add(new ServiceDateKey(slot.getService().getId(), slot.getWorkDate()));
            }

            timeSlotRepository.saveAll(expired);
            timeSlotRepository.flush();

            // Resync each affected (serviceId, workDate) pair once per batch in Valkey
            for (ServiceDateKey key : affectedServiceDates) {
                slotGeneratorService.syncAvailableSlotsToValkey(key.serviceId(), key.workDate());
            }

            LogContract.info(log, LogDomain.CALENDAR, "expired_holds_released")
                    .outcome(LogOutcome.SUCCESS)
                    .field("slotCount", expired.size())
                    .field("serviceDateCount", affectedServiceDates.size())
                    .emit();
            return expired.size();
        });
    }

    private record ServiceDateKey(String serviceId, LocalDate workDate) {
    }
}

