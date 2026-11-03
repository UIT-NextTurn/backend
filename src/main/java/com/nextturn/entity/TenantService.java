package com.nextturn.entity;

import com.nextturn.enums.ServiceStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Entity mapping to {@code tenant__services} in tenant-isolated schema ({@code tenant_{tenantCode}}).
 * Represents configurable services for virtual queuing and online appointment scheduling.
 */
@Entity
@Table(
        name = "tenant__services",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_tenant__services_service_code", columnNames = "service_code"),
                @UniqueConstraint(name = "uk_tenant__services_service_name", columnNames = "service_name")
        },
        indexes = {
                @Index(name = "idx_tenant__services_status", columnList = "status, is_deleted")
        }
)
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TenantService {

    @Id
    @Column(name = "id", length = 36, nullable = false, updatable = false)
    private String id;

    @Column(name = "service_code", length = 30, nullable = false, unique = true)
    private String serviceCode;

    @Column(name = "service_name", length = 150, nullable = false, unique = true)
    private String serviceName;

    @Column(name = "standard_duration", nullable = false)
    private Integer standardDuration;

    @Column(name = "price", precision = 12, scale = 2, nullable = false)
    private BigDecimal price;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20, nullable = false)
    private ServiceStatus status;

    @Builder.Default
    @Column(name = "is_deleted", nullable = false)
    private Boolean isDeleted = false;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
