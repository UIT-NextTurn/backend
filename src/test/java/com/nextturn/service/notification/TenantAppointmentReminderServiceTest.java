package com.nextturn.service.notification;

import com.nextturn.dto.notification.ReminderDispatchSummaryResponse;
import com.nextturn.entity.CentralTenant;
import com.nextturn.entity.TenantAppointment;
import com.nextturn.entity.TenantService;
import com.nextturn.entity.TenantTimeSlot;
import com.nextturn.enums.AppointmentStatus;
import com.nextturn.enums.NotificationChannel;
import com.nextturn.enums.NotificationDeliveryStatus;
import com.nextturn.enums.NotificationTemplateCode;
import com.nextturn.infrastructure.multitenancy.TenantContextHolder;
import com.nextturn.repository.appointment.TenantAppointmentRepository;
import com.nextturn.repository.auth.CentralTenantRepository;
import com.nextturn.repository.notification.TenantNotificationTemplateRepository;
import com.nextturn.service.notification.impl.TenantAppointmentReminderServiceImpl;
import com.nextturn.service.notification.impl.TenantNotificationTemplateServiceImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link TenantAppointmentReminderServiceImpl} verifying SRS REQ-NOTI-01:
 * <ul>
 *     <li>24-hour and 1-hour milestone scans over {@code CONFIRMED} appointments</li>
 *     <li>Skipping {@code CANCELLED} appointments and preventing duplicate sends via flags</li>
 *     <li>3-attempt SMTP retry strategy with immutable audit log creation</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class TenantAppointmentReminderServiceTest {

    private static final String TENANT_CODE = "clinic-alpha";
    private static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    // Fixed now: 2026-11-23 09:00:00
    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2026-11-23T02:00:00Z"), ZONE);

    @Mock
    private TenantAppointmentRepository appointmentRepository;

    @Mock
    private TenantNotificationTemplateRepository templateRepository;

    @Mock
    private TenantNotificationLogService logService;

    @Mock
    private EmailService emailService;

    @Mock
    private CentralTenantRepository centralTenantRepository;

    private TenantAppointmentReminderServiceImpl reminderService;

    @BeforeEach
    void setUp() {
        TenantContextHolder.setTenantCode(TENANT_CODE);
        TenantNotificationTemplateService templateService =
                new TenantNotificationTemplateServiceImpl(templateRepository, null);
        reminderService = new TenantAppointmentReminderServiceImpl(
                appointmentRepository,
                templateService,
                logService,
                emailService,
                centralTenantRepository,
                null,
                FIXED_CLOCK
        );

        CentralTenant tenant = CentralTenant.builder()
                .tenantCode(TENANT_CODE)
                .tenantName("Phòng khám Alpha")
                .build();
        when(centralTenantRepository.findByTenantCodeIgnoreCaseAndIsDeletedFalse(TENANT_CODE))
                .thenReturn(Optional.of(tenant));
        when(templateRepository.findByTemplateCodeIgnoreCase(anyString()))
                .thenReturn(Optional.empty());
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
    }

    @Test
    @DisplayName("REQ-NOTI-01: Dispatches 24h and 1h reminders for CONFIRMED appointments and skips CANCELLED / already-sent")
    void dispatchUpcomingReminders_MilestonesAndExclusions() throws Exception {
        TenantService service = TenantService.builder()
                .id("srv-1")
                .serviceCode("KHAM-TQ")
                .serviceName("Khám tổng quát")
                .standardDuration(20)
                .build();

        // 23 hours ahead -> 24h milestone pending
        TenantAppointment apt24h = buildAppointment("apt-24h", "APT-101", AppointmentStatus.CONFIRMED,
                LocalDate.of(2026, 11, 24), LocalTime.of(8, 0), service, "an@example.com", "QR-24H", false, false);

        // 45 minutes ahead -> 1h milestone pending (contains QR + -15m notice)
        TenantAppointment apt1h = buildAppointment("apt-1h", "APT-102", AppointmentStatus.CONFIRMED,
                LocalDate.of(2026, 11, 23), LocalTime.of(9, 45), service, "binh@example.com", "QR-1H-XYZ", false, false);

        // CANCELLED appointment in 30m -> must NOT receive reminder
        TenantAppointment aptCancelled = buildAppointment("apt-canc", "APT-103", AppointmentStatus.CANCELLED,
                LocalDate.of(2026, 11, 23), LocalTime.of(9, 30), service, "cancel@example.com", "QR-CANC", false, false);

        // Already sent 24h milestone -> must NOT re-send
        TenantAppointment aptAlreadySent = buildAppointment("apt-sent", "APT-104", AppointmentStatus.CONFIRMED,
                LocalDate.of(2026, 11, 24), LocalTime.of(7, 0), service, "done@example.com", "QR-DONE", true, false);

        when(appointmentRepository.findConfirmedAppointmentsPendingReminder())
                .thenReturn(List.of(apt24h, apt1h, aptCancelled, aptAlreadySent));
        when(emailService.isConfigured()).thenReturn(true);
        doNothing().when(emailService).sendHtmlEmail(anyString(), anyString(), anyString());

        ReminderDispatchSummaryResponse summary = reminderService.dispatchUpcomingReminders();

        assertThat(summary.getReminders24hSent()).isEqualTo(1);
        assertThat(summary.getReminders1hSent()).isEqualTo(1);
        assertThat(summary.getFailedCount()).isZero();

        assertThat(apt24h.getReminder24hSent()).isTrue();
        assertThat(apt1h.getReminder1hSent()).isTrue();
        assertThat(aptCancelled.getReminder1hSent()).isFalse();

        // Verify 1h email body embeds QR token
        ArgumentCaptor<String> htmlCaptor = ArgumentCaptor.forClass(String.class);
        verify(emailService).sendHtmlEmail(eq("binh@example.com"), anyString(), htmlCaptor.capture());
        assertThat(htmlCaptor.getValue()).contains("QR-1H-XYZ");
        verify(emailService).sendHtmlEmail(eq("an@example.com"), anyString(), anyString());
        verify(emailService, never()).sendHtmlEmail(eq("cancel@example.com"), anyString(), anyString());
        verify(emailService, never()).sendHtmlEmail(eq("done@example.com"), anyString(), anyString());

        verify(logService).recordDeliveryLog(eq(apt24h), isNull(), eq("an@example.com"),
                eq(NotificationChannel.EMAIL), eq(NotificationTemplateCode.APT_REMINDER_24H.name()),
                anyString(), eq(NotificationDeliveryStatus.SUCCESS), isNull());
        verify(logService).recordDeliveryLog(eq(apt1h), isNull(), eq("binh@example.com"),
                eq(NotificationChannel.EMAIL), eq(NotificationTemplateCode.APT_REMINDER_1H.name()),
                anyString(), eq(NotificationDeliveryStatus.SUCCESS), isNull());
    }

    @Test
    @DisplayName("REQ-NOTI-01: Retries SMTP delivery up to 3 times before recording FAILED log")
    void dispatchUpcomingReminders_RetriesThreeTimesThenLogsFailure() throws Exception {
        TenantService service = TenantService.builder()
                .id("srv-1")
                .serviceName("Xét nghiệm máu")
                .standardDuration(15)
                .build();

        TenantAppointment apt = buildAppointment("apt-fail", "APT-201", AppointmentStatus.CONFIRMED,
                LocalDate.of(2026, 11, 24), LocalTime.of(8, 30), service, "fail@example.com", "QR-FAIL", false, false);

        when(appointmentRepository.findConfirmedAppointmentsPendingReminder()).thenReturn(List.of(apt));
        when(emailService.isConfigured()).thenReturn(true);
        doThrow(new RuntimeException("SMTP gateway timeout"))
                .when(emailService).sendHtmlEmail(eq("fail@example.com"), anyString(), anyString());

        ReminderDispatchSummaryResponse summary = reminderService.dispatchUpcomingReminders();

        assertThat(summary.getReminders24hSent()).isZero();
        assertThat(summary.getFailedCount()).isEqualTo(1);
        assertThat(apt.getReminder24hSent()).isFalse();

        verify(emailService, times(3)).sendHtmlEmail(eq("fail@example.com"), anyString(), anyString());
        verify(appointmentRepository, never()).save(apt);
        verify(logService).recordDeliveryLog(
                eq(apt),
                isNull(),
                eq("fail@example.com"),
                eq(NotificationChannel.EMAIL),
                eq(NotificationTemplateCode.APT_REMINDER_24H.name()),
                anyString(),
                eq(NotificationDeliveryStatus.FAILED),
                contains("Failed after 3 retries")
        );
    }

    @Test
    @DisplayName("REQ-NOTI-01: Marks reminder sent and logs SUCCESS when 3rd retry attempt recovers")
    void dispatchUpcomingReminders_RecoversOnThirdRetry() throws Exception {
        TenantService service = TenantService.builder()
                .id("srv-1")
                .serviceName("Nội soi")
                .standardDuration(30)
                .build();

        TenantAppointment apt = buildAppointment("apt-retry", "APT-301", AppointmentStatus.CONFIRMED,
                LocalDate.of(2026, 11, 23), LocalTime.of(9, 40), service, "retry@example.com", "QR-RETRY", false, false);

        when(appointmentRepository.findConfirmedAppointmentsPendingReminder()).thenReturn(List.of(apt));
        when(emailService.isConfigured()).thenReturn(true);
        doThrow(new RuntimeException("Transient 1"))
                .doThrow(new RuntimeException("Transient 2"))
                .doNothing()
                .when(emailService).sendHtmlEmail(eq("retry@example.com"), anyString(), anyString());

        ReminderDispatchSummaryResponse summary = reminderService.dispatchUpcomingReminders();

        assertThat(summary.getReminders1hSent()).isEqualTo(1);
        assertThat(summary.getFailedCount()).isZero();
        assertThat(apt.getReminder1hSent()).isTrue();
        verify(emailService, times(3)).sendHtmlEmail(eq("retry@example.com"), anyString(), anyString());
        verify(appointmentRepository).save(apt);
        verify(logService).recordDeliveryLog(
                eq(apt), isNull(), eq("retry@example.com"), eq(NotificationChannel.EMAIL),
                eq(NotificationTemplateCode.APT_REMINDER_1H.name()), anyString(),
                eq(NotificationDeliveryStatus.SUCCESS), isNull()
        );
    }

    private TenantAppointment buildAppointment(
            String id,
            String code,
            AppointmentStatus status,
            LocalDate workDate,
            LocalTime slotStart,
            TenantService service,
            String email,
            String qrToken,
            boolean r24Sent,
            boolean r1Sent
    ) {
        TenantTimeSlot slot = TenantTimeSlot.builder()
                .id("slot-" + id)
                .service(service)
                .workDate(workDate)
                .slotStart(slotStart)
                .slotEnd(slotStart.plusMinutes(20))
                .capacityPerSlot(1)
                .build();
        return TenantAppointment.builder()
                .id(id)
                .appointmentCode(code)
                .service(service)
                .slot(slot)
                .customerName("Nguyễn Văn A")
                .customerPhone("0909123456")
                .customerEmail(email)
                .qrToken(qrToken)
                .status(status)
                .reminder24hSent(r24Sent)
                .reminder1hSent(r1Sent)
                .build();
    }
}
