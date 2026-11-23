package com.nextturn.service.notification.impl;

import com.nextturn.dto.notification.NotificationLogResponse;
import com.nextturn.entity.TenantAppointment;
import com.nextturn.entity.TenantNotificationLog;
import com.nextturn.entity.TenantQueueTicket;
import com.nextturn.enums.NotificationChannel;
import com.nextturn.enums.NotificationDeliveryStatus;
import com.nextturn.repository.notification.TenantNotificationLogRepository;
import com.nextturn.service.BaseService;
import com.nextturn.service.notification.TenantNotificationLogService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

/**
 * Default implementation of {@link TenantNotificationLogService} persisting immutable delivery logs
 * and supporting admin complaint queries by customer contact or ticket code (REQ-NOTI-04).
 */
@Service
public class TenantNotificationLogServiceImpl extends BaseService implements TenantNotificationLogService {

    private final TenantNotificationLogRepository logRepository;

    @Autowired
    public TenantNotificationLogServiceImpl(
            TenantNotificationLogRepository logRepository,
            @Autowired(required = false) TransactionTemplate transactionTemplate
    ) {
        super(transactionTemplate);
        this.logRepository = logRepository;
    }

    @Override
    @Transactional
    public void recordDeliveryLog(
            TenantAppointment appointment,
            TenantQueueTicket ticket,
            String recipientContact,
            NotificationChannel channel,
            String templateCode,
            String messagePayload,
            NotificationDeliveryStatus status,
            String errorMessage
    ) {
        if (logRepository == null) {
            return;
        }
        String safeRecipient = (recipientContact != null && !recipientContact.isBlank())
                ? recipientContact.trim()
                : "UNKNOWN";
        String safePayload = (messagePayload != null && !messagePayload.isBlank())
                ? messagePayload
                : templateCode;

        TenantNotificationLog entry = TenantNotificationLog.builder()
                .id(UUID.randomUUID().toString())
                .appointment(appointment)
                .ticket(ticket)
                .recipientContact(safeRecipient)
                .channel(channel != null ? channel : NotificationChannel.PUSH)
                .templateCode(templateCode != null ? templateCode : "GENERAL")
                .messagePayload(safePayload)
                .status(status != null ? status : NotificationDeliveryStatus.SUCCESS)
                .errorMessage(errorMessage)
                .build();
        logRepository.save(entry);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<NotificationLogResponse> searchLogs(
            String recipient,
            String ticketCode,
            NotificationChannel channel,
            NotificationDeliveryStatus status,
            String templateCode,
            Pageable pageable
    ) {
        resolveCurrentTenantCode();
        String cleanRecipient = recipient != null ? recipient.trim() : null;
        String cleanTicket = ticketCode != null ? ticketCode.trim() : null;
        String cleanTemplate = templateCode != null ? templateCode.trim() : null;
        return logRepository.searchLogs(cleanRecipient, cleanTicket, channel, status, cleanTemplate, pageable)
                .map(NotificationLogResponse::fromEntity);
    }
}
