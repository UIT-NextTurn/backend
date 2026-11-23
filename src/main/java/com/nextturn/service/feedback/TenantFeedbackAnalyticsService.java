package com.nextturn.service.feedback;

import com.nextturn.dto.feedback.FeedbackSummaryResponse;
import com.nextturn.entity.TenantFeedback;

import java.util.List;

/**
 * Contract for CSAT KPI calculation, Valkey statistics caching, and daily analytics
 * table rollups ({@code tenant__analytics_daily_services}, {@code tenant__analytics_daily_staffs})
 * (REQ-FEEDBACK-03).
 */
public interface TenantFeedbackAnalyticsService {

    /**
     * Updates Valkey CSAT cache and synchronizes daily MySQL service/staff feedback counters.
     */
    void recordFeedbackMetrics(String tenantCode, TenantFeedback feedback);

    /**
     * Computes average rating, CSAT percentage (4-5 star share), negative count (1-2 stars),
     * and full 1-5 star distribution from the given feedback list.
     */
    FeedbackSummaryResponse computeSummary(List<TenantFeedback> feedbacks);

    FeedbackSummaryResponse getCachedSummary(String tenantCode);

    void cacheSummary(String tenantCode, FeedbackSummaryResponse summary);
}
