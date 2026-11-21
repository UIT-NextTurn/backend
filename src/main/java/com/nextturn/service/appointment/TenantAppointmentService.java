package com.nextturn.service.appointment;

import com.nextturn.dto.appointment.AppointmentResponse;
import com.nextturn.dto.appointment.CancelAppointmentRequest;
import com.nextturn.dto.appointment.CreateAppointmentRequest;
import com.nextturn.dto.appointment.RescheduleAppointmentRequest;
import com.nextturn.dto.calendar.SlotHoldingResponse;
import com.nextturn.dto.calendar.TimeSlotResponse;

import java.time.LocalDate;
import java.util.List;

/**
 * Domain service contract for Customer Appointment Booking, Rescheduling, Cancellation, and History
 * (REQ-APT-01, REQ-APT-02, REQ-APT-03, REQ-APT-04, REQ-APT-05).
 */
public interface TenantAppointmentService {

    /**
     * Queries available time slots for a service and date, enforcing facility booking policies (REQ-APT-01).
     *
     * @param serviceId       service UUID
     * @param appointmentDate requested booking date
     * @return list of available policy-compliant time slots
     */
    List<TimeSlotResponse> getAvailableSlotsForBooking(String serviceId, LocalDate appointmentDate);

    /**
     * Temporarily holds an available time slot for 10 minutes using distributed locking (REQ-APT-02).
     *
     * @param slotId         time slot UUID
     * @param holdingMinutes lease duration in minutes (defaults to 10)
     * @return slot holding response containing {@code holdingToken} and countdown expiration
     */
    SlotHoldingResponse holdSlotForBooking(String slotId, Integer holdingMinutes);

    /**
     * Confirms customer personal details, validates holding session and lead-time policy,
     * and creates a confirmed appointment with code {@code APT-YYYYMMDD-XXXX} and QR token (REQ-APT-03).
     *
     * @param request             appointment creation payload
     * @param authenticatedUserId optional authenticated customer userId (from OTP or Widget SSO JWT)
     * @return created appointment response
     */
    AppointmentResponse createAppointment(CreateAppointmentRequest request, String authenticatedUserId);

    /**
     * Looks up a customer appointment by exact match of appointment code and phone number (REQ-APT-04).
     *
     * @param appointmentCode unique appointment code (e.g. APT-20261122-0001) or appointment UUID
     * @param customerPhone   10-digit customer phone number
     * @return appointment detail response
     */
    AppointmentResponse lookupAppointment(String appointmentCode, String customerPhone);

    /**
     * Cancels a confirmed appointment if within facility {@code cancellation_deadline_hours},
     * releases slot capacity back to AVAILABLE, and sends cancellation notification (REQ-APT-04).
     *
     * @param appointmentIdentifier appointment UUID or appointmentCode
     * @param request               cancellation reason and optional phone verification
     * @param authenticatedUserId   optional authenticated customer userId
     * @param isAdminOrStaff        true if invoked by TENANT_ADMIN or COUNTER_STAFF (bypasses deadline check)
     * @return cancelled appointment response
     */
    AppointmentResponse cancelAppointment(
            String appointmentIdentifier,
            CancelAppointmentRequest request,
            String authenticatedUserId,
            boolean isAdminOrStaff
    );

    /**
     * Reschedules a confirmed appointment to a new available time slot in a single atomic transaction,
     * releasing the old slot and locking the new slot while respecting {@code cancellation_deadline_hours}
     * and {@code min_lead_time_hours} (REQ-APT-04).
     *
     * @param appointmentIdentifier appointment UUID or appointmentCode
     * @param request               new slot ID, optional holding token, and optional phone verification
     * @param authenticatedUserId   optional authenticated customer userId
     * @param isAdminOrStaff        true if invoked by TENANT_ADMIN or COUNTER_STAFF
     * @return rescheduled appointment response
     */
    AppointmentResponse rescheduleAppointment(
            String appointmentIdentifier,
            RescheduleAppointmentRequest request,
            String authenticatedUserId,
            boolean isAdminOrStaff
    );

    /**
     * Retrieves past, current, and future appointments of an authenticated customer at the current
     * logged-in tenant facility via {@code central__global_user_appointments} (REQ-APT-05).
     *
     * @param authenticatedUserId customer userId extracted from JWT session
     * @return list of customer appointments sorted by date descending
     */
    List<AppointmentResponse> getCustomerAppointmentsForCurrentTenant(String authenticatedUserId);

    /**
     * Retrieves all appointments for the current tenant facility (for Tenant Admin / Counter Staff).
     *
     * @return list of tenant appointments
     */
    List<AppointmentResponse> getAllTenantAppointments();

    /**
     * Retrieves a single appointment by ID in the current tenant facility.
     *
     * @param appointmentId appointment UUID
     * @return appointment detail response
     */
    AppointmentResponse getAppointmentById(String appointmentId);
}
