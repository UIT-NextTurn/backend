package com.nextturn.dto.notification;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.nextturn.entity.TenantAppointment;
import com.nextturn.entity.TenantNotificationLog;
import com.nextturn.entity.TenantQueueTicket;
import com.nextturn.enums.NotificationChannel;
import com.nextturn.enums.NotificationDeliveryStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Response DTO representing an audit entry in {@code tenant__notification_logs} (REQ-NOTI-04).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "Notification delivery audit log record")
public class NotificationLogResponse {

    private String id;
    private String appointmentId;
    private String appointmentCode;
    private String ticketId;
    private String ticketCode;
    private String recipientContact;
    private NotificationChannel channel;
    private String templateCode;
    private String messagePayload;
    private NotificationDeliveryStatus status;
    private String errorMessage;
    private LocalDateTime sentAt;

    public static NotificationLogResponse fromEntity(TenantNotificationLog logEntry) {
        if (logEntry == null) {
            return null;
        }
        TenantAppointment apt = logEntry.getAppointment();
        TenantQueueTicket ticket = logEntry.getTicket();
        return NotificationLogResponse.builder()
                .id(logEntry.getId())
                .appointmentId(apt != null ? apt.getId() : null)
                .appointmentCode(apt != null ? apt.getAppointmentCode() : null)
                .ticketId(ticket != null ? ticket.getId() : null)
                .ticketCode(ticket != null ? ticket.getTicketCode() : null)
                .recipientContact(logEntry.getRecipientContact())
                .channel(logEntry.getChannel())
                .templateCode(logEntry.getTemplateCode())
                .messagePayload(logEntry.getMessagePayload())
                .status(logEntry.getStatus())
                .errorMessage(logEntry.getErrorMessage())
                .sentAt(logEntry.getSentAt())
                .build();
    }
}
