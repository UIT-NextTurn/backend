package com.nextturn.service.appointment;

import com.nextturn.entity.TenantAppointment;
import com.nextturn.entity.TenantTimeSlot;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Contract for validating appointment business rules, holding leases, lead times,
 * advance booking windows, and cancellation/rescheduling deadlines (REQ-APT-01 to REQ-APT-05).
 */
public interface TenantAppointmentValidator {

    /**
     * Validates that the target appointment date falls within {@code [today, today + maxAdvanceDays]} (REQ-APT-01).
     */
    void validateAdvanceBookingWindow(LocalDate appointmentDate, int maxAdvanceDays);

    /**
     * Validates that a locked slot is currently in {@code HOLDING} state with a matching non-expired
     * {@code holdingToken} (REQ-APT-03).
     */
    void validateHoldingSession(TenantTimeSlot slot, String providedHoldingToken, LocalDateTime now);

    /**
     * Validates that the slot start time satisfies {@code slotStart - now >= minLeadTimeHours} (REQ-APT-03).
     */
    void validateMinLeadTime(TenantTimeSlot slot, int minLeadTimeHours, LocalDateTime now);

    /**
     * Validates that the customer phone does not already have an active booking on the same slot.
     */
    void validateNoDoubleBooking(String slotId, String customerPhone);

    /**
     * Validates that the appointment is in {@code CONFIRMED} status and (for customers) satisfies
     * {@code appointmentStart - now >= cancellationDeadlineHours} (REQ-APT-04).
     */
    void validateCanModifyOrCancel(TenantAppointment appointment, int cancellationDeadlineHours, boolean isAdminOrStaff);

    /**
     * Determines whether a customer is currently eligible to self-cancel or self-reschedule an appointment.
     */
    boolean canCustomerModifyAppointment(TenantAppointment appointment, int cancellationDeadlineHours);

    /**
     * Resolves an appointment by ID or code and verifies customer ownership (phone or userId) or staff privilege.
     */
    TenantAppointment resolveAndAuthorizeAppointment(
            String appointmentIdentifier,
            String customerPhone,
            String authenticatedUserId,
            boolean isAdminOrStaff
    );
}
