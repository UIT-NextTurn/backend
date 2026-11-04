package com.nextturn.service.calendar;

import com.nextturn.dto.calendar.TimeSlotResponse;
import com.nextturn.entity.TenantShift;

import java.time.LocalDate;
import java.util.List;

/**
 * Contract for automatically decomposing staff work shifts into sequential capacity time slots,
 * calculating capacity per slot, and synchronizing with Valkey cache.
 */
public interface TenantTimeSlotGeneratorService {

    /**
     * Decomposes the given shift into sequential time slots, calculates capacity per slot,
     * batch saves records into MySQL, and synchronizes available slots with Valkey cache.
     *
     * @param shift newly saved operational work shift
     * @return list of generated/updated time slot responses
     */
    List<TimeSlotResponse> generateTimeSlotsForShift(TenantShift shift);

    /**
     * Recalculates time slot capacity when a shift is cancelled or removed.
     *
     * @param shift cancelled shift
     */
    void recalculateSlotsForCancelledShift(TenantShift shift);

    /**
     * Recalculates time slot capacity and generates new slots when a shift's operational window is adjusted.
     *
     * @param shift adjusted shift entity
     * @param oldStart previous shift start time
     * @param oldEnd previous shift end time
     */
    void recalculateSlotsForShiftAdjustment(TenantShift shift, java.time.LocalTime oldStart, java.time.LocalTime oldEnd);

    /**
     * Reads available slots from the database and updates Valkey cache.
     *
     * @param serviceId service UUID
     * @param workDate scheduled work date
     */
    void syncAvailableSlotsToValkey(String serviceId, LocalDate workDate);
}

