package com.nextturn.service.notification.impl;

import com.nextturn.dto.notification.NotificationTemplateResponse;
import com.nextturn.entity.TenantQueueTicket;
import com.nextturn.entity.TenantService;
import com.nextturn.entity.TenantStaff;
import com.nextturn.enums.NotificationChannel;
import com.nextturn.enums.NotificationDeliveryStatus;
import com.nextturn.enums.NotificationTemplateCode;
import com.nextturn.repository.ticket.TenantQueueTicketRepository;
import com.nextturn.service.notification.TenantNotificationLogService;
import com.nextturn.service.notification.TenantNotificationTemplateService;
import com.nextturn.service.notification.TenantQueueAlertService;
import com.nextturn.infrastructure.logging.LogContract;
import com.nextturn.infrastructure.logging.LogDomain;
import com.nextturn.infrastructure.logging.LogOutcome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Default implementation of {@link TenantQueueAlertService} implementing:
 * <ul>
 *     <li>REQ-NOTI-02: Approaching-turn alert when {@code peopleAheadCount <= 2} or {@code estimatedWaitMinutes < 5},
 *         idempotently guarded by {@code approaching_alert_sent = TRUE}</li>
 *     <li>REQ-NOTI-03: High-priority counter call alert when a ticket enters {@code CALLING}</li>
 * </ul>
 */
@Service
public class TenantQueueAlertServiceImpl implements TenantQueueAlertService {

    private static final Logger log = LoggerFactory.getLogger(TenantQueueAlertServiceImpl.class);
    private static final long APPROACHING_PEOPLE_THRESHOLD = 2L;
    private static final long APPROACHING_WAIT_MINUTES_THRESHOLD = 5L;

    private final TenantQueueTicketRepository ticketRepository;
    private final TenantNotificationTemplateService templateService;
    private final TenantNotificationLogService logService;

    @Autowired
    public TenantQueueAlertServiceImpl(
            TenantQueueTicketRepository ticketRepository,
            TenantNotificationTemplateService templateService,
            TenantNotificationLogService logService
    ) {
        this.ticketRepository = ticketRepository;
        this.templateService = templateService;
        this.logService = logService;
    }

    @Override
    @Transactional
    public int processWaitingTurnAlerts(String serviceId, LocalDate workDate) {
        if (ticketRepository == null || serviceId == null || workDate == null) {
            return 0;
        }
        List<TenantQueueTicket> waitingList =
                ticketRepository.findWaitingByServiceAndWorkDateWithService(serviceId, workDate);
        if (waitingList == null || waitingList.isEmpty()) {
            return 0;
        }

        int triggeredCount = 0;
        for (int i = 0; i < waitingList.size(); i++) {
            TenantQueueTicket ticket = waitingList.get(i);
            if (Boolean.TRUE.equals(ticket.getApproachingAlertSent())) {
                continue;
            }
            long peopleAhead = i;
            TenantService service = ticket.getService();
            int duration = (service != null && service.getStandardDuration() != null && service.getStandardDuration() > 0)
                    ? service.getStandardDuration()
                    : 15;
            long estimatedWait = peopleAhead * duration;

            if (peopleAhead <= APPROACHING_PEOPLE_THRESHOLD || estimatedWait < APPROACHING_WAIT_MINUTES_THRESHOLD) {
                ticket.setApproachingAlertSent(true);
                ticketRepository.save(ticket);

                String message = renderApproachingAlertMessage(ticket, peopleAhead);
                String recipient = resolveTicketContact(ticket);
                logService.recordDeliveryLog(
                        ticket.getAppointment(),
                        ticket,
                        recipient,
                        NotificationChannel.PUSH,
                        NotificationTemplateCode.APPROACHING_TURN_ALERT.name(),
                        message,
                        NotificationDeliveryStatus.SUCCESS,
                        null
                );
                LogContract.info(log, LogDomain.NOTIFICATION, "approaching_turn_alert")
                        .outcome(LogOutcome.SUCCESS)
                        .entity("ticket", ticket.getTicketCode())
                        .field("peopleAhead", peopleAhead)
                        .emit();
                triggeredCount++;
            }
        }
        return triggeredCount;
    }

    @Override
    @Transactional
    public void dispatchCounterCallNotification(TenantQueueTicket calledTicket) {
        if (calledTicket == null || logService == null) {
            return;
        }
        String deskLabel = resolveCounterDeskLabel(calledTicket);
        TenantStaff staff = calledTicket.getCounterStaff();
        TenantService service = calledTicket.getService();

        Map<String, String> vars = new HashMap<>();
        vars.put("ticket_code", calledTicket.getTicketCode() != null ? calledTicket.getTicketCode() : "");
        vars.put("counter_label", deskLabel);
        vars.put("counter_staff_name", staff != null && staff.getFullName() != null ? staff.getFullName() : "Nhân viên quầy");
        vars.put("customer_name", calledTicket.getCustomerName() != null ? calledTicket.getCustomerName() : "Quý khách");
        vars.put("service_name", service != null && service.getServiceName() != null ? service.getServiceName() : "");

        NotificationTemplateResponse cfg =
                templateService.getTemplateOrDefault(NotificationTemplateCode.TICKET_CALLED_ALERT.name());
        String rendered = templateService.renderTemplateText(cfg.getBodyTemplate(), vars);

        logService.recordDeliveryLog(
                calledTicket.getAppointment(),
                calledTicket,
                resolveTicketContact(calledTicket),
                NotificationChannel.PUSH,
                NotificationTemplateCode.TICKET_CALLED_ALERT.name(),
                rendered,
                NotificationDeliveryStatus.SUCCESS,
                null
        );
        LogContract.debug(log, LogDomain.NOTIFICATION, "counter_call_alert")
                .outcome(LogOutcome.SUCCESS)
                .entity("ticket", calledTicket.getTicketCode())
                .field("counter", deskLabel)
                .emit();
    }

    private String renderApproachingAlertMessage(TenantQueueTicket ticket, long peopleAhead) {
        TenantService service = ticket.getService();
        Map<String, String> vars = new HashMap<>();
        vars.put("ticket_code", ticket.getTicketCode() != null ? ticket.getTicketCode() : "");
        vars.put("people_ahead", String.valueOf(peopleAhead));
        vars.put("customer_name", ticket.getCustomerName() != null ? ticket.getCustomerName() : "Quý khách");
        vars.put("service_name", service != null && service.getServiceName() != null ? service.getServiceName() : "");

        NotificationTemplateResponse cfg =
                templateService.getTemplateOrDefault(NotificationTemplateCode.APPROACHING_TURN_ALERT.name());
        return templateService.renderTemplateText(cfg.getBodyTemplate(), vars);
    }

    private String resolveCounterDeskLabel(TenantQueueTicket ticket) {
        if (ticket.getCounterLabel() != null && !ticket.getCounterLabel().isBlank()) {
            return ticket.getCounterLabel().trim();
        }
        return ticket.getCounterStaff() != null && ticket.getCounterStaff().getFullName() != null
                ? ticket.getCounterStaff().getFullName()
                : "Quầy tiếp nhận";
    }

    private String resolveTicketContact(TenantQueueTicket ticket) {
        if (ticket.getCustomerPhone() != null && !ticket.getCustomerPhone().isBlank()) {
            return ticket.getCustomerPhone().trim();
        }
        return ticket.getTicketCode() != null ? ticket.getTicketCode() : ticket.getId();
    }
}
