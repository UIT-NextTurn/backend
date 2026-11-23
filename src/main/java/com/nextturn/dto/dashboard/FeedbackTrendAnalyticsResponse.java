package com.nextturn.dto.dashboard;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Feedback star distribution (1-5) and weekly/monthly CSAT trend series (REQ-DASHBOARD-04).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "CSAT star distribution and weekly/monthly trends")
public class FeedbackTrendAnalyticsResponse {

    private LocalDate fromDate;
    private LocalDate toDate;
    private String serviceId;
    private String providerId;

    private long totalFeedbacks;
    private double averageRating;
    private double csatPercentage;
    private long negativeCount;
    private Map<Integer, Long> ratingDistribution;

    private List<CsatTrendPoint> weeklyTrends;
    private List<CsatTrendPoint> monthlyTrends;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CsatTrendPoint {
        private String periodLabel;
        private LocalDate periodStart;
        private long totalFeedbacks;
        private double averageRating;
        private double csatPercentage;
    }
}
