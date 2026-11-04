package com.nextturn.service.calendar;

import com.nextturn.dto.calendar.TimeSlotResponse;

import java.time.LocalDate;
import java.util.List;

/**
 * Contract for querying capacity booking time slots across cached Valkey layer and database.
 */
public interface TenantTimeSlotQueryService {

    /**
     * Retrieves available booking time slots for a service on a given date.
     * Checks Valkey cache first for sub-millisecond retrieval; falls back to DB on cache miss.
     *
     * @param serviceId service UUID
     * @param workDate scheduled work date
     * @return list of available time slot responses
     */
    List<TimeSlotResponse> getAvailableSlots(String serviceId, LocalDate workDate);

    /**
     * Retrieves all time slots (including booked and holding) for a service and date for admin overview.
     *
     * @param serviceId service UUID
     * @param workDate scheduled work date
     * @return list of all time slot responses
     */
    List<TimeSlotResponse> getAllSlotsForDate(String serviceId, LocalDate workDate);
}

