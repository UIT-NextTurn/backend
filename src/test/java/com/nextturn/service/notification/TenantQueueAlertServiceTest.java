package com.nextturn.service.notification;

import com.nextturn.dto.notification.NotificationTemplateResponse;
import com.nextturn.dto.notification.UpsertNotificationTemplateRequest;
import com.nextturn.entity.TenantNotificationTemplate;
import com.nextturn.entity.TenantQueueTicket;
import com.nextturn.entity.TenantService;
import com.nextturn.entity.TenantStaff;
import com.nextturn.enums.NotificationChannel;
import com.nextturn.enums.NotificationDeliveryStatus;
import com.nextturn.enums.NotificationTemplateCode;
import com.nextturn.enums.TicketStatus;
import com.nextturn.infrastructure.multitenancy.TenantContextHolder;
import com.nextturn.repository.notification.TenantNotificationTemplateRepository;
import com.nextturn.repository.ticket.TenantQueueTicketRepository;
import com.nextturn.service.notification.impl.TenantNotificationTemplateServiceImpl;
import com.nextturn.service.notification.impl.TenantQueueAlertServiceImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests covering {@link TenantQueueAlertServiceImpl} and
 * {@link TenantNotificationTemplateServiceImpl} (REQ-NOTI-02, REQ-NOTI-03, REQ-NOTI-04).
 */
@ExtendWith(MockitoExtension.class)
class TenantQueueAlertServiceTest {

    private static final String TENANT_CODE = "clinic-alpha";

    @Mock
    private TenantQueueTicketRepository ticketRepository;

    @Mock
    private TenantNotificationTemplateRepository templateRepository;

    @Mock
    private TenantNotificationLogService logService;

    private TenantNotificationTemplateServiceImpl templateService;
    private TenantQueueAlertServiceImpl queueAlertService;

    @BeforeEach
    void setUp() {
        TenantContextHolder.setTenantCode(TENANT_CODE);
        lenient().when(templateRepository.findByTemplateCodeIgnoreCase(anyString()))
                .thenReturn(Optional.empty());
        templateService = new TenantNotificationTemplateServiceImpl(templateRepository, null);
        queueAlertService = new TenantQueueAlertServiceImpl(ticketRepository, templateService, logService);
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
    }

    @Test
    @DisplayName("REQ-NOTI-02: Triggers approaching-turn push alert only once when peopleAhead <= 2")
    void processWaitingTurnAlerts_ThresholdAndIdempotency() {
        LocalDate workDate = LocalDate.of(2026, 11, 23);
        TenantService service = TenantService.builder()
                .id("srv-1")
                .serviceName("Khám nhi")
                .standardDuration(15)
                .build();

        TenantQueueTicket t0 = buildWaitingTicket("t0", "B-001", 1, service, workDate, false);
        TenantQueueTicket t1 = buildWaitingTicket("t1", "B-002", 2, service, workDate, true);  // already flagged
        TenantQueueTicket t2 = buildWaitingTicket("t2", "B-003", 3, service, workDate, false); // peopleAhead = 2
        TenantQueueTicket t3 = buildWaitingTicket("t3", "B-004", 4, service, workDate, false); // peopleAhead = 3, est=45m -> NOT triggered

        when(ticketRepository.findWaitingByServiceAndWorkDateWithService("srv-1", workDate))
                .thenReturn(List.of(t0, t1, t2, t3));

        int triggered = queueAlertService.processWaitingTurnAlerts("srv-1", workDate);

        assertThat(triggered).isEqualTo(2);
        assertThat(t0.getApproachingAlertSent()).isTrue();
        assertThat(t2.getApproachingAlertSent()).isTrue();
        assertThat(t3.getApproachingAlertSent()).isFalse();

        verify(ticketRepository, times(2)).save(any(TenantQueueTicket.class));
        verify(logService).recordDeliveryLog(
                isNull(), eq(t0), eq("0900000001"), eq(NotificationChannel.PUSH),
                eq(NotificationTemplateCode.APPROACHING_TURN_ALERT.name()), anyString(),
                eq(NotificationDeliveryStatus.SUCCESS), isNull()
        );
        verify(logService).recordDeliveryLog(
                isNull(), eq(t2), eq("0900000003"), eq(NotificationChannel.PUSH),
                eq(NotificationTemplateCode.APPROACHING_TURN_ALERT.name()), anyString(),
                eq(NotificationDeliveryStatus.SUCCESS), isNull()
        );
    }

    @Test
    @DisplayName("REQ-NOTI-03: Dispatches counter call alert substituting {ticket_code}, {counter_label}, {counter_staff_name}")
    void dispatchCounterCallNotification_RendersCounterAndStaffDetails() {
        TenantService service = TenantService.builder()
                .id("srv-1")
                .serviceName("Khám răng")
                .build();
        TenantStaff staff = TenantStaff.builder()
                .id("stf-1")
                .fullName("Trần Thị Hoa")
                .build();
        TenantQueueTicket called = TenantQueueTicket.builder()
                .id("tkt-call")
                .ticketCode("A-012")
                .sequenceNumber(12)
                .workDate(LocalDate.of(2026, 11, 23))
                .service(service)
                .customerName("Lê Minh Tuấn")
                .customerPhone("0912345678")
                .counterStaff(staff)
                .counterLabel("Quầy 03")
                .status(TicketStatus.CALLING)
                .issuedAt(LocalDateTime.now())
                .build();

        queueAlertService.dispatchCounterCallNotification(called);

        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        verify(logService).recordDeliveryLog(
                isNull(),
                eq(called),
                eq("0912345678"),
                eq(NotificationChannel.PUSH),
                eq(NotificationTemplateCode.TICKET_CALLED_ALERT.name()),
                payloadCaptor.capture(),
                eq(NotificationDeliveryStatus.SUCCESS),
                isNull()
        );

        assertThat(payloadCaptor.getValue())
                .contains("A-012")
                .contains("Quầy 03")
                .contains("Trần Thị Hoa");
    }

    @Test
    @DisplayName("REQ-NOTI-04: Upserts customized notification template and substitutes dynamic placeholders")
    void upsertAndRenderCustomTemplate() {
        UpsertNotificationTemplateRequest request = UpsertNotificationTemplateRequest.builder()
                .templateName("Thông báo gọi số tùy biến")
                .channel(NotificationChannel.PUSH)
                .subjectTemplate("Quầy {counter_label} gọi {ticket_code}")
                .bodyTemplate("Kính mời {customer_name} mang vé {ticket_code} gặp {counter_staff_name} cho dịch vụ {service_name}.")
                .isActive(true)
                .build();

        when(templateRepository.save(any(TenantNotificationTemplate.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        NotificationTemplateResponse saved =
                templateService.upsertTemplate("ticket_called_alert", request);

        assertThat(saved.getTemplateCode()).isEqualTo("TICKET_CALLED_ALERT");
        assertThat(saved.getCustomized()).isTrue();

        String rendered = templateService.renderTemplateText(saved.getBodyTemplate(), Map.of(
                "customer_name", "Phạm Lan Anh",
                "ticket_code", "C-009",
                "counter_staff_name", "Nguyễn Văn Hùng",
                "service_name", "Siêu âm tim"
        ));
        assertThat(rendered)
                .isEqualTo("Kính mời Phạm Lan Anh mang vé C-009 gặp Nguyễn Văn Hùng cho dịch vụ Siêu âm tim.");
    }

    private TenantQueueTicket buildWaitingTicket(
            String id,
            String code,
            int seq,
            TenantService service,
            LocalDate workDate,
            boolean approachingSent
    ) {
        return TenantQueueTicket.builder()
                .id(id)
                .ticketCode(code)
                .sequenceNumber(seq)
                .workDate(workDate)
                .service(service)
                .customerName("Khách " + code)
                .customerPhone("090000000" + seq)
                .status(TicketStatus.WAITING)
                .approachingAlertSent(approachingSent)
                .issuedAt(LocalDateTime.now())
                .build();
    }
}
