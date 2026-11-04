package com.nextturn.entity;

import com.nextturn.enums.TimeSlotStatus;
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

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * Entity mapping to {@code tenant__time_slots} table in the tenant-isolated schema ({@code tenant_{tenantCode}}).
 * Represents sequential service capacity booking windows decomposed from staff work shifts.
 */
@Entity
@Table(
        name = "tenant__time_slots",
        indexes = {
                @Index(name = "idx_tenant__slots_query", columnList = "service_id, work_date, status"),
                @Index(name = "idx_tenant__slots_hold", columnList = "holding_token, holding_expires_at")
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TenantTimeSlot {

    @Id
    @Column(name = "id", length = 36, nullable = false, updatable = false)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "service_id", nullable = false)
    private TenantService service;

    @Column(name = "work_date", nullable = false)
    private LocalDate workDate;

    @Column(name = "slot_start", nullable = false)
    private LocalTime slotStart;

    @Column(name = "slot_end", nullable = false)
    private LocalTime slotEnd;

    @Column(name = "capacity_per_slot", nullable = false)
    @Builder.Default
    private Integer capacityPerSlot = 1;

    @Column(name = "booked_count", nullable = false)
    @Builder.Default
    private Integer bookedCount = 0;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20, nullable = false)
    @Builder.Default
    private TimeSlotStatus status = TimeSlotStatus.AVAILABLE;

    @Column(name = "holding_token", length = 64)
    private String holdingToken;

    @Column(name = "holding_expires_at")
    private LocalDateTime holdingExpiresAt;
}

