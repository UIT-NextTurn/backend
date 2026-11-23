package com.nextturn.dto.dashboard;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Real-time daily KPI overview card snapshot for Tenant Operations Dashboard (REQ-DASHBOARD-01, UI-04).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Tenant real-time daily KPI overview")
public class TenantDashboardOverviewResponse {

    private LocalDate workDate;
    private LocalDateTime generatedAt;

    private long totalTickets;
    private long appointmentTickets;
    private long walkInTickets;

    private long completedTickets;
    private long waitingTickets;
    private long servingTickets;
    private long noShowTickets;

    private long totalAppointments;
    private long cancelledAppointments;

    private double noShowRate;
    private double cancellationRate;
    private String noShowSeverity;

    private long avgServeMinutes;
    private double avgCsatRating;
    private long totalFeedbacksToday;
}
