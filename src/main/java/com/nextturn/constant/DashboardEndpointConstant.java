package com.nextturn.constant;

/**
 * URL endpoint constants for Tenant Operations & Analytics Dashboard (REQ-DASHBOARD-01..04)
 * and Superadmin Central Platform Monitoring (REQ-DASHBOARD-05..06).
 */
public final class DashboardEndpointConstant {

    private DashboardEndpointConstant() {
        // Prevent instantiation
    }

    public static final String TENANT_DASHBOARD_BASE = "/api/v1/dashboard";
    public static final String CENTRAL_DASHBOARD_BASE = "/api/v1/central/dashboard";

    public static final String OVERVIEW = "/overview";
    public static final String OVERVIEW_STREAM = "/overview/stream";
    public static final String OVERVIEW_STREAM_TOKEN = "/overview/stream-token";
    public static final String CANCELLATIONS_NOSHOWS = "/analytics/cancellations-noshows";
    public static final String SERVICE_PERFORMANCE = "/analytics/service-performance";
    public static final String FEEDBACK_TRENDS = "/analytics/feedback-trends";
    public static final String EXPORT = "/export";

    public static final String QUOTAS = "/quotas";
}
