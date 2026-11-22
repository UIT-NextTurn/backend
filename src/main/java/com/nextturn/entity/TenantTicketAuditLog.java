package com.nextturn.entity;

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
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * Entity mapping to {@code tenant__ticket_audit_logs} for non-destructive audit trail
 * of ticket state transitions (Section 3.16).
 */
@Entity
@Table(
        name = "tenant__ticket_audit_logs",
        indexes = {
                @Index(name = "idx_tenant__ticket_audit_time", columnList = "ticket_id, created_at")
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TenantTicketAuditLog {

    @Id
    @Column(name = "id", length = 36, nullable = false, updatable = false)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "ticket_id", nullable = false)
    private TenantQueueTicket ticket;

    @Column(name = "actor_id", length = 100, nullable = false)
    private String actorId;

    @Enumerated(EnumType.STRING)
    @Column(name = "previous_state", length = 20, nullable = false)
    private TicketStatus previousState;

    @Enumerated(EnumType.STRING)
    @Column(name = "new_state", length = 20, nullable = false)
    private TicketStatus newState;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
