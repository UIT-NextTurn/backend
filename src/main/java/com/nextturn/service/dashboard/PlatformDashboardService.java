package com.nextturn.service.dashboard;

import com.nextturn.dto.dashboard.PlatformDashboardOverviewResponse;
import com.nextturn.dto.dashboard.TenantQuotaMonitoringResponse;
import com.nextturn.enums.QuotaMonitorFilter;

/**
 * Service contract for Superadmin platform-wide monitoring and tenant quota consumption alerts
 * (REQ-DASHBOARD-05, REQ-DASHBOARD-06, UI-05).
 */
public interface PlatformDashboardService {

    PlatformDashboardOverviewResponse getPlatformOverview(int days);

    TenantQuotaMonitoringResponse getTenantQuotaMonitoring(QuotaMonitorFilter filter);
}
