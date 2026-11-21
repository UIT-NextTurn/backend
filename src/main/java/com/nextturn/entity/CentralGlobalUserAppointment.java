package com.nextturn.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * Entity mapping to {@code central__global_user_appointments} table in {@code nextturn_master}.
 * High-speed cross-tenant appointment index for logged-in customer appointment history (REQ-APT-05).
 */
@Entity
@Table(
        name = "central__global_user_appointments",
        catalog = "nextturn_master",
        indexes = {
                @Index(name = "idx_central__gua_user_created", columnList = "user_id, created_at"),
                @Index(name = "idx_central__gua_appointment", columnList = "tenant_code, appointment_code")
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CentralGlobalUserAppointment {

    @Id
    @Column(name = "id", length = 36, nullable = false, updatable = false)
    private String id;

    @Column(name = "user_id", length = 36, nullable = false)
    private String userId;

    @Column(name = "tenant_id", length = 36, nullable = false)
    private String tenantId;

    @Column(name = "appointment_id", length = 36, nullable = false)
    private String appointmentId;

    @Column(name = "tenant_code", length = 50, nullable = false)
    private String tenantCode;

    @Column(name = "appointment_code", length = 50, nullable = false)
    private String appointmentCode;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
