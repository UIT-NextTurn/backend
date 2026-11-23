package com.nextturn.constant;

/**
 * Centralized API endpoint constants for Customer Feedback & CSAT Analytics
 * (REQ-FEEDBACK-01 through REQ-FEEDBACK-04).
 */
public final class FeedbackEndpointConstant {

    private FeedbackEndpointConstant() {
        // Utility class
    }

    public static final String PUBLIC_FEEDBACK_BASE = "/api/v1/public/feedback";
    public static final String FEEDBACK_BY_TOKEN = "/{token}";

    public static final String TENANT_FEEDBACK_BASE = "/api/v1/feedbacks";
    public static final String FEEDBACK_SUMMARY = "/summary";
}
