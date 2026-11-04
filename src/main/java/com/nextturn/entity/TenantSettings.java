package com.nextturn.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * Entity mapping to {@code tenant__tenant_settings} in the tenant-isolated schema ({@code tenant_{tenantCode}}).
 * Represents facility-level operational parameters for customer advance booking, lead times, and cancellations.
 */
@Entity
@Table(name = "tenant__tenant_settings")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TenantSettings {

    @Id
    @Column(name = "id", length = 36, nullable = false, updatable = false)
    private String id;

    @Column(name = "min_lead_time_hours", nullable = false)
    @Builder.Default
    private Integer minLeadTimeHours = 2;

    @Column(name = "max_advance_booking_days", nullable = false)
    @Builder.Default
    private Integer maxAdvanceBookingDays = 30;

    @Column(name = "cancellation_deadline_hours", nullable = false)
    @Builder.Default
    private Integer cancellationDeadlineHours = 12;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}

