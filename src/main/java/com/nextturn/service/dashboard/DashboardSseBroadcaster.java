package com.nextturn.service.dashboard;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Thread-safe SSE broadcaster streaming real-time operational KPI snapshots to Tenant Admin
 * and Central Platform Superadmin dashboards (REQ-DASHBOARD-01, REQ-DASHBOARD-05).
 */
public interface DashboardSseBroadcaster {

    SseEmitter subscribeTenantOverview(String tenantCode);

    SseEmitter subscribePlatformOverview();

    void broadcastTenantOverviewUpdate(String tenantCode, String eventType);

    void broadcastPlatformOverviewUpdate(String eventType);

    /**
     * Sends an SSE comment heartbeat to all active tenant and platform dashboard emitters
     * and evicts dead connections.
     *
     * @return number of healthy emitters successfully pinged
     */
    default int sendHeartbeats() {
        return 0;
    }
}
