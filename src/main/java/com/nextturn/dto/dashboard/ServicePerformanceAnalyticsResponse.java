package com.nextturn.dto.dashboard;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.util.List;

/**
 * Service duration benchmark vs. T_standard, staff leaderboard, and 3-consecutive overtime alerts (REQ-DASHBOARD-03).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Average service duration, staff performance leaderboard, and overtime alerts")
public class ServicePerformanceAnalyticsResponse {

    private LocalDate fromDate;
    private LocalDate toDate;
    private String serviceId;
    private String staffId;

    private long totalCompletedServed;
    private long overallAvgServeMinutes;

    private List<HourlyServeTrendPoint> hourlyServeTrend;
    private List<ServiceVarianceRow> serviceVariances;
    private List<StaffLeaderboardRow> staffLeaderboard;
    private List<CounterOvertimeAlert> counterOvertimeAlerts;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class HourlyServeTrendPoint {
        private int hourSlot;
        private long completedCount;
        private long avgServeMinutes;
        private double avgWaitMinutes;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ServiceVarianceRow {
        private String serviceId;
        private String serviceCode;
        private String serviceName;
        private int standardDurationMinutes;
        private long actualAvgServeMinutes;
        private long varianceMinutes;
        private long servedCount;
        private long overtimeCount;
        private double overtimeRate;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class StaffLeaderboardRow {
        private String staffId;
        private String staffCode;
        private String staffName;
        private long servedCount;
        private long avgServeMinutes;
        private long overtimeCount;
        private long totalFeedbackCount;
        private long positiveFeedbackCount;
        private double positiveFeedbackRate;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CounterOvertimeAlert {
        private String counterKey;
        private String counterLabel;
        private String providerId;
        private String providerName;
        private String serviceName;
        private int consecutiveOvertimeCount;
        private int standardDurationMinutes;
        private int thresholdMinutes;
        private String lastTicketCode;
        private int lastActualMinutes;
        private String severity;
    }
}
