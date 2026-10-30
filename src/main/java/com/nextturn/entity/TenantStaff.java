package com.nextturn.entity;

import com.nextturn.enums.StaffRole;
import com.nextturn.enums.StaffStatus;
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
 * Entity mapping to {@code tenant__staffs} table in the tenant-isolated schema ({@code tenant__{tenantCode}}).
 * Represents internal healthcare staff, counter operators, and clinic administrators.
 */
@Entity
@Table(
        name = "tenant__staffs",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_tenant__staffs_staff_code", columnNames = "staff_code"),
                @UniqueConstraint(name = "uk_tenant__staffs_email", columnNames = "email"),
                @UniqueConstraint(name = "uk_tenant__staffs_phone_number", columnNames = "phone_number")
        },
        indexes = {
                @Index(name = "idx_tenant__staffs_role_status", columnList = "role, status, is_deleted")
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TenantStaff {

    @Id
    @Column(name = "id", length = 36, nullable = false, updatable = false)
    private String id;

    @Column(name = "staff_code", length = 30, nullable = false, unique = true)
    private String staffCode;

    @Column(name = "keycloak_user_id", length = 64)
    private String keycloakUserId;

    @Column(name = "full_name", length = 150, nullable = false)
    private String fullName;

    @Column(name = "email", length = 255, nullable = false, unique = true)
    private String email;

    @Column(name = "phone_number", length = 20, nullable = false, unique = true)
    private String phoneNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", length = 30, nullable = false)
    private StaffRole role;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20, nullable = false)
    @Builder.Default
    private StaffStatus status = StaffStatus.ACTIVE;

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
