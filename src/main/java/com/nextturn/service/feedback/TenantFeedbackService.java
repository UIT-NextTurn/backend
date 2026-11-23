package com.nextturn.service.feedback;

import com.nextturn.dto.feedback.FeedbackFormInitResponse;
import com.nextturn.dto.feedback.FeedbackResponse;
import com.nextturn.dto.feedback.FeedbackSummaryResponse;
import com.nextturn.dto.feedback.SubmitFeedbackRequest;
import com.nextturn.entity.TenantQueueTicket;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Service contract for post-service feedback token lifecycle, submission validation,
 * CSAT aggregation, and Admin review monitoring (REQ-FEEDBACK-01 through REQ-FEEDBACK-04).
 */
public interface TenantFeedbackService {

    /**
     * Ensures a 24-hour CSAT {@code feedbackToken} and {@code feedbackTokenExpiresAt} are attached
     * to a ticket transitioning to {@code COMPLETED} (REQ-FEEDBACK-01).
     */
    String prepareFeedbackToken(TenantQueueTicket ticket, LocalDateTime completedAt);

    /**
     * Caches the token routing mapping in Valkey and dispatches the survey email if customer email is known.
     */
    void dispatchFeedbackSurvey(String tenantCode, TenantQueueTicket ticket);

    /**
     * Validates a feedback token and loads service/provider context for rendering the customer survey form.
     */
    FeedbackFormInitResponse getFeedbackForm(String feedbackToken);

    /**
     * Validates, sanitizes, and stores a one-time CSAT rating & review, updating CSAT rollups (REQ-FEEDBACK-02/03).
     */
    FeedbackResponse submitFeedback(String feedbackToken, SubmitFeedbackRequest request);

    /**
     * Searches feedback entries with optional star/negative/service/provider/date filters (REQ-FEEDBACK-04).
     */
    Page<FeedbackResponse> searchFeedbacks(
            Integer ratingScore,
            Boolean negativeOnly,
            String serviceId,
            String providerId,
            LocalDate fromDate,
            LocalDate toDate,
            Pageable pageable
    );

    /**
     * Computes aggregated CSAT score, average rating, star distribution, and negative review count (REQ-FEEDBACK-03/04).
     */
    FeedbackSummaryResponse getFeedbackSummary(
            Integer ratingScore,
            Boolean negativeOnly,
            String serviceId,
            String providerId,
            LocalDate fromDate,
            LocalDate toDate
    );
}
