package com.nextturn.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * Entity mapping to {@code tenant__feedbacks} in the tenant-isolated schema ({@code tenant_{tenantCode}}).
 * Captures post-service CSAT star ratings (1-5), quick criteria tags, and sanitized customer comments
 * (REQ-FEEDBACK-01 through REQ-FEEDBACK-04).
 */
@Entity
@Table(
        name = "tenant__feedbacks",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_tenant__feedbacks_ticket", columnNames = "ticket_id"),
                @UniqueConstraint(name = "uk_tenant__feedbacks_token", columnNames = "feedback_token")
        },
        indexes = {
                @Index(name = "idx_tenant__feedbacks_provider_score", columnList = "provider_id, rating_score"),
                @Index(name = "idx_tenant__feedbacks_created_at", columnList = "created_at, rating_score")
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TenantFeedback {

    @Id
    @Column(name = "id", length = 36, nullable = false, updatable = false)
    private String id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "ticket_id", nullable = false, unique = true)
    private TenantQueueTicket ticket;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "service_id", nullable = false)
    private TenantService service;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "provider_id")
    private TenantStaff provider;

    @Column(name = "rating_score", nullable = false)
    private Integer ratingScore;

    @Column(name = "criteria_ratings", columnDefinition = "JSON")
    private String criteriaRatings;

    @Column(name = "comment", columnDefinition = "TEXT")
    private String comment;

    @Column(name = "feedback_token", length = 64, nullable = false, unique = true)
    private String feedbackToken;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
