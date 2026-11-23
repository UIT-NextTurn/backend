package com.nextturn.dto.dashboard;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Platform-wide real-time monitoring overview and historical traffic series for Superadmin (REQ-DASHBOARD-05, UI-05).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Superadmin platform monitoring overview and time-series")
public class PlatformDashboardOverviewResponse {

    private LocalDateTime generatedAt;

    private long activeTenants;
    private long suspendedTenants;
    private long pendingApprovalTenants;
    private long totalTenants;

    private long totalPlatformTicketsToday;
    private long completedTicketsToday;
    private long noShowTicketsToday;
    private long totalActiveUsers;

    private int trafficHistoryDays;
    private List<PlatformTrafficPoint> trafficSeries;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PlatformTrafficPoint {
        private LocalDate reportDate;
        private long ticketsIssued;
        private long ticketsCompleted;
        private long ticketsNoShow;
        private long activeTenants;
        private long activeUsers;
    }
}
