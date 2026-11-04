package com.nextturn.service.calendar;

import com.nextturn.dto.calendar.CreateShiftRequest;
import com.nextturn.dto.calendar.ShiftResponse;
import com.nextturn.dto.calendar.UpdateShiftRequest;
import com.nextturn.enums.ShiftStatus;

import java.time.LocalDate;
import java.util.List;

/**
 * Service contract managing staff work shift allocations, recurring schedules, cancellation,
 * and coordinating automated time slot generation.
 */
public interface TenantCalendarService {

    /**
     * Creates one or multiple scheduled work shifts for a staff member and triggers automatic time slot generation.
     *
     * @param request shift creation request
     * @return list of created shift responses
     */
    List<ShiftResponse> createShifts(CreateShiftRequest request);

    /**
     * Retrieves shift details by ID.
     *
     * @param shiftId shift UUID
     * @return shift response
     */
    ShiftResponse getShiftById(String shiftId);

    /**
     * Searches shifts by filter criteria.
     *
     * @param workDate specific work date
     * @param startDate date range start
     * @param endDate date range end
     * @param staffId staff UUID
     * @param serviceId service UUID
     * @param status shift operational status
     * @return list of matching shift responses
     */
    List<ShiftResponse> searchShifts(
            LocalDate workDate,
            LocalDate startDate,
            LocalDate endDate,
            String staffId,
            String serviceId,
            ShiftStatus status
    );

    /**
     * Cancels a scheduled shift and recalculates affected service time slot capacities.
     * Blocked if any booking exists in the shift window.
     *
     * @param shiftId shift UUID
     * @return updated shift response
     */
    ShiftResponse cancelShift(String shiftId);

    /**
     * Adjusts the operational time window of an existing scheduled work shift.
     * Blocked if booked appointments exist in the cut window.
     *
     * @param shiftId shift UUID
     * @param request shift adjustment request
     * @return updated shift response
     */
    ShiftResponse updateShift(String shiftId, UpdateShiftRequest request);
}

