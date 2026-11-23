package com.nextturn.scheduler;

import com.nextturn.infrastructure.logging.LogContract;
import com.nextturn.infrastructure.logging.LogDomain;
import com.nextturn.infrastructure.logging.LogOutcome;
import com.nextturn.service.dashboard.DashboardAnalyticsSyncService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/**
 * Scheduled job reconciling platform-wide tenant status counts, active users, and daily tenant quota
 * summaries into {@code central__analytics_daily_platform} and {@code central__analytics_tenant_summaries}
 * (REQ-DASHBOARD-05, REQ-DASHBOARD-06).
 */
@Component
@EnableScheduling
@ConditionalOnProperty(name = "nextturn.scheduler.dashboard-snapshot.enabled", havingValue = "true", matchIfMissing = true)
public class DashboardSnapshotScheduler {

    private static final Logger log = LoggerFactory.getLogger(DashboardSnapshotScheduler.class);

    private final DashboardAnalyticsSyncService analyticsSyncService;

    @Autowired
    public DashboardSnapshotScheduler(@Autowired(required = false) DashboardAnalyticsSyncService analyticsSyncService) {
        this.analyticsSyncService = analyticsSyncService;
    }

    @Scheduled(fixedRateString = "${nextturn.scheduler.dashboard-snapshot.fixed-rate:300000}")
    public void syncPlatformDailyMetrics() {
        if (analyticsSyncService == null) {
            return;
        }
        LogContract.bindNewTraceId();
        try {
            int syncedTenants = analyticsSyncService.syncPlatformDailySnapshot(LocalDate.now());
            LogContract.info(log, LogDomain.SCHEDULER, "dashboard_platform_snapshot")
                    .outcome(LogOutcome.SUCCESS)
                    .field("syncedTenants", syncedTenants)
                    .emit();
        } catch (Exception ex) {
            LogContract.warn(log, LogDomain.SCHEDULER, "dashboard_platform_snapshot")
                    .outcome(LogOutcome.FAILED)
                    .cause(ex)
                    .emit();
        } finally {
            LogContract.clearTraceId();
        }
    }
}
