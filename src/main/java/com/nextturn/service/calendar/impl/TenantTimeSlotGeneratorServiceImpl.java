package com.nextturn.service.calendar.impl;

import com.nextturn.dto.calendar.TimeSlotResponse;
import com.nextturn.entity.TenantService;
import com.nextturn.entity.TenantShift;
import com.nextturn.entity.TenantTimeSlot;
import com.nextturn.enums.TimeSlotStatus;
import com.nextturn.repository.calendar.TenantShiftRepository;
import com.nextturn.repository.calendar.TenantTimeSlotRepository;
import com.nextturn.service.BaseService;
import com.nextturn.service.calendar.TenantTimeSlotGeneratorService;
import com.nextturn.service.tenant.TenantCacheService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Default implementation of {@link TenantTimeSlotGeneratorService} providing automated
 * decomposition of shifts into capacity booking windows and Valkey synchronization.
 */
@Service
public class TenantTimeSlotGeneratorServiceImpl extends BaseService implements TenantTimeSlotGeneratorService {

    private static final Logger log = LoggerFactory.getLogger(TenantTimeSlotGeneratorServiceImpl.class);

    private final TenantTimeSlotRepository timeSlotRepository;
    private final TenantShiftRepository shiftRepository;
    private final TenantCacheService tenantCacheService;

    public TenantTimeSlotGeneratorServiceImpl(
            TenantTimeSlotRepository timeSlotRepository,
            TenantShiftRepository shiftRepository,
            TenantCacheService tenantCacheService,
            TransactionTemplate transactionTemplate
    ) {
        super(transactionTemplate);
        this.timeSlotRepository = timeSlotRepository;
        this.shiftRepository = shiftRepository;
        this.tenantCacheService = tenantCacheService;
    }

    @Override
    @Transactional
    public List<TimeSlotResponse> generateTimeSlotsForShift(TenantShift shift) {
        if (shift == null || shift.getService() == null) {
            log.debug("Skipping slot generation: shift or service is null");
            return List.of();
        }

        TenantService service = shift.getService();
        LocalDate workDate = shift.getWorkDate();
        LocalTime shiftStart = shift.getShiftStart();
        LocalTime shiftEnd = shift.getShiftEnd();
        int standardDuration = service.getStandardDuration() != null ? service.getStandardDuration() : 15;

        // Step 1: Compute candidate slot windows: [Shift_start + (k-1)*T, Shift_start + k*T] <= Shift_end
        List<TimeWindow> windows = calculateSlotWindows(shiftStart, shiftEnd, standardDuration);
        if (windows.isEmpty()) {
            log.warn("No valid slot windows generated for shift [{}] (duration < standardDuration)", shift.getId());
            return List.of();
        }

        List<TenantTimeSlot> slotsToSave = new ArrayList<>();

        for (TimeWindow window : windows) {
            LocalTime slotStart = window.start();
            LocalTime slotEnd = window.end();

            // Step 2: Aggregate capacity: capacity_per_slot = COUNT(active_staffs covering this slot)
            int capacity = shiftRepository.countActiveStaffCoveringSlot(service.getId(), workDate, slotStart, slotEnd);
            int safeCapacity = Math.max(1, capacity);

            Optional<TenantTimeSlot> existingOpt = timeSlotRepository
                    .findByServiceIdAndWorkDateAndSlotStartAndSlotEnd(service.getId(), workDate, slotStart, slotEnd);

            TenantTimeSlot slot;
            if (existingOpt.isPresent()) {
                slot = existingOpt.get();
                slot.setCapacityPerSlot(safeCapacity);
                int bookedCount = slot.getBookedCount() != null ? slot.getBookedCount() : 0;
                if (bookedCount >= safeCapacity) {
                    slot.setStatus(TimeSlotStatus.BOOKED);
                } else if (slot.getStatus() == TimeSlotStatus.BOOKED) {
                    slot.setStatus(TimeSlotStatus.AVAILABLE);
                }
            } else {
                slot = TenantTimeSlot.builder()
                        .id(UUID.randomUUID().toString())
                        .service(service)
                        .workDate(workDate)
                        .slotStart(slotStart)
                        .slotEnd(slotEnd)
                        .capacityPerSlot(safeCapacity)
                        .bookedCount(0)
                        .status(TimeSlotStatus.AVAILABLE)
                        .build();
            }
            slotsToSave.add(slot);
        }

        // Step 3: Batch insert / update into MySQL
        List<TenantTimeSlot> savedSlots = timeSlotRepository.saveAll(slotsToSave);
        timeSlotRepository.flush();
        log.info("Batch inserted/updated {} time slots for service [{}] on [{}]",
                savedSlots.size(), service.getServiceCode(), workDate);

        // Step 4: Synchronize available slots into Valkey cache
        syncAvailableSlotsToValkey(service.getId(), workDate);

        return savedSlots.stream()
                .map(TimeSlotResponse::fromEntity)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional
    public void recalculateSlotsForCancelledShift(TenantShift shift) {
        if (shift == null || shift.getService() == null) {
            return;
        }

        TenantService service = shift.getService();
        LocalDate workDate = shift.getWorkDate();
        int standardDuration = service.getStandardDuration() != null ? service.getStandardDuration() : 15;

        List<TimeWindow> windows = calculateSlotWindows(shift.getShiftStart(), shift.getShiftEnd(), standardDuration);
        List<TenantTimeSlot> toUpdate = new ArrayList<>();
        List<TenantTimeSlot> toDelete = new ArrayList<>();

        for (TimeWindow window : windows) {
            recalculateSlotCapacityForWindow(service, workDate, window, toDelete, toUpdate);
        }

        if (!toDelete.isEmpty()) {
            timeSlotRepository.deleteAll(toDelete);
        }
        if (!toUpdate.isEmpty()) {
            timeSlotRepository.saveAll(toUpdate);
        }
        timeSlotRepository.flush();

        // Resync cache
        syncAvailableSlotsToValkey(service.getId(), workDate);
    }

    @Override
    @Transactional
    public void recalculateSlotsForShiftAdjustment(TenantShift shift, LocalTime oldStart, LocalTime oldEnd) {
        if (shift == null || shift.getService() == null) {
            return;
        }

        TenantService service = shift.getService();
        LocalDate workDate = shift.getWorkDate();
        int standardDuration = service.getStandardDuration() != null ? service.getStandardDuration() : 15;

        LocalTime newStart = shift.getShiftStart();
        LocalTime newEnd = shift.getShiftEnd();

        List<TenantTimeSlot> toDelete = new ArrayList<>();
        List<TenantTimeSlot> toUpdate = new ArrayList<>();

        // If shift start was pushed forward (shortened at start): cut window [oldStart, newStart]
        if (newStart.isAfter(oldStart)) {
            List<TimeWindow> cutWindows = calculateSlotWindows(oldStart, newStart, standardDuration);
            for (TimeWindow window : cutWindows) {
                recalculateSlotCapacityForWindow(service, workDate, window, toDelete, toUpdate);
            }
        }

        // If shift end was pulled back (shortened at end): cut window [newEnd, oldEnd]
        if (newEnd.isBefore(oldEnd)) {
            List<TimeWindow> cutWindows = calculateSlotWindows(newEnd, oldEnd, standardDuration);
            for (TimeWindow window : cutWindows) {
                recalculateSlotCapacityForWindow(service, workDate, window, toDelete, toUpdate);
            }
        }

        if (!toDelete.isEmpty()) {
            timeSlotRepository.deleteAll(toDelete);
        }
        if (!toUpdate.isEmpty()) {
            timeSlotRepository.saveAll(toUpdate);
        }
        timeSlotRepository.flush();

        // Regenerate / upsert capacity for the new operational window and sync cache
        generateTimeSlotsForShift(shift);
    }

    private void recalculateSlotCapacityForWindow(
            TenantService service,
            LocalDate workDate,
            TimeWindow window,
            List<TenantTimeSlot> toDelete,
            List<TenantTimeSlot> toUpdate
    ) {
        Optional<TenantTimeSlot> slotOpt = timeSlotRepository
                .findByServiceIdAndWorkDateAndSlotStartAndSlotEnd(service.getId(), workDate, window.start(), window.end());

        if (slotOpt.isPresent()) {
            TenantTimeSlot slot = slotOpt.get();
            int remainingCapacity = shiftRepository.countActiveStaffCoveringSlot(
                    service.getId(), workDate, window.start(), window.end());

            if (remainingCapacity <= 0) {
                if (slot.getBookedCount() == null || slot.getBookedCount() == 0) {
                    toDelete.add(slot);
                } else {
                    // Slot still has booked appointments: lock slot
                    slot.setCapacityPerSlot(Math.max(1, slot.getBookedCount()));
                    slot.setStatus(TimeSlotStatus.BOOKED);
                    toUpdate.add(slot);
                }
            } else {
                slot.setCapacityPerSlot(remainingCapacity);
                int booked = slot.getBookedCount() != null ? slot.getBookedCount() : 0;
                if (booked >= remainingCapacity) {
                    slot.setStatus(TimeSlotStatus.BOOKED);
                } else {
                    slot.setStatus(TimeSlotStatus.AVAILABLE);
                }
                toUpdate.add(slot);
            }
        }
    }

    @Override
    public void syncAvailableSlotsToValkey(String serviceId, LocalDate workDate) {
        try {
            String tenantCode = resolveCurrentTenantCode();
            List<TenantTimeSlot> availableSlots = timeSlotRepository
                    .findByServiceIdAndWorkDateAndStatusOrderBySlotStartAsc(serviceId, workDate, TimeSlotStatus.AVAILABLE);

            List<TimeSlotResponse> responses = availableSlots.stream()
                    .map(TimeSlotResponse::fromEntity)
                    .collect(Collectors.toList());

            tenantCacheService.cacheTenantSlots(tenantCode, serviceId, workDate, responses);
            log.debug("Synchronized {} available slots to Valkey for tenant [{}], service [{}], date [{}]",
                    responses.size(), tenantCode, serviceId, workDate);
        } catch (Exception ex) {
            log.warn("Valkey slot sync failed: {}", ex.getMessage());
        }
    }

    private List<TimeWindow> calculateSlotWindows(LocalTime start, LocalTime end, int standardDuration) {
        List<TimeWindow> windows = new ArrayList<>();
        LocalTime currentStart = start;

        while (true) {
            LocalTime currentEnd = currentStart.plusMinutes(standardDuration);

            if (currentEnd.isAfter(end) || currentEnd.isBefore(currentStart)) {
                break;
            }

            windows.add(new TimeWindow(currentStart, currentEnd));
            currentStart = currentEnd;

            if (!currentStart.isBefore(end)) {
                break;
            }
        }
        return windows;
    }

    private record TimeWindow(LocalTime start, LocalTime end) {}
}

