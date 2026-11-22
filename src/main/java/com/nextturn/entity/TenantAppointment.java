package com.nextturn.entity;

import com.nextturn.enums.AppointmentStatus;
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
 * Entity mapping to {@code tenant__appointments} table in the tenant-isolated schema ({@code tenant_{tenantCode}}).
 * Represents customer online pre-bookings (REQ-APT-03, REQ-APT-04, REQ-APT-05).
 */
@Entity
@Table(
        name = "tenant__appointments",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_tenant__appointments_code", columnNames = "appointment_code"),
                @UniqueConstraint(name = "uk_tenant__appointments_qr", columnNames = "qr_token")
        },
        indexes = {
                @Index(name = "idx_tenant__apt_user", columnList = "user_id"),
                @Index(name = "idx_tenant__apt_phone", columnList = "customer_phone"),
                @Index(name = "idx_tenant__apt_status", columnList = "status, created_at")
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TenantAppointment {

    @Id
    @Column(name = "id", length = 36, nullable = false, updatable = false)
    private String id;

    @Column(name = "appointment_code", length = 30, nullable = false, unique = true)
    private String appointmentCode;

    @Column(name = "user_id", length = 36)
    private String userId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "service_id", nullable = false)
    private TenantService service;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "slot_id", nullable = false)
    private TenantTimeSlot slot;

    @Column(name = "customer_name", length = 150, nullable = false)
    private String customerName;

    @Column(name = "customer_phone", length = 20, nullable = false)
    private String customerPhone;

    @Column(name = "customer_email", length = 255)
    private String customerEmail;

    @Column(name = "notes", length = 255)
    private String notes;

    @Column(name = "cancellation_reason", length = 500)
    private String cancellationReason;

    @Column(name = "qr_token", length = 128, nullable = false, unique = true)
    private String qrToken;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 30, nullable = false)
    @Builder.Default
    private AppointmentStatus status = AppointmentStatus.CONFIRMED;

    @Column(name = "reminder_24h_sent", nullable = false)
    @Builder.Default
    private Boolean reminder24hSent = false;

    @Column(name = "reminder_1h_sent", nullable = false)
    @Builder.Default
    private Boolean reminder1hSent = false;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
