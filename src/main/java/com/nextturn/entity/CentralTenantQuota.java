package com.nextturn.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * Entity mapping to {@code central__tenant_quotas} table in the master database schema ({@code nextturn_master}).
 * Represents operational resource capacity limits per tenant facility (REQ-TNT-02).
 */
@Entity
@Table(
        name = "central__tenant_quotas",
        catalog = "nextturn_master",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_central__tenant_quotas_tenant_id", columnNames = "tenant_id")
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CentralTenantQuota {

    @Id
    @Column(name = "id", length = 36, nullable = false, updatable = false)
    private String id;

    @Column(name = "tenant_id", length = 36, nullable = false, unique = true)
    private String tenantId;

    @Column(name = "max_staffs", nullable = false)
    @Builder.Default
    private Integer maxStaffs = 5;

    @Column(name = "daily_ticket_quota", nullable = false)
    @Builder.Default
    private Integer dailyTicketQuota = 200;

    @Column(name = "max_services", nullable = false)
    @Builder.Default
    private Integer maxServices = 20;

    @Column(name = "max_appointments", nullable = false)
    @Builder.Default
    private Integer maxAppointments = 500;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}

