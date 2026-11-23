package com.nextturn.repository.dashboard.impl;

import com.nextturn.dto.dashboard.PlatformDashboardOverviewResponse.PlatformTrafficPoint;
import com.nextturn.infrastructure.logging.LogContract;
import com.nextturn.infrastructure.logging.LogDomain;
import com.nextturn.infrastructure.logging.LogOutcome;
import com.nextturn.infrastructure.multitenancy.TenantContextHolder;
import com.nextturn.repository.dashboard.DashboardAnalyticsRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * JDBC implementation of {@link DashboardAnalyticsRepository} executing schema-routed atomic
 * {@code INSERT ... ON DUPLICATE KEY UPDATE} rollups and time-series queries against
 * MySQL RANGE-partitioned analytics tables while isolating failures from caller transactions.
 */
@Repository
public class DashboardAnalyticsRepositoryImpl implements DashboardAnalyticsRepository {

    private static final Logger log = LoggerFactory.getLogger(DashboardAnalyticsRepositoryImpl.class);

    private final DataSource dataSource;

    @Autowired
    public DashboardAnalyticsRepositoryImpl(@Autowired(required = false) DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public void incrementDailyServiceCounters(
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
    ) {
        withSchemaConnection(tenantCode, "daily_service_rollup_upsert", conn -> {
            String sql = """
                INSERT INTO tenant__analytics_daily_services
                (id, service_id, report_date, total_tickets, appointment_tickets, walk_in_tickets,
                 completed_tickets, no_show_tickets, cancelled_appts, total_wait_minutes, total_serve_minutes)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE
                  total_tickets = total_tickets + VALUES(total_tickets),
                  appointment_tickets = appointment_tickets + VALUES(appointment_tickets),
                  walk_in_tickets = walk_in_tickets + VALUES(walk_in_tickets),
                  completed_tickets = completed_tickets + VALUES(completed_tickets),
                  no_show_tickets = no_show_tickets + VALUES(no_show_tickets),
                  cancelled_appts = cancelled_appts + VALUES(cancelled_appts),
                  total_wait_minutes = total_wait_minutes + VALUES(total_wait_minutes),
                  total_serve_minutes = total_serve_minutes + VALUES(total_serve_minutes)
                """;
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, UUID.randomUUID().toString());
                ps.setString(2, serviceId);
                ps.setDate(3, Date.valueOf(reportDate));
                ps.setInt(4, totalInc);
                ps.setInt(5, apptInc);
                ps.setInt(6, walkInInc);
                ps.setInt(7, completedInc);
                ps.setInt(8, noShowInc);
                ps.setInt(9, cancelInc);
                ps.setLong(10, waitMinutesInc);
                ps.setLong(11, serveMinutesInc);
                ps.executeUpdate();
            }
        });
    }

    @Override
    public void incrementHourlyTicketIssued(String tenantCode, String serviceId, LocalDate reportDate, int hourSlot) {
        withSchemaConnection(tenantCode, "hourly_ticket_issued_upsert", conn -> {
            String sql = """
                INSERT INTO tenant__analytics_hourly_traffic
                (id, service_id, report_date, hour_slot, ticket_count, completed_count, avg_wait_minutes)
                VALUES (?, ?, ?, ?, 1, 0, 0.00)
                ON DUPLICATE KEY UPDATE ticket_count = ticket_count + 1
                """;
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, UUID.randomUUID().toString());
                ps.setString(2, serviceId);
                ps.setDate(3, Date.valueOf(reportDate));
                ps.setInt(4, clampHour(hourSlot));
                ps.executeUpdate();
            }
        });
    }

    @Override
    public void incrementHourlyTicketCompleted(String tenantCode, String serviceId, LocalDate reportDate, int hourSlot) {
        withSchemaConnection(tenantCode, "hourly_ticket_completed_upsert", conn -> {
            String sql = """
                INSERT INTO tenant__analytics_hourly_traffic
                (id, service_id, report_date, hour_slot, ticket_count, completed_count, avg_wait_minutes)
                VALUES (?, ?, ?, ?, 0, 1, 0.00)
                ON DUPLICATE KEY UPDATE completed_count = completed_count + 1
                """;
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, UUID.randomUUID().toString());
                ps.setString(2, serviceId);
                ps.setDate(3, Date.valueOf(reportDate));
                ps.setInt(4, clampHour(hourSlot));
                ps.executeUpdate();
            }
        });
    }

    @Override
    public void updateHourlyAvgWait(String tenantCode, String serviceId, LocalDate reportDate, int hourSlot, long waitMinutes) {
        withSchemaConnection(tenantCode, "hourly_wait_update", conn -> {
            String sql = """
                UPDATE tenant__analytics_hourly_traffic
                SET avg_wait_minutes = ROUND(
                    ((avg_wait_minutes * GREATEST(ticket_count - 1, 0)) + ?) / GREATEST(ticket_count, 1), 2
                )
                WHERE service_id = ? AND report_date = ? AND hour_slot = ?
                """;
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setDouble(1, (double) waitMinutes);
                ps.setString(2, serviceId);
                ps.setDate(3, Date.valueOf(reportDate));
                ps.setInt(4, clampHour(hourSlot));
                ps.executeUpdate();
            }
        });
    }

    @Override
    public void incrementDailyStaffPerformance(
            String tenantCode,
            String staffId,
            String serviceId,
            LocalDate reportDate,
            long serveMinutes,
            int overtimeInc
    ) {
        withSchemaConnection(tenantCode, "daily_staff_performance_upsert", conn -> {
            String sql = """
                INSERT INTO tenant__analytics_daily_staffs
                (id, staff_id, service_id, report_date, served_count, total_duration_minutes, avg_duration_minutes, overtime_cases_count)
                VALUES (?, ?, ?, ?, 1, ?, ?, ?)
                ON DUPLICATE KEY UPDATE
                  total_duration_minutes = total_duration_minutes + VALUES(total_duration_minutes),
                  served_count = served_count + 1,
                  avg_duration_minutes = ROUND((total_duration_minutes + VALUES(total_duration_minutes)) / (served_count + 1), 2),
                  overtime_cases_count = overtime_cases_count + VALUES(overtime_cases_count)
                """;
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, UUID.randomUUID().toString());
                ps.setString(2, staffId);
                ps.setString(3, serviceId);
                ps.setDate(4, Date.valueOf(reportDate));
                ps.setInt(5, (int) serveMinutes);
                ps.setDouble(6, (double) serveMinutes);
                ps.setInt(7, overtimeInc);
                ps.executeUpdate();
            }
        });
    }

    @Override
    public long countActiveServices(String tenantCode) {
        final long[] count = {0L};
        withSchemaConnection(tenantCode, "count_active_services", conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT COUNT(*) FROM tenant__services WHERE status = 'ACTIVE' AND is_deleted = false");
                 ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    count[0] = rs.getLong(1);
                }
            }
        });
        return count[0];
    }

    @Override
    public void incrementPlatformCounters(LocalDate reportDate, int issuedInc, int completedInc, int noShowInc) {
        withSchemaConnection(null, "platform_counters_upsert", conn -> {
            String fullSql = """
                INSERT INTO central__analytics_daily_platform
                (id, report_date, total_tickets_issued, total_tickets_completed, total_tickets_no_show)
                VALUES (?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE
                  total_tickets_issued = total_tickets_issued + VALUES(total_tickets_issued),
                  total_tickets_completed = total_tickets_completed + VALUES(total_tickets_completed),
                  total_tickets_no_show = total_tickets_no_show + VALUES(total_tickets_no_show)
                """;
            try (PreparedStatement ps = conn.prepareStatement(fullSql)) {
                ps.setString(1, UUID.randomUUID().toString());
                ps.setDate(2, Date.valueOf(reportDate));
                ps.setInt(3, issuedInc);
                ps.setInt(4, completedInc);
                ps.setInt(5, noShowInc);
                ps.executeUpdate();
            } catch (SQLException ex) {
                // Fallback gracefully when V20261123020001 central migration has not yet added total_tickets_no_show
                if (ex.getMessage() != null && ex.getMessage().contains("total_tickets_no_show")) {
                    String compatSql = """
                        INSERT INTO central__analytics_daily_platform
                        (id, report_date, total_tickets_issued, total_tickets_completed)
                        VALUES (?, ?, ?, ?)
                        ON DUPLICATE KEY UPDATE
                          total_tickets_issued = total_tickets_issued + VALUES(total_tickets_issued),
                          total_tickets_completed = total_tickets_completed + VALUES(total_tickets_completed)
                        """;
                    try (PreparedStatement ps = conn.prepareStatement(compatSql)) {
                        ps.setString(1, UUID.randomUUID().toString());
                        ps.setDate(2, Date.valueOf(reportDate));
                        ps.setInt(3, issuedInc);
                        ps.setInt(4, completedInc);
                        ps.executeUpdate();
                    }
                } else {
                    throw ex;
                }
            }
        });
    }

    @Override
    public void upsertPlatformDailySnapshot(
            LocalDate reportDate,
            int activeTenants,
            int suspendedTenants,
            int newRegistrationsToday,
            int activeUsers
    ) {
        withSchemaConnection(null, "platform_snapshot_upsert", conn -> {
            String sql = """
                INSERT INTO central__analytics_daily_platform
                (id, report_date, active_tenants_count, suspended_tenants_count, new_registrations_count, total_active_users)
                VALUES (?, ?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE
                  active_tenants_count = VALUES(active_tenants_count),
                  suspended_tenants_count = VALUES(suspended_tenants_count),
                  new_registrations_count = VALUES(new_registrations_count),
                  total_active_users = VALUES(total_active_users)
                """;
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, UUID.randomUUID().toString());
                ps.setDate(2, Date.valueOf(reportDate));
                ps.setInt(3, activeTenants);
                ps.setInt(4, suspendedTenants);
                ps.setInt(5, newRegistrationsToday);
                ps.setInt(6, activeUsers);
                ps.executeUpdate();
            }
        });
    }

    @Override
    public int incrementAndFetchTenantDailyTicketCount(String tenantId, LocalDate reportDate, int dailyQuota) {
        final int[] count = {0};
        withSchemaConnection(null, "tenant_summary_ticket_increment", conn -> {
            String upsertSql = """
                INSERT INTO central__analytics_tenant_summaries
                (id, tenant_id, report_date, daily_tickets_count, is_quota_exceeded)
                VALUES (?, ?, ?, 1, ?)
                ON DUPLICATE KEY UPDATE
                  daily_tickets_count = daily_tickets_count + 1,
                  is_quota_exceeded = CASE
                    WHEN ? > 0 AND (daily_tickets_count + 1) >= ? THEN TRUE
                    ELSE is_quota_exceeded
                  END
                """;
            try (PreparedStatement ps = conn.prepareStatement(upsertSql)) {
                ps.setString(1, UUID.randomUUID().toString());
                ps.setString(2, tenantId);
                ps.setDate(3, Date.valueOf(reportDate));
                ps.setBoolean(4, dailyQuota > 0 && dailyQuota <= 1);
                ps.setInt(5, dailyQuota);
                ps.setInt(6, dailyQuota);
                ps.executeUpdate();
            }

            String readSql = "SELECT daily_tickets_count FROM central__analytics_tenant_summaries WHERE tenant_id = ? AND report_date = ?";
            try (PreparedStatement ps = conn.prepareStatement(readSql)) {
                ps.setString(1, tenantId);
                ps.setDate(2, Date.valueOf(reportDate));
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        count[0] = rs.getInt(1);
                    }
                }
            }
        });
        return count[0];
    }

    @Override
    public void upsertTenantSummarySnapshot(
            String tenantId,
            LocalDate reportDate,
            int activeStaff,
            int activeServices,
            int dailyTickets,
            boolean quotaExceeded
    ) {
        withSchemaConnection(null, "tenant_summary_snapshot_upsert", conn -> {
            String sql = """
                INSERT INTO central__analytics_tenant_summaries
                (id, tenant_id, report_date, active_staff_count, active_services_count, daily_tickets_count, is_quota_exceeded)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE
                  active_staff_count = VALUES(active_staff_count),
                  active_services_count = VALUES(active_services_count),
                  daily_tickets_count = VALUES(daily_tickets_count),
                  is_quota_exceeded = VALUES(is_quota_exceeded)
                """;
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, UUID.randomUUID().toString());
                ps.setString(2, tenantId);
                ps.setDate(3, Date.valueOf(reportDate));
                ps.setInt(4, activeStaff);
                ps.setInt(5, activeServices);
                ps.setInt(6, dailyTickets);
                ps.setBoolean(7, quotaExceeded);
                ps.executeUpdate();
            }
        });
    }

    @Override
    public List<PlatformTrafficPoint> findPlatformTrafficSeries(LocalDate startDate, LocalDate endDate) {
        List<PlatformTrafficPoint> points = new ArrayList<>();
        withSchemaConnection(null, "platform_series_query", conn -> {
            try {
                executePlatformSeriesQuery(conn, startDate, endDate, true, points);
            } catch (Exception missingColEx) {
                if (missingColEx.getMessage() != null && missingColEx.getMessage().contains("total_tickets_no_show")) {
                    points.clear();
                    executePlatformSeriesQuery(conn, startDate, endDate, false, points);
                } else {
                    throw missingColEx;
                }
            }
        });
        return points;
    }

    @Override
    public Map<String, TenantSummarySnapshot> findTenantSummariesByDate(LocalDate reportDate) {
        Map<String, TenantSummarySnapshot> map = new HashMap<>();
        withSchemaConnection(null, "quota_summaries_query", conn -> {
            String sql = """
                SELECT tenant_id, active_staff_count, active_services_count,
                       daily_tickets_count, is_quota_exceeded
                FROM central__analytics_tenant_summaries
                WHERE report_date = ?
                """;
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setDate(1, Date.valueOf(reportDate));
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        map.put(rs.getString("tenant_id"), new TenantSummarySnapshot(
                                rs.getInt("active_staff_count"),
                                rs.getInt("active_services_count"),
                                rs.getInt("daily_tickets_count"),
                                rs.getBoolean("is_quota_exceeded")
                        ));
                    }
                }
            }
        });
        return map;
    }

    // -------------------------------------------------------------------------
    // Internal JDBC Routing & Mapping Helpers
    // -------------------------------------------------------------------------

    @FunctionalInterface
    private interface SqlWork {
        void execute(Connection conn) throws Exception;
    }

    private void withSchemaConnection(String tenantCode, String actionName, SqlWork work) {
        if (dataSource == null) {
            return;
        }
        String previousTenant = TenantContextHolder.getTenantCode();
        try {
            if (tenantCode != null && !tenantCode.isBlank()) {
                TenantContextHolder.setTenantCode(tenantCode.trim());
            } else {
                TenantContextHolder.clear();
            }
            try (Connection conn = dataSource.getConnection()) {
                if (conn != null) {
                    work.execute(conn);
                }
            }
        } catch (Exception ex) {
            LogContract.warn(log, LogDomain.DASHBOARD, actionName)
                    .outcome(LogOutcome.DEGRADED)
                    .tenant(tenantCode != null ? tenantCode : "central")
                    .cause(ex)
                    .emit();
        } finally {
            if (previousTenant != null) {
                TenantContextHolder.setTenantCode(previousTenant);
            } else {
                TenantContextHolder.clear();
            }
        }
    }

    private void executePlatformSeriesQuery(
            Connection conn,
            LocalDate startDate,
            LocalDate endDate,
            boolean includeNoShow,
            List<PlatformTrafficPoint> points
    ) throws Exception {
        String sql = includeNoShow
                ? """
                    SELECT report_date, active_tenants_count, total_tickets_issued,
                           total_tickets_completed, total_tickets_no_show, total_active_users
                    FROM central__analytics_daily_platform
                    WHERE report_date BETWEEN ? AND ?
                    ORDER BY report_date ASC
                    """
                : """
                    SELECT report_date, active_tenants_count, total_tickets_issued,
                           total_tickets_completed, total_active_users
                    FROM central__analytics_daily_platform
                    WHERE report_date BETWEEN ? AND ?
                    ORDER BY report_date ASC
                    """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setDate(1, Date.valueOf(startDate));
            ps.setDate(2, Date.valueOf(endDate));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    points.add(PlatformTrafficPoint.builder()
                            .reportDate(rs.getDate("report_date").toLocalDate())
                            .activeTenants(rs.getInt("active_tenants_count"))
                            .ticketsIssued(rs.getInt("total_tickets_issued"))
                            .ticketsCompleted(rs.getInt("total_tickets_completed"))
                            .ticketsNoShow(includeNoShow ? rs.getInt("total_tickets_no_show") : 0L)
                            .activeUsers(rs.getInt("total_active_users"))
                            .build());
                }
            }
        }
    }

    private int clampHour(int hourSlot) {
        return Math.max(0, Math.min(23, hourSlot));
    }
}
