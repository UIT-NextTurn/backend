package com.nextturn.dto.dashboard;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Unified Server-Sent Event payload emitted to Tenant Admin or Superadmin dashboards
 * for zero-refresh KPI updates (REQ-DASHBOARD-01, REQ-DASHBOARD-05).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DashboardSseEventResponse {

    private String eventType;
    private String scope;
    private String tenantCode;
    private LocalDateTime occurredAt;
    private TenantDashboardOverviewResponse tenantOverview;
    private PlatformDashboardOverviewResponse platformOverview;
}
