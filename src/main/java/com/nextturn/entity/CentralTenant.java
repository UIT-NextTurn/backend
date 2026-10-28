package com.nextturn.entity;

import com.nextturn.enums.TenantStatus;
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

import java.time.LocalDateTime;

/**
 * Entity mapping to {@code central__tenants} table in the master database schema ({@code nextturn_master}).
 * Represents registered tenant facilities and their Keycloak organization linkage.
 */
@Entity
@Table(
        name = "central__tenants",
        catalog = "nextturn_master",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_central__tenants_tenant_code", columnNames = "tenant_code"),
                @UniqueConstraint(name = "uk_central__tenants_admin_email", columnNames = "admin_email"),
                @UniqueConstraint(name = "uk_central__tenants_organisation_id", columnNames = "organisation_id")
        },
        indexes = {
                @Index(name = "idx_central__tenants_status", columnList = "status, is_deleted")
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CentralTenant {

    @Id
    @Column(name = "id", length = 36, nullable = false, updatable = false)
    private String id;

    @Column(name = "tenant_code", length = 50, nullable = false, unique = true)
    private String tenantCode;

    @Column(name = "tenant_name", length = 255, nullable = false)
    private String tenantName;

    @Column(name = "admin_email", length = 255, nullable = false, unique = true)
    private String adminEmail;

    @Column(name = "phone_number", length = 20)
    private String phoneNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20, nullable = false)
    @Builder.Default
    private TenantStatus status = TenantStatus.ACTIVE;

    @Column(name = "registration_id", length = 36)
    private String registrationId;

    @Column(name = "is_deleted", nullable = false)
    @Builder.Default
    private Boolean isDeleted = false;

    @Column(name = "widget_api_key", length = 128)
    private String widgetApiKey;

    @Column(name = "widget_secret_key", length = 255)
    private String widgetSecretKey;

    @Column(name = "organisation_id", length = 36, unique = true)
    private String organisationId;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
