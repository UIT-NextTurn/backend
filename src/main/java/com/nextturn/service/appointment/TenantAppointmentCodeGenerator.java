package com.nextturn.service.appointment;

import java.time.LocalDate;

/**
 * Contract for generating unique sequential appointment codes ({@code APT-YYYYMMDD-XXXX})
 * and check-in QR payload tokens (REQ-APT-03).
 */
public interface TenantAppointmentCodeGenerator {

    /**
     * Generates a unique sequential appointment code for the specified work date.
     *
     * @param workDate scheduled appointment date
     * @return unique appointment code in format {@code APT-YYYYMMDD-XXXX}
     */
    String generateAppointmentCode(LocalDate workDate);

    /**
     * Generates a unique QR check-in token payload bound to the tenant and appointment code.
     *
     * @param tenantCode      current tenant code
     * @param appointmentCode generated appointment code
     * @return unique QR check-in payload string
     */
    String generateQrToken(String tenantCode, String appointmentCode);
}
