package com.nextturn.service.appointment.impl;

import com.nextturn.repository.appointment.TenantAppointmentRepository;
import com.nextturn.service.appointment.TenantAppointmentCodeGenerator;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/**
 * Default implementation of {@link TenantAppointmentCodeGenerator} generating sequential
 * {@code APT-YYYYMMDD-XXXX} codes and QR check-in payload tokens (REQ-APT-03).
 */
@Component
public class TenantAppointmentCodeGeneratorImpl implements TenantAppointmentCodeGenerator {

    private static final DateTimeFormatter CODE_DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final TenantAppointmentRepository appointmentRepository;

    public TenantAppointmentCodeGeneratorImpl(TenantAppointmentRepository appointmentRepository) {
        this.appointmentRepository = appointmentRepository;
    }

    @Override
    public String generateAppointmentCode(LocalDate workDate) {
        String datePart = (workDate != null ? workDate : LocalDate.now()).format(CODE_DATE_FMT);
        String prefix = "APT-" + datePart + "-";
        long existingCount = appointmentRepository != null
                ? appointmentRepository.countByAppointmentCodeStartingWith(prefix)
                : 0L;

        for (int attempt = 1; attempt <= 100; attempt++) {
            String candidate = String.format("%s%04d", prefix, existingCount + attempt);
            if (appointmentRepository == null || !appointmentRepository.existsByAppointmentCodeIgnoreCase(candidate)) {
                return candidate;
            }
        }
        return prefix + UUID.randomUUID().toString().substring(0, 4).toUpperCase();
    }

    @Override
    public String generateQrToken(String tenantCode, String appointmentCode) {
        String safeTenant = (tenantCode != null && !tenantCode.isBlank()) ? tenantCode.trim().toUpperCase() : "TENANT";
        return "NTQR-" + safeTenant + "-" + appointmentCode + "-"
                + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
    }
}
