package com.nextturn.service.dashboard;

import com.nextturn.dto.dashboard.CancellationNoShowAnalyticsResponse;
import com.nextturn.dto.dashboard.FeedbackTrendAnalyticsResponse;
import com.nextturn.dto.dashboard.ServicePerformanceAnalyticsResponse;
import com.nextturn.dto.dashboard.TenantDashboardOverviewResponse;
import com.nextturn.enums.DashboardDatePreset;

import java.time.LocalDate;

/**
 * Service contract for Tenant Operations & Analytics Dashboard (REQ-DASHBOARD-01..04, UI-04).
 */
public interface TenantDashboardService {

    TenantDashboardOverviewResponse getOverview();

    CancellationNoShowAnalyticsResponse getCancellationAndNoShowAnalytics(
            DashboardDatePreset preset,
            LocalDate fromDate,
            LocalDate toDate,
            String serviceId,
            String staffId
    );

    ServicePerformanceAnalyticsResponse getServicePerformanceAnalytics(
            DashboardDatePreset preset,
            LocalDate fromDate,
            LocalDate toDate,
            String serviceId,
            String staffId
    );

    FeedbackTrendAnalyticsResponse getFeedbackTrends(
            DashboardDatePreset preset,
            LocalDate fromDate,
            LocalDate toDate,
            String serviceId,
            String providerId
    );
}
