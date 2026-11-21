package com.nextturn.service.appointment.impl;

import com.nextturn.entity.TenantAppointment;
import com.nextturn.entity.TenantService;
import com.nextturn.entity.TenantTimeSlot;
import com.nextturn.service.appointment.TenantAppointmentNotificationService;
import com.nextturn.service.notification.EmailService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Default implementation of {@link TenantAppointmentNotificationService} rendering centralized HTML email
 * templates from {@code classpath:templates/email/} and dispatching them asynchronously (REQ-APT-03, REQ-APT-04).
 */
@Service
public class TenantAppointmentNotificationServiceImpl implements TenantAppointmentNotificationService {

    private static final Logger log = LoggerFactory.getLogger(TenantAppointmentNotificationServiceImpl.class);
    private static final String CONFIRMATION_TEMPLATE_PATH = "templates/email/appointment-confirmation.html";
    private static final String CANCELLATION_TEMPLATE_PATH = "templates/email/appointment-cancellation.html";

    private final EmailService emailService;
    private final Map<String, String> templateCache = new ConcurrentHashMap<>();

    public TenantAppointmentNotificationServiceImpl(@Autowired(required = false) EmailService emailService) {
        this.emailService = emailService;
    }

    @Override
    public void sendConfirmationEmailAsync(TenantAppointment appointment, String tenantName) {
        if (emailService == null || appointment == null
                || appointment.getCustomerEmail() == null || appointment.getCustomerEmail().isBlank()) {
            return;
        }
        try {
            TenantTimeSlot slot = appointment.getSlot();
            TenantService service = appointment.getService();
            String safeTenant = tenantName != null ? tenantName : "";
            String subject = String.format("[%s] Xác nhận đặt lịch hẹn thành công - Mã %s",
                    safeTenant, appointment.getAppointmentCode());

            Map<String, String> variables = Map.of(
                    "customerName", appointment.getCustomerName() != null ? appointment.getCustomerName() : "",
                    "tenantName", safeTenant,
                    "appointmentCode", appointment.getAppointmentCode() != null ? appointment.getAppointmentCode() : "",
                    "serviceName", service != null && service.getServiceName() != null ? service.getServiceName() : "",
                    "workDate", slot != null && slot.getWorkDate() != null ? slot.getWorkDate().toString() : "",
                    "slotStart", slot != null && slot.getSlotStart() != null ? slot.getSlotStart().toString() : "",
                    "slotEnd", slot != null && slot.getSlotEnd() != null ? slot.getSlotEnd().toString() : "",
                    "qrToken", appointment.getQrToken() != null ? appointment.getQrToken() : ""
            );

            String html = renderTemplate(CONFIRMATION_TEMPLATE_PATH, variables);
            emailService.sendHtmlEmailAsync(appointment.getCustomerEmail(), subject, html);
        } catch (Exception ex) {
            log.warn("Non-fatal: Failed to send appointment confirmation email for [{}]: {}",
                    appointment.getAppointmentCode(), ex.getMessage());
        }
    }

    @Override
    public void sendCancellationEmailAsync(TenantAppointment appointment, String tenantName) {
        if (emailService == null || appointment == null
                || appointment.getCustomerEmail() == null || appointment.getCustomerEmail().isBlank()) {
            return;
        }
        try {
            TenantTimeSlot slot = appointment.getSlot();
            TenantService service = appointment.getService();
            String safeTenant = tenantName != null ? tenantName : "";
            String subject = String.format("[%s] Thông báo hủy lịch hẹn - Mã %s",
                    safeTenant, appointment.getAppointmentCode());

            Map<String, String> variables = Map.of(
                    "customerName", appointment.getCustomerName() != null ? appointment.getCustomerName() : "",
                    "tenantName", safeTenant,
                    "appointmentCode", appointment.getAppointmentCode() != null ? appointment.getAppointmentCode() : "",
                    "serviceName", service != null && service.getServiceName() != null ? service.getServiceName() : "",
                    "workDate", slot != null && slot.getWorkDate() != null ? slot.getWorkDate().toString() : "",
                    "slotStart", slot != null && slot.getSlotStart() != null ? slot.getSlotStart().toString() : "",
                    "slotEnd", slot != null && slot.getSlotEnd() != null ? slot.getSlotEnd().toString() : "",
                    "cancellationReason", appointment.getCancellationReason() != null ? appointment.getCancellationReason() : ""
            );

            String html = renderTemplate(CANCELLATION_TEMPLATE_PATH, variables);
            emailService.sendHtmlEmailAsync(appointment.getCustomerEmail(), subject, html);
        } catch (Exception ex) {
            log.warn("Non-fatal: Failed to send appointment cancellation email for [{}]: {}",
                    appointment.getAppointmentCode(), ex.getMessage());
        }
    }

    private String renderTemplate(String templatePath, Map<String, String> variables) {
        String template = templateCache.computeIfAbsent(templatePath, path -> {
            try {
                ClassPathResource resource = new ClassPathResource(path);
                try (InputStream is = resource.getInputStream()) {
                    return new String(is.readAllBytes(), StandardCharsets.UTF_8);
                }
            } catch (Exception ex) {
                log.warn("Could not load email template from classpath [{}]: {}", path, ex.getMessage());
                return "";
            }
        });

        String result = template;
        for (Map.Entry<String, String> entry : variables.entrySet()) {
            result = result.replace("{{" + entry.getKey() + "}}", entry.getValue() != null ? entry.getValue() : "");
        }
        return result;
    }
}
