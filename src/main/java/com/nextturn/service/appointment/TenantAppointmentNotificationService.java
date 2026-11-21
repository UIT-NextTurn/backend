package com.nextturn.service.appointment;

import com.nextturn.entity.TenantAppointment;

/**
 * Contract for dispatching asynchronous email notifications for appointment confirmation
 * (with QR check-in token) and cancellation (REQ-APT-03, REQ-APT-04).
 */
public interface TenantAppointmentNotificationService {

    /**
     * Asynchronously sends an appointment confirmation email containing the booking code and QR token.
     */
    void sendConfirmationEmailAsync(TenantAppointment appointment, String tenantName);

    /**
     * Asynchronously sends an appointment cancellation confirmation email.
     */
    void sendCancellationEmailAsync(TenantAppointment appointment, String tenantName);
}
