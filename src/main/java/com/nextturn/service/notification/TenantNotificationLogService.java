package com.nextturn.service.notification;

import com.nextturn.dto.notification.NotificationLogResponse;
import com.nextturn.entity.TenantAppointment;
import com.nextturn.entity.TenantQueueTicket;
import com.nextturn.enums.NotificationChannel;
import com.nextturn.enums.NotificationDeliveryStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * Contract for persisting and querying multi-channel notification delivery audit logs (REQ-NOTI-04).
 */
public interface TenantNotificationLogService {

    void recordDeliveryLog(
            TenantAppointment appointment,
            TenantQueueTicket ticket,
            String recipientContact,
            NotificationChannel channel,
            String templateCode,
            String messagePayload,
            NotificationDeliveryStatus status,
            String errorMessage
    );

    Page<NotificationLogResponse> searchLogs(
            String recipient,
            String ticketCode,
            NotificationChannel channel,
            NotificationDeliveryStatus status,
            String templateCode,
            Pageable pageable
    );
}
