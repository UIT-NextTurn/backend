package com.nextturn.dto.feedback;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * Aggregated Customer Satisfaction Score (CSAT) metrics and star distribution (REQ-FEEDBACK-03, REQ-FEEDBACK-04).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "Aggregated CSAT metrics, average rating, and star distribution")
public class FeedbackSummaryResponse {

    private long totalFeedbacks;
    private double averageRating;
    private double csatPercentage;
    private long negativeCount;
    private Map<Integer, Long> ratingDistribution;
}
