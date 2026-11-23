package com.nextturn.entity;

import com.nextturn.enums.TicketCustomerType;
import com.nextturn.enums.TicketStatus;
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
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Entity mapping to {@code tenant__queue_tickets} in the tenant-isolated schema ({@code tenant_{tenantCode}}).
 * Represents uniquely sequenced electronic queue tickets (REQ-TICKET-01 to REQ-TICKET-04).
 */
@Entity
@Table(
        name = "tenant__queue_tickets",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_tenant__tickets_date_code", columnNames = {"work_date", "ticket_code"})
        },
        indexes = {
                @Index(name = "idx_tenant__ticket_live_queue", columnList = "work_date, service_id, status"),
                @Index(name = "idx_tenant__ticket_called", columnList = "counter_staff_id, status"),
                @Index(name = "idx_tenant__ticket_provider", columnList = "provider_id, status"),
                @Index(name = "idx_tenant__ticket_phone_date", columnList = "work_date, customer_phone, status"),
                @Index(name = "idx_tenant__ticket_service_seq", columnList = "service_id, work_date, sequence_number")
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TenantQueueTicket {

    @Id
    @Column(name = "id", length = 36, nullable = false, updatable = false)
    private String id;

    @Column(name = "ticket_code", length = 20, nullable = false)
    private String ticketCode;

    @Column(name = "sequence_number", nullable = false)
    private Integer sequenceNumber;

    @Column(name = "work_date", nullable = false)
    private LocalDate workDate;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "service_id", nullable = false)
    private TenantService service;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "appointment_id")
    private TenantAppointment appointment;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reception_point_id")
    private TenantReceptionPoint receptionPoint;

    @Column(name = "customer_name", length = 150)
    private String customerName;

    @Column(name = "customer_phone", length = 20)
    private String customerPhone;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "counter_staff_id")
    private TenantStaff counterStaff;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "provider_id")
    private TenantStaff provider;

    @Column(name = "counter_label", length = 50)
    private String counterLabel;

    @Enumerated(EnumType.STRING)
    @Column(name = "customer_type", length = 20, nullable = false)
    @Builder.Default
    private TicketCustomerType customerType = TicketCustomerType.WALK_IN;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20, nullable = false)
    @Builder.Default
    private TicketStatus status = TicketStatus.WAITING;

    @Column(name = "recall_count", nullable = false)
    @Builder.Default
    private Integer recallCount = 0;

    @Version
    @Column(name = "version", nullable = false)
    @Builder.Default
    private Integer version = 0;

    @Column(name = "auto_completed", nullable = false)
    @Builder.Default
    private Boolean autoCompleted = false;

    @Column(name = "approaching_alert_sent", nullable = false)
    @Builder.Default
    private Boolean approachingAlertSent = false;

    @Column(name = "feedback_token", length = 64, unique = true)
    private String feedbackToken;

    @Column(name = "feedback_token_expires_at")
    private LocalDateTime feedbackTokenExpiresAt;

    @Column(name = "issued_at", nullable = false)
    private LocalDateTime issuedAt;

    @Column(name = "called_at")
    private LocalDateTime calledAt;

    @Column(name = "served_at")
    private LocalDateTime servedAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @Column(name = "actual_duration")
    private Integer actualDuration;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
