package com.nextturn.service.appointment;

import com.nextturn.dto.appointment.AppointmentResponse;
import com.nextturn.dto.calendar.TimeSlotResponse;

import java.time.LocalDate;
import java.util.List;

/**
 * Read-only query contract for customer appointment slot availability (REQ-APT-01),
 * appointment lookup by code & phone (REQ-APT-04), customer appointment history (REQ-APT-05),
 * and tenant appointment listings.
 */
public interface TenantAppointmentQueryService {

    /**
     * Queries available time slots for a service and date, enforcing facility booking policies (REQ-APT-01).
     */
    List<TimeSlotResponse> getAvailableSlotsForBooking(String serviceId, LocalDate appointmentDate);

    /**
     * Looks up a customer appointment by exact match of appointment code and phone number (REQ-APT-04).
     */
    AppointmentResponse lookupAppointment(String appointmentCode, String customerPhone);

    /**
     * Retrieves past, current, and future appointments of an authenticated customer at the current
     * logged-in tenant facility via {@code central__global_user_appointments} (REQ-APT-05).
     */
    List<AppointmentResponse> getCustomerAppointmentsForCurrentTenant(String authenticatedUserId);

    /**
     * Retrieves all appointments for the current tenant facility.
     */
    List<AppointmentResponse> getAllTenantAppointments();

    /**
     * Retrieves a single appointment by ID or code in the current tenant facility.
     */
    AppointmentResponse getAppointmentById(String appointmentId);
}
