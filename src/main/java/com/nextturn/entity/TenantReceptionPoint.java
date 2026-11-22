package com.nextturn.entity;

import com.nextturn.enums.ReceptionPointStatus;
import com.nextturn.enums.ReceptionQrMode;
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
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * Entity mapping to {@code tenant__reception_points} table in the tenant-isolated schema.
 * Represents physical lobby/counter reception desks hosting static or 60s-rotating QR check-in codes (REQ-CHECK-IN-03).
 */
@Entity
@Table(
        name = "tenant__reception_points",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_tenant__reception_code", columnNames = "point_code"),
                @UniqueConstraint(name = "uk_tenant__reception_static_token", columnNames = "static_token")
        },
        indexes = {
                @Index(name = "idx_tenant__reception_status", columnList = "status, is_deleted"),
                @Index(name = "idx_tenant__reception_service", columnList = "service_id, status")
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TenantReceptionPoint {

    @Id
    @Column(name = "id", length = 36, nullable = false, updatable = false)
    private String id;

    @Column(name = "point_code", length = 30, nullable = false)
    private String pointCode;

    @Column(name = "point_name", length = 150, nullable = false)
    private String pointName;

    @Column(name = "location_label", length = 150)
    private String locationLabel;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "service_id")
    private TenantService service;

    @Enumerated(EnumType.STRING)
    @Column(name = "qr_mode", length = 20, nullable = false)
    @Builder.Default
    private ReceptionQrMode qrMode = ReceptionQrMode.DYNAMIC;

    @Column(name = "static_token", length = 128)
    private String staticToken;

    @Column(name = "qr_secret", length = 128, nullable = false)
    private String qrSecret;

    @Column(name = "rotation_seconds", nullable = false)
    @Builder.Default
    private Integer rotationSeconds = 60;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20, nullable = false)
    @Builder.Default
    private ReceptionPointStatus status = ReceptionPointStatus.ACTIVE;

    @Column(name = "is_deleted", nullable = false)
    @Builder.Default
    private Boolean isDeleted = false;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
