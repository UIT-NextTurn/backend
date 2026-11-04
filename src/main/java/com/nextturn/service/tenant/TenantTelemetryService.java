package com.nextturn.service.tenant;

/**
 * Service contract providing tenant-level telemetry, active staff count, today's queue ticket volume,
 * and historical operational data verification (REQ-TNT-03, REQ-TNT-04).
 */
public interface TenantTelemetryService {

    long countActiveStaff(String tenantCode);

    long countTodayTickets(String tenantCode);

    boolean checkTenantHistoricalData(String tenantCode);
}
