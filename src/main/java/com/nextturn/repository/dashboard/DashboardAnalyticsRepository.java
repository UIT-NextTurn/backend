package com.nextturn.repository.dashboard;

import com.nextturn.dto.dashboard.PlatformDashboardOverviewResponse.PlatformTrafficPoint;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * JDBC repository managing MySQL RANGE-partitioned analytics rollup tables across both
 * tenant schemas ({@code tenant__analytics_*}) and the central schema ({@code central__analytics_*}).
 */
public interface DashboardAnalyticsRepository {

    void incrementDailyServiceCounters(
            String tenantCode,
            String serviceId,
            LocalDate reportDate,
            int totalInc,
            int apptInc,
            int walkInInc,
            int completedInc,
            int noShowInc,
            int cancelInc,
            long waitMinutesInc,
            long serveMinutesInc
    );

    void incrementHourlyTicketIssued(String tenantCode, String serviceId, LocalDate reportDate, int hourSlot);

    void incrementHourlyTicketCompleted(String tenantCode, String serviceId, LocalDate reportDate, int hourSlot);

    void updateHourlyAvgWait(String tenantCode, String serviceId, LocalDate reportDate, int hourSlot, long waitMinutes);

    void incrementDailyStaffPerformance(
            String tenantCode,
            String staffId,
            String serviceId,
            LocalDate reportDate,
            long serveMinutes,
            int overtimeInc
    );

    long countActiveServices(String tenantCode);

    void incrementPlatformCounters(LocalDate reportDate, int issuedInc, int completedInc, int noShowInc);

    void upsertPlatformDailySnapshot(
            LocalDate reportDate,
            int activeTenants,
            int suspendedTenants,
            int newRegistrationsToday,
            int activeUsers
    );

    int incrementAndFetchTenantDailyTicketCount(String tenantId, LocalDate reportDate, int dailyQuota);

    void upsertTenantSummarySnapshot(
            String tenantId,
            LocalDate reportDate,
            int activeStaff,
            int activeServices,
            int dailyTickets,
            boolean quotaExceeded
    );

    List<PlatformTrafficPoint> findPlatformTrafficSeries(LocalDate startDate, LocalDate endDate);

    Map<String, TenantSummarySnapshot> findTenantSummariesByDate(LocalDate reportDate);

    record TenantSummarySnapshot(
            long activeStaffCount,
            long activeServicesCount,
            long dailyTicketsCount,
            boolean quotaExceeded
    ) {
    }
}
