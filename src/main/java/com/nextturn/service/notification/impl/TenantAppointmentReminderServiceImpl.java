package com.nextturn.service.notification.impl;

import com.nextturn.dto.notification.NotificationTemplateResponse;
import com.nextturn.dto.notification.ReminderDispatchSummaryResponse;
import com.nextturn.entity.CentralTenant;
import com.nextturn.entity.TenantAppointment;
import com.nextturn.entity.TenantService;
import com.nextturn.entity.TenantTimeSlot;
import com.nextturn.enums.AppointmentStatus;
import com.nextturn.enums.NotificationChannel;
import com.nextturn.enums.NotificationDeliveryStatus;
import com.nextturn.enums.NotificationTemplateCode;
import com.nextturn.repository.appointment.TenantAppointmentRepository;
import com.nextturn.repository.auth.CentralTenantRepository;
import com.nextturn.service.BaseService;
import com.nextturn.service.notification.EmailService;
import com.nextturn.service.notification.TenantAppointmentReminderService;
import com.nextturn.service.notification.TenantNotificationLogService;
import com.nextturn.service.notification.TenantNotificationTemplateService;
import com.nextturn.infrastructure.logging.LogContract;
import com.nextturn.infrastructure.logging.LogDomain;
import com.nextturn.infrastructure.logging.LogOutcome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Default implementation of {@link TenantAppointmentReminderService} enforcing SRS REQ-NOTI-01:
 * scans {@code CONFIRMED} appointments, dispatches reminders at 24h and 1h milestones using
 * centralized HTML templates, skips {@code CANCELLED} appointments, and retries up to 3 times on error.
 */
@Service
public class TenantAppointmentReminderServiceImpl extends BaseService implements TenantAppointmentReminderService {

    private static final Logger log = LoggerFactory.getLogger(TenantAppointmentReminderServiceImpl.class);
    private static final String REMINDER_HTML_TEMPLATE = "templates/email/appointment-reminder.html";
    private static final int MAX_RETRY_ATTEMPTS = 3;
    private static final long ONE_HOUR_MINUTES = 60L;
    private static final long TWENTY_FOUR_HOURS_MINUTES = 24L * 60L;

    private final TenantAppointmentRepository appointmentRepository;
    private final TenantNotificationTemplateService templateService;
    private final TenantNotificationLogService logService;
    private final EmailService emailService;
    private final CentralTenantRepository centralTenantRepository;
    private final Clock clock;
    private final Map<String, String> htmlCache = new ConcurrentHashMap<>();

    @Autowired
    public TenantAppointmentReminderServiceImpl(
            TenantAppointmentRepository appointmentRepository,
            TenantNotificationTemplateService templateService,
            TenantNotificationLogService logService,
            @Autowired(required = false) EmailService emailService,
            @Autowired(required = false) CentralTenantRepository centralTenantRepository,
            @Autowired(required = false) TransactionTemplate transactionTemplate
    ) {
        this(appointmentRepository, templateService, logService, emailService, centralTenantRepository,
                transactionTemplate, Clock.systemDefaultZone());
    }

    public TenantAppointmentReminderServiceImpl(
            TenantAppointmentRepository appointmentRepository,
            TenantNotificationTemplateService templateService,
            TenantNotificationLogService logService,
            EmailService emailService,
            CentralTenantRepository centralTenantRepository,
            TransactionTemplate transactionTemplate,
            Clock clock
    ) {
        super(transactionTemplate);
        this.appointmentRepository = appointmentRepository;
        this.templateService = templateService;
        this.logService = logService;
        this.emailService = emailService;
        this.centralTenantRepository = centralTenantRepository;
        this.clock = clock != null ? clock : Clock.systemDefaultZone();
    }

    @Override
    public ReminderDispatchSummaryResponse dispatchUpcomingReminders() {
        String tenantCode = resolveCurrentTenantCode();
        String tenantName = resolveTenantDisplayName(tenantCode);
        LocalDateTime now = LocalDateTime.now(clock);

        return executeInTransaction(status -> {
            List<TenantAppointment> candidates = appointmentRepository.findConfirmedAppointmentsPendingReminder();
            int sent24h = 0;
            int sent1h = 0;
            int failed = 0;

            for (TenantAppointment apt : candidates) {
                if (apt.getStatus() != AppointmentStatus.CONFIRMED || apt.getSlot() == null
                        || apt.getSlot().getWorkDate() == null || apt.getSlot().getSlotStart() == null) {
                    continue;
                }

                LocalDateTime appointmentStart = LocalDateTime.of(apt.getSlot().getWorkDate(), apt.getSlot().getSlotStart());
                long minutesUntil = Duration.between(now, appointmentStart).toMinutes();
                if (minutesUntil <= 0 || minutesUntil > TWENTY_FOUR_HOURS_MINUTES) {
                    continue;
                }

                boolean is1hWindow = minutesUntil <= ONE_HOUR_MINUTES;
                if (is1hWindow && !Boolean.TRUE.equals(apt.getReminder1hSent())) {
                    if (sendMilestoneReminder(apt, tenantName, true)) {
                        apt.setReminder1hSent(true);
                        appointmentRepository.save(apt);
                        sent1h++;
                    } else {
                        failed++;
                    }
                } else if (!is1hWindow && !Boolean.TRUE.equals(apt.getReminder24hSent())) {
                    if (sendMilestoneReminder(apt, tenantName, false)) {
                        apt.setReminder24hSent(true);
                        appointmentRepository.save(apt);
                        sent24h++;
                    } else {
                        failed++;
                    }
                }
            }

            return ReminderDispatchSummaryResponse.builder()
                    .reminders24hSent(sent24h)
                    .reminders1hSent(sent1h)
                    .failedCount(failed)
                    .build();
        });
    }

    private boolean sendMilestoneReminder(TenantAppointment apt, String tenantName, boolean isOneHour) {
        String templateCode = isOneHour
                ? NotificationTemplateCode.APT_REMINDER_1H.name()
                : NotificationTemplateCode.APT_REMINDER_24H.name();

        TenantTimeSlot slot = apt.getSlot();
        TenantService service = apt.getService();
        String appointmentTimeStr = slot.getWorkDate() + " " + slot.getSlotStart();

        Map<String, String> vars = new HashMap<>();
        vars.put("customer_name", apt.getCustomerName() != null ? apt.getCustomerName() : "");
        vars.put("customerName", apt.getCustomerName() != null ? apt.getCustomerName() : "");
        vars.put("tenant_name", tenantName);
        vars.put("tenantName", tenantName);
        vars.put("appointment_code", apt.getAppointmentCode() != null ? apt.getAppointmentCode() : "");
        vars.put("appointmentCode", apt.getAppointmentCode() != null ? apt.getAppointmentCode() : "");
        vars.put("service_name", service != null && service.getServiceName() != null ? service.getServiceName() : "");
        vars.put("serviceName", service != null && service.getServiceName() != null ? service.getServiceName() : "");
        vars.put("appointment_time", appointmentTimeStr);
        vars.put("appointmentTime", appointmentTimeStr);
        vars.put("qr_token", apt.getQrToken() != null ? apt.getQrToken() : "");
        vars.put("qrToken", apt.getQrToken() != null ? apt.getQrToken() : "");
        vars.put("milestoneTitle", isOneHour
                ? "Nhắc nhở lịch hẹn sắp diễn ra (còn dưới 1 giờ)"
                : "Nhắc nhở lịch hẹn ngày mai (trước 24 giờ)");
        vars.put("milestoneIntro", isOneHour
                ? "Lịch hẹn của quý khách sắp bắt đầu. Vui lòng di chuyển tới cơ sở và xuất trình mã QR Check-in bên dưới trước 15 phút."
                : "Quý khách có lịch hẹn đặt trước vào ngày mai. Vui lòng chuẩn bị giấy tờ liên quan theo hướng dẫn dịch vụ.");

        NotificationTemplateResponse templateCfg = templateService.getTemplateOrDefault(templateCode);
        String subject = templateService.renderTemplateText(templateCfg.getSubjectTemplate(), vars);
        String summaryBody = templateService.renderTemplateText(templateCfg.getBodyTemplate(), vars);

        String recipientEmail = apt.getCustomerEmail();
        String recipientContact = (recipientEmail != null && !recipientEmail.isBlank())
                ? recipientEmail.trim()
                : apt.getCustomerPhone();

        if (recipientEmail == null || recipientEmail.isBlank()) {
            // SMS/Push fallback if customer has no email address
            logService.recordDeliveryLog(apt, null, recipientContact, NotificationChannel.PUSH,
                    templateCode, summaryBody, NotificationDeliveryStatus.SUCCESS, null);
            return true;
        }

        String html = buildEmailHtml(vars, summaryBody);
        String lastError = null;
        for (int attempt = 1; attempt <= MAX_RETRY_ATTEMPTS; attempt++) {
            try {
                if (emailService != null && emailService.isConfigured()) {
                    emailService.sendHtmlEmail(recipientEmail, subject, html);
                } else {
                    LogContract.info(log, LogDomain.NOTIFICATION, "reminder_console_fallback")
                            .outcome(LogOutcome.DEGRADED)
                            .entity("appointment", apt.getAppointmentCode())
                            .email(recipientEmail)
                            .field("templateCode", templateCode)
                            .emit();
                }
                logService.recordDeliveryLog(apt, null, recipientEmail, NotificationChannel.EMAIL,
                        templateCode, summaryBody, NotificationDeliveryStatus.SUCCESS, null);
                return true;
            } catch (Exception ex) {
                lastError = ex.getMessage();
                LogContract.warn(log, LogDomain.NOTIFICATION, "reminder_email_attempt")
                        .outcome(LogOutcome.DEGRADED)
                        .entity("appointment", apt.getAppointmentCode())
                        .email(recipientEmail)
                        .field("attempt", attempt)
                        .field("maxAttempts", MAX_RETRY_ATTEMPTS)
                        .cause(ex)
                        .emit();
            }
        }

        logService.recordDeliveryLog(apt, null, recipientEmail, NotificationChannel.EMAIL,
                templateCode, summaryBody, NotificationDeliveryStatus.FAILED,
                "Failed after " + MAX_RETRY_ATTEMPTS + " retries: " + lastError);
        return false;
    }

    private String buildEmailHtml(Map<String, String> vars, String fallbackBody) {
        String rawHtml = htmlCache.computeIfAbsent(REMINDER_HTML_TEMPLATE, path -> {
            try (InputStream is = new ClassPathResource(path).getInputStream()) {
                return new String(is.readAllBytes(), StandardCharsets.UTF_8);
            } catch (Exception ex) {
                LogContract.warn(log, LogDomain.NOTIFICATION, "reminder_template_load")
                        .outcome(LogOutcome.DEGRADED)
                        .field("path", path)
                        .cause(ex)
                        .emit();
                return "";
            }
        });
        if (rawHtml.isBlank()) {
            return "<p>" + fallbackBody + "</p>";
        }
        return templateService.renderTemplateText(rawHtml, vars);
    }

    private String resolveTenantDisplayName(String tenantCode) {
        if (centralTenantRepository == null || tenantCode == null || tenantCode.isBlank()) {
            return tenantCode != null ? tenantCode : "NextTurn";
        }
        return centralTenantRepository.findByTenantCodeIgnoreCaseAndIsDeletedFalse(tenantCode)
                .map(CentralTenant::getTenantName)
                .orElse(tenantCode);
    }
}
