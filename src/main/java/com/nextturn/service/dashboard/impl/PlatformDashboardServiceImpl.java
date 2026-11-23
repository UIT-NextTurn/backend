package com.nextturn.service.dashboard.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.nextturn.constant.ValkeyKeyConstant;
import com.nextturn.dto.dashboard.PlatformDashboardOverviewResponse;
import com.nextturn.dto.dashboard.PlatformDashboardOverviewResponse.PlatformTrafficPoint;
import com.nextturn.dto.dashboard.TenantQuotaMonitoringResponse;
import com.nextturn.dto.dashboard.TenantQuotaMonitoringResponse.TenantQuotaUsageRow;
import com.nextturn.entity.CentralTenant;
import com.nextturn.entity.CentralTenantQuota;
import com.nextturn.enums.QuotaAlertLevel;
import com.nextturn.enums.QuotaMonitorFilter;
import com.nextturn.enums.TenantStatus;
import com.nextturn.exception.dashboard.DashboardErrorCode;
import com.nextturn.exception.dashboard.DashboardException;
import com.nextturn.infrastructure.logging.LogContract;
import com.nextturn.infrastructure.logging.LogDomain;
import com.nextturn.infrastructure.logging.LogOutcome;
import com.nextturn.repository.auth.CentralTenantRepository;
import com.nextturn.repository.auth.CentralUserRepository;
import com.nextturn.repository.dashboard.DashboardAnalyticsRepository;
import com.nextturn.repository.dashboard.DashboardAnalyticsRepository.TenantSummarySnapshot;
import com.nextturn.repository.tenant.CentralTenantQuotaRepository;
import com.nextturn.service.dashboard.PlatformDashboardService;
import com.nextturn.service.tenant.TenantTelemetryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Default implementation of {@link PlatformDashboardService} serving Superadmin platform-wide
 * KPI monitoring (Valkey {@code cache:platform:overview}, {@code central__analytics_daily_platform})
 * and tenant quota consumption alerts (REQ-DASHBOARD-05, REQ-DASHBOARD-06).
 */
@Service
public class PlatformDashboardServiceImpl implements PlatformDashboardService {

    private static final Logger log = LoggerFactory.getLogger(PlatformDashboardServiceImpl.class);
    private static final Duration PLATFORM_OVERVIEW_TTL = Duration.ofSeconds(60);
    private static final double TICKET_WARNING_PCT = 85.0d;
    private static final double STAFF_WARNING_PCT = 90.0d;
    private static final double CRITICAL_PCT = 100.0d;

    private final CentralTenantRepository centralTenantRepository;
    private final CentralTenantQuotaRepository quotaRepository;
    private final CentralUserRepository centralUserRepository;
    private final TenantTelemetryService telemetryService;
    private final DashboardAnalyticsRepository analyticsRepository;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @Autowired
    public PlatformDashboardServiceImpl(
            CentralTenantRepository centralTenantRepository,
            CentralTenantQuotaRepository quotaRepository,
            @Autowired(required = false) CentralUserRepository centralUserRepository,
            @Autowired(required = false) TenantTelemetryService telemetryService,
            @Autowired(required = false) DashboardAnalyticsRepository analyticsRepository,
            @Autowired(required = false) StringRedisTemplate redisTemplate,
            @Autowired(required = false) ObjectMapper objectMapper
    ) {
        this(centralTenantRepository, quotaRepository, centralUserRepository, telemetryService,
                analyticsRepository, redisTemplate, objectMapper, Clock.systemDefaultZone());
    }

    public PlatformDashboardServiceImpl(
            CentralTenantRepository centralTenantRepository,
            CentralTenantQuotaRepository quotaRepository,
            CentralUserRepository centralUserRepository,
            TenantTelemetryService telemetryService,
            DashboardAnalyticsRepository analyticsRepository,
            StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper,
            Clock clock
    ) {
        this.centralTenantRepository = centralTenantRepository;
        this.quotaRepository = quotaRepository;
        this.centralUserRepository = centralUserRepository;
        this.telemetryService = telemetryService;
        this.analyticsRepository = analyticsRepository;
        this.redisTemplate = redisTemplate;
        this.objectMapper = (objectMapper != null ? objectMapper.copy() : new ObjectMapper())
                .findAndRegisterModules()
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        this.clock = clock != null ? clock : Clock.systemDefaultZone();
    }

    @Override
    public PlatformDashboardOverviewResponse getPlatformOverview(int days) {
        if (days != 7 && days != 30 && days != 90) {
            throw new DashboardException(
                    DashboardErrorCode.INVALID_FILTER_PARAMETER,
                    "Chu kỳ biểu đồ hệ thống chỉ hỗ trợ 7, 30 hoặc 90 ngày."
            );
        }

        if (days == 7) {
            PlatformDashboardOverviewResponse cached = readCachedOverview();
            if (cached != null && cached.getTrafficHistoryDays() == 7) {
                return cached;
            }
        }

        LocalDate today = LocalDate.now(clock);
        List<CentralTenant> tenants = centralTenantRepository.findAll().stream()
                .filter(t -> !Boolean.TRUE.equals(t.getIsDeleted()))
                .toList();

        long activeTenants = tenants.stream().filter(t -> t.getStatus() == TenantStatus.ACTIVE).count();
        long suspendedTenants = tenants.stream().filter(t -> t.getStatus() == TenantStatus.SUSPENDED).count();
        long pendingTenants = tenants.stream().filter(t -> t.getStatus() == TenantStatus.PENDING_APPROVAL).count();
        long totalUsers = centralUserRepository != null ? centralUserRepository.count() : 0L;

        LocalDate startDate = today.minusDays(days - 1L);
        List<PlatformTrafficPoint> history = analyticsRepository != null
                ? analyticsRepository.findPlatformTrafficSeries(startDate, today)
                : List.of();

        PlatformTrafficPoint todayRollup = history.stream()
                .filter(p -> today.equals(p.getReportDate()))
                .findFirst()
                .orElse(null);

        long issuedToday = todayRollup != null ? todayRollup.getTicketsIssued() : sumLiveTodayTickets(tenants);
        long completedToday = todayRollup != null ? todayRollup.getTicketsCompleted() : 0L;
        long noShowToday = todayRollup != null ? todayRollup.getTicketsNoShow() : 0L;

        PlatformDashboardOverviewResponse response = PlatformDashboardOverviewResponse.builder()
                .generatedAt(LocalDateTime.now(clock))
                .activeTenants(activeTenants)
                .suspendedTenants(suspendedTenants)
                .pendingApprovalTenants(pendingTenants)
                .totalTenants(tenants.size())
                .totalPlatformTicketsToday(issuedToday)
                .completedTicketsToday(completedToday)
                .noShowTicketsToday(noShowToday)
                .totalActiveUsers(totalUsers)
                .trafficHistoryDays(days)
                .trafficSeries(history)
                .build();

        if (days == 7) {
            writeCachedOverview(response);
        }
        return response;
    }

    @Override
    public TenantQuotaMonitoringResponse getTenantQuotaMonitoring(QuotaMonitorFilter filter) {
        QuotaMonitorFilter effectiveFilter = filter != null ? filter : QuotaMonitorFilter.ALL;
        LocalDate today = LocalDate.now(clock);

        List<CentralTenant> tenants = centralTenantRepository.findAll().stream()
                .filter(t -> !Boolean.TRUE.equals(t.getIsDeleted()))
                .toList();

        Map<String, CentralTenantQuota> quotaMap = new HashMap<>();
        for (CentralTenantQuota q : quotaRepository.findAll()) {
            quotaMap.put(q.getTenantId(), q);
        }
        Map<String, TenantSummarySnapshot> summaryMap = analyticsRepository != null
                ? analyticsRepository.findTenantSummariesByDate(today)
                : Map.of();

        List<TenantQuotaUsageRow> rows = new ArrayList<>();
        long warningCount = 0L;
        long criticalCount = 0L;

        for (CentralTenant tenant : tenants) {
            CentralTenantQuota quota = quotaMap.get(tenant.getId());
            int dailyTicketQuota = quota != null && quota.getDailyTicketQuota() != null ? quota.getDailyTicketQuota() : -1;
            int maxStaffQuota = quota != null && quota.getMaxStaffs() != null ? quota.getMaxStaffs() : -1;
            int maxServicesQuota = quota != null && quota.getMaxServices() != null ? quota.getMaxServices() : -1;

            TenantSummarySnapshot summary = summaryMap.get(tenant.getId());
            long dailyTickets = summary != null
                    ? summary.dailyTicketsCount()
                    : (telemetryService != null && tenant.getStatus() == TenantStatus.ACTIVE
                            ? telemetryService.countTodayTickets(tenant.getTenantCode())
                            : 0L);
            long activeStaff = summary != null && summary.activeStaffCount() > 0
                    ? summary.activeStaffCount()
                    : (telemetryService != null && tenant.getStatus() == TenantStatus.ACTIVE
                            ? telemetryService.countActiveStaff(tenant.getTenantCode())
                            : 0L);
            long activeServices = summary != null ? summary.activeServicesCount() : 0L;

            double ticketPct = dailyTicketQuota > 0 ? DashboardSupport.computePercentage(dailyTickets, dailyTicketQuota) : 0.0d;
            double staffPct = maxStaffQuota > 0 ? DashboardSupport.computePercentage(activeStaff, maxStaffQuota) : 0.0d;

            boolean ticketExceeded = (summary != null && summary.quotaExceeded())
                    || (dailyTicketQuota > 0 && ticketPct >= CRITICAL_PCT);

            QuotaAlertLevel level = evaluateAlertLevel(dailyTicketQuota, maxStaffQuota, ticketPct, staffPct, ticketExceeded);
            if (level == QuotaAlertLevel.WARNING) {
                warningCount++;
            } else if (level == QuotaAlertLevel.CRITICAL) {
                criticalCount++;
            }

            TenantQuotaUsageRow row = TenantQuotaUsageRow.builder()
                    .tenantId(tenant.getId())
                    .tenantCode(tenant.getTenantCode())
                    .tenantName(tenant.getTenantName())
                    .status(tenant.getStatus())
                    .dailyTicketsUsed(dailyTickets)
                    .dailyTicketQuota(dailyTicketQuota)
                    .ticketUsagePercentage(ticketPct)
                    .activeStaffCount(activeStaff)
                    .maxStaffQuota(maxStaffQuota)
                    .staffUsagePercentage(staffPct)
                    .activeServicesCount(activeServices)
                    .maxServicesQuota(maxServicesQuota)
                    .quotaExceeded(ticketExceeded)
                    .alertLevel(level)
                    .build();

            if (matchesQuotaFilter(row, effectiveFilter)) {
                rows.add(row);
            }
        }

        // Pin exceeded / CRITICAL tenants first, then descending by max resource utilization
        rows.sort(Comparator.comparing(TenantQuotaUsageRow::isQuotaExceeded, Comparator.reverseOrder())
                .thenComparing(
                        (TenantQuotaUsageRow r) -> Math.max(r.getTicketUsagePercentage(), r.getStaffUsagePercentage()),
                        Comparator.reverseOrder())
                .thenComparing(TenantQuotaUsageRow::getTenantCode));

        return TenantQuotaMonitoringResponse.builder()
                .generatedAt(LocalDateTime.now(clock))
                .filter(effectiveFilter)
                .totalTenants(rows.size())
                .warningCount(warningCount)
                .criticalCount(criticalCount)
                .tenants(rows)
                .build();
    }

    private QuotaAlertLevel evaluateAlertLevel(
            int dailyTicketQuota,
            int maxStaffQuota,
            double ticketPct,
            double staffPct,
            boolean ticketExceeded
    ) {
        if (ticketExceeded || (dailyTicketQuota > 0 && ticketPct >= CRITICAL_PCT)) {
            return QuotaAlertLevel.CRITICAL;
        }
        if ((dailyTicketQuota > 0 && ticketPct >= TICKET_WARNING_PCT)
                || (maxStaffQuota > 0 && staffPct >= STAFF_WARNING_PCT)) {
            return QuotaAlertLevel.WARNING;
        }
        if (dailyTicketQuota <= 0 && maxStaffQuota <= 0) {
            return QuotaAlertLevel.UNLIMITED;
        }
        return QuotaAlertLevel.NORMAL;
    }

    private boolean matchesQuotaFilter(TenantQuotaUsageRow row, QuotaMonitorFilter filter) {
        return switch (filter) {
            case ALL -> true;
            case TICKET_EXCEEDED -> row.isQuotaExceeded() || row.getAlertLevel() == QuotaAlertLevel.CRITICAL;
            case STAFF_WARNING -> row.getMaxStaffQuota() > 0 && row.getStaffUsagePercentage() >= STAFF_WARNING_PCT;
        };
    }

    private long sumLiveTodayTickets(List<CentralTenant> tenants) {
        if (telemetryService == null) {
            return 0L;
        }
        return tenants.stream()
                .filter(t -> t.getStatus() == TenantStatus.ACTIVE)
                .mapToLong(t -> telemetryService.countTodayTickets(t.getTenantCode()))
                .sum();
    }

    private PlatformDashboardOverviewResponse readCachedOverview() {
        if (redisTemplate == null) {
            return null;
        }
        try {
            String json = redisTemplate.opsForValue().get(ValkeyKeyConstant.PLATFORM_OVERVIEW_CACHE_KEY);
            if (json != null && !json.isBlank()) {
                return objectMapper.readValue(json, PlatformDashboardOverviewResponse.class);
            }
        } catch (Exception ex) {
            LogContract.warn(log, LogDomain.DASHBOARD, "platform_overview_cache_read")
                    .outcome(LogOutcome.DEGRADED)
                    .cause(ex)
                    .emit();
        }
        return null;
    }

    private void writeCachedOverview(PlatformDashboardOverviewResponse response) {
        if (redisTemplate == null || response == null) {
            return;
        }
        try {
            String json = objectMapper.writeValueAsString(response);
            redisTemplate.opsForValue().set(ValkeyKeyConstant.PLATFORM_OVERVIEW_CACHE_KEY, json, PLATFORM_OVERVIEW_TTL);
        } catch (Exception ex) {
            LogContract.warn(log, LogDomain.DASHBOARD, "platform_overview_cache_write")
                    .outcome(LogOutcome.DEGRADED)
                    .cause(ex)
                    .emit();
        }
    }
}
