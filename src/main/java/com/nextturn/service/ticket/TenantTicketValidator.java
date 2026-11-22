package com.nextturn.service.ticket;

import com.nextturn.dto.ticket.IssueTicketRequest;
import com.nextturn.entity.TenantAppointment;
import com.nextturn.entity.TenantService;

import java.time.LocalDate;

/**
 * Contract for validating queue ticket intake eligibility (REQ-TICKET-01, REQ-CHECK-IN-01, REQ-CHECK-IN-02).
 */
public interface TenantTicketValidator {

    /**
     * Resolves and validates that the requested service exists and is ACTIVE.
     */
    TenantService validateActiveService(String serviceId);

    /**
     * Resolves and validates a pre-booked appointment (via appointmentId/code or QR token)
     * against today's date and the check-in window {@code [-15 min, +10 min]} (REQ-CHECK-IN-01).
     */
    TenantAppointment validateAppointmentForCheckIn(IssueTicketRequest request, LocalDate today);

    /**
     * Validates walk-in ticket intake eligibility: 10-digit phone required, no duplicate active ticket
     * for the same service today, and active specialist shift/capacity availability (REQ-CHECK-IN-02).
     */
    void validateWalkInEligibility(TenantService service, String customerPhone, LocalDate today);

    /**
     * Validates that the tenant has not exceeded its configured {@code daily_ticket_quota}.
     */
    void validateTenantDailyTicketQuota(String tenantCode, LocalDate today);
}
