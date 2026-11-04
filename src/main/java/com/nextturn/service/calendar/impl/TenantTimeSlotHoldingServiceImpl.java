package com.nextturn.service.calendar.impl;

import com.nextturn.constant.ValkeyKeyConstant;
import com.nextturn.dto.calendar.SlotHoldingResponse;
import com.nextturn.entity.TenantTimeSlot;
import com.nextturn.enums.TimeSlotStatus;
import com.nextturn.exception.calendar.CalendarErrorCode;
import com.nextturn.exception.calendar.CalendarException;
import com.nextturn.repository.calendar.TenantTimeSlotRepository;
import com.nextturn.service.BaseService;
import com.nextturn.service.calendar.TenantTimeSlotGeneratorService;
import com.nextturn.service.calendar.TenantTimeSlotHoldingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashSet;
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

        // Step 1: Distributed Lock on Valkey (SET NX PX 5000ms)
        boolean lockAcquired = true;
        if (redisTemplate != null) {
            try {
                Boolean setSuccess = redisTemplate.opsForValue()
                        .setIfAbsent(lockKey, "LOCKED", Duration.ofSeconds(5));
                lockAcquired = Boolean.TRUE.equals(setSuccess);
            } catch (Exception ex) {
                log.warn("Valkey distributed lock acquire failed, falling back to DB lock: {}", ex.getMessage());
            }
        }

        if (!lockAcquired) {
            log.warn("Slot [{}] distributed lock rejected for tenant [{}] (race condition detected)", slotId, tenantCode);
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
                        log.warn("Slot [{}] is currently actively held by another customer (expires at {})",
                                slotId, slot.getHoldingExpiresAt());
                        throw new CalendarException(
                                CalendarErrorCode.SLOT_ALREADY_HOLDING_OR_BOOKED,
                                "Khung giờ này vừa có người khác nhanh tay chọn trước. Vui lòng chọn một khung giờ khác."
                        );
                    }
                    log.info("Resetting expired holding lease on slot [{}] (expired at {})", slotId, slot.getHoldingExpiresAt());
                }

                if (slot.getStatus() == TimeSlotStatus.BOOKED ||
                        (slot.getBookedCount() != null && slot.getBookedCount() >= slot.getCapacityPerSlot())) {
                    log.warn("Slot [{}] is already fully booked", slotId);
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

                log.info("Successfully acquired hold on slot [{}] with lease token [{}] until [{}]",
                        slotId, token, expiresAt);

                return SlotHoldingResponse.builder()
                        .slotId(saved.getId())
                        .holdingToken(token)
                        .holdingExpiresAt(expiresAt)
                        .status(TimeSlotStatus.HOLDING)
                        .message(String.format("Khung giờ đã được giữ chỗ tạm thời trong %d phút", leaseMinutes))
                        .build();
            });
        } finally {
            // Release distributed lock
            if (redisTemplate != null) {
                try {
                    redisTemplate.delete(lockKey);
                } catch (Exception ex) {
                    log.warn("Failed to release Valkey distributed lock key [{}]: {}", lockKey, ex.getMessage());
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
                log.warn("Rejected slot release on [{}]: token mismatch", slotId);
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
                log.info("Released held slot [{}] back to AVAILABLE", slotId);
            }
        });
    }

    @Override
    public void confirmSlot(String slotId, String holdingToken) {
        executeInTransactionWithoutResult(status -> {
            TenantTimeSlot slot = timeSlotRepository.findByIdForUpdate(slotId)
                    .orElseThrow(() -> new CalendarException(CalendarErrorCode.SLOT_NOT_FOUND));

            if (slot.getHoldingToken() == null || !slot.getHoldingToken().equals(holdingToken)) {
                log.warn("Rejected slot confirmation on [{}]: token mismatch", slotId);
                throw new CalendarException(
                        CalendarErrorCode.HOLDING_TOKEN_INVALID,
                        "Mã giữ chỗ không hợp lệ hoặc không khớp."
                );
            }

            if (slot.getHoldingExpiresAt() != null && slot.getHoldingExpiresAt().isBefore(LocalDateTime.now())) {
                log.warn("Rejected slot confirmation on [{}]: holding lease expired", slotId);
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
            log.info("Confirmed booking on slot [{}] (booked count: {}/{}, status: {})",
                    slotId, newBooked, slot.getCapacityPerSlot(), slot.getStatus());
        });
    }

    @Override
    public int releaseExpiredHoldingSlots() {
        return executeInTransaction(status -> {
            LocalDateTime now = LocalDateTime.now();
            List<TenantTimeSlot> expired = timeSlotRepository.findExpiredHoldingSlots(now);
            if (expired.isEmpty()) {
                return 0;
            }

            Set<String> affectedServiceDates = new HashSet<>();
            for (TenantTimeSlot slot : expired) {
                slot.setStatus(TimeSlotStatus.AVAILABLE);
                slot.setHoldingToken(null);
                slot.setHoldingExpiresAt(null);
                affectedServiceDates.add(slot.getService().getId() + ":" + slot.getWorkDate());
            }

            timeSlotRepository.saveAll(expired);
            timeSlotRepository.flush();

            // Resync affected services/dates in Valkey
            for (TenantTimeSlot slot : expired) {
                slotGeneratorService.syncAvailableSlotsToValkey(slot.getService().getId(), slot.getWorkDate());
            }

            log.info("Automatically released {} expired holding slots across {} service-dates",
                    expired.size(), affectedServiceDates.size());
            return expired.size();
        });
    }
}

