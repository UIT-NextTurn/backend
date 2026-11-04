package com.nextturn.service.calendar;

import com.nextturn.entity.TenantService;
import com.nextturn.entity.TenantStaff;

import java.time.LocalDate;
import java.time.LocalTime;

/**
 * Contract for validating business rules and constraints on work shifts and calendar scheduling.
 */
public interface TenantCalendarValidator {

    /**
     * Validates that the staff member exists, is not deleted, and is currently Active.
     *
     * @param staffId staff UUID
     * @return active TenantStaff entity
     */
    TenantStaff validateStaff(String staffId);

    /**
     * Resolves and validates the service associated with the staff member and shift.
     *
     * @param staff staff entity
     * @param requestedServiceId optional service ID passed in request
     * @return resolved TenantService
     */
    TenantService validateAndResolveService(TenantStaff staff, String requestedServiceId);

    /**
     * Validates work date constraint (workDate must be >= current date).
     *
     * @param workDate scheduled work date
     */
    void validateWorkDate(LocalDate workDate);

    /**
     * Validates shift start and end times (shiftStart < shiftEnd).
     *
     * @param shiftStart start time
     * @param shiftEnd end time
     */
    void validateTimeWindow(LocalTime shiftStart, LocalTime shiftEnd);

    /**
     * Validates that the shift duration is greater than or equal to the standard duration of the assigned service.
     *
     * @param shiftStart shift start time
     * @param shiftEnd shift end time
     * @param service target service
     */
    void validateShiftDuration(LocalTime shiftStart, LocalTime shiftEnd, TenantService service);

    /**
     * Checks for overlapping active shifts for the staff member on the given date.
     *
     * @param staffId staff UUID
     * @param workDate scheduled work date
     * @param shiftStart shift start time
     * @param shiftEnd shift end time
     */
    void validateNoOverlap(String staffId, LocalDate workDate, LocalTime shiftStart, LocalTime shiftEnd);

    /**
     * Checks for overlapping active shifts for the staff member on the given date, excluding a specific shift ID.
     *
     * @param staffId staff UUID
     * @param workDate scheduled work date
     * @param shiftStart shift start time
     * @param shiftEnd shift end time
     * @param excludedShiftId shift UUID to exclude from overlap check
     */
    void validateNoOverlapExcludingShift(String staffId, LocalDate workDate, LocalTime shiftStart, LocalTime shiftEnd, String excludedShiftId);
}

