package com.nextturn.entity;

import com.nextturn.enums.NotificationChannel;
import com.nextturn.enums.NotificationDeliveryStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * Entity mapping to {@code tenant__notification_logs} recording multi-channel dispatch outcomes
 * (Email / Push / SMS) and retry errors for admin audit & complaint resolution (REQ-NOTI-01, REQ-NOTI-04).
 */
@Entity
@Table(
        name = "tenant__notification_logs",
        indexes = {
                @Index(name = "idx_tenant__notif_status_time", columnList = "status, sent_at"),
                @Index(name = "idx_tenant__notif_ticket", columnList = "ticket_id"),
                @Index(name = "idx_tenant__notif_recipient", columnList = "recipient_contact, sent_at"),
                @Index(name = "idx_tenant__notif_template", columnList = "template_code, sent_at")
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TenantNotificationLog {

    @Id
    @Column(name = "id", length = 36, nullable = false, updatable = false)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "appointment_id")
    private TenantAppointment appointment;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "ticket_id")
    private TenantQueueTicket ticket;

    @Column(name = "recipient_contact", length = 255, nullable = false)
    private String recipientContact;

    @Enumerated(EnumType.STRING)
    @Column(name = "channel", length = 20, nullable = false)
    private NotificationChannel channel;

    @Column(name = "template_code", length = 50, nullable = false)
    private String templateCode;

    @Column(name = "message_payload", columnDefinition = "TEXT", nullable = false)
    private String messagePayload;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20, nullable = false)
    @Builder.Default
    private NotificationDeliveryStatus status = NotificationDeliveryStatus.SUCCESS;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @CreationTimestamp
    @Column(name = "sent_at", nullable = false, updatable = false)
    private LocalDateTime sentAt;
}
