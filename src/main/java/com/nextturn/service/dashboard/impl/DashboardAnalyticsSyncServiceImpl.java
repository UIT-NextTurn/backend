package com.nextturn.service.dashboard.impl;

import com.nextturn.constant.ValkeyKeyConstant;
import com.nextturn.entity.CentralTenant;
import com.nextturn.entity.CentralTenantQuota;
import com.nextturn.entity.TenantAppointment;
import com.nextturn.entity.TenantFeedback;
import com.nextturn.entity.TenantQueueTicket;
import com.nextturn.entity.TenantService;
import com.nextturn.entity.TenantStaff;
import com.nextturn.enums.TenantStatus;
import com.nextturn.enums.TicketCustomerType;
import com.nextturn.infrastructure.logging.LogContract;
import com.nextturn.infrastructure.logging.LogDomain;
import com.nextturn.infrastructure.logging.LogOutcome;
import com.nextturn.repository.auth.CentralTenantRepository;
import com.nextturn.repository.auth.CentralUserRepository;
import com.nextturn.repository.dashboard.DashboardAnalyticsRepository;
import com.nextturn.repository.tenant.CentralTenantQuotaRepository;
import com.nextturn.service.dashboard.DashboardAnalyticsSyncService;
import com.nextturn.service.dashboard.DashboardSseBroadcaster;
import com.nextturn.service.notification.EmailService;
import com.nextturn.service.tenant.TenantTelemetryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Default implementation of {@link DashboardAnalyticsSyncService} orchestrating non-blocking
 * analytics rollup updates via {@link DashboardAnalyticsRepository}, evaluating daily quota alert
 * thresholds (REQ-DASHBOARD-06), invalidating overview Valkey caches, and publishing SSE updates.
 */
@Service
public class DashboardAnalyticsSyncServiceImpl implements DashboardAnalyticsSyncService {

    private static final Logger log = LoggerFactory.getLogger(DashboardAnalyticsSyncServiceImpl.class);
    private static final double OVERTIME_MULTIPLIER = 1.5d;
    private static final double TICKET_WARNING_PCT = 85.0d;
    private static final double TICKET_CRITICAL_PCT = 100.0d;

    private final DashboardAnalyticsRepository analyticsRepository;
    private final StringRedisTemplate redisTemplate;
    private final CentralTenantRepository centralTenantRepository;
    private final CentralTenantQuotaRepository quotaRepository;
    private final CentralUserRepository centralUserRepository;
    private final TenantTelemetryService telemetryService;
    private final EmailService emailService;
    private final Clock clock;
    private DashboardSseBroadcaster sseBroadcaster;

    @Autowired
    public DashboardAnalyticsSyncServiceImpl(
            @Autowired(required = false) DashboardAnalyticsRepository analyticsRepository,
            @Autowired(required = false) StringRedisTemplate redisTemplate,
            @Autowired(required = false) CentralTenantRepository centralTenantRepository,
            @Autowired(required = false) CentralTenantQuotaRepository quotaRepository,
            @Autowired(required = false) CentralUserRepository centralUserRepository,
            @Autowired(required = false) TenantTelemetryService telemetryService,
            @Autowired(required = false) EmailService emailService
    ) {
        this(analyticsRepository, redisTemplate, centralTenantRepository, quotaRepository,
                centralUserRepository, telemetryService, emailService, Clock.systemDefaultZone());
    }

    public DashboardAnalyticsSyncServiceImpl(
            DashboardAnalyticsRepository analyticsRepository,
            StringRedisTemplate redisTemplate,
            CentralTenantRepository centralTenantRepository,
            CentralTenantQuotaRepository quotaRepository,
            CentralUserRepository centralUserRepository,
            TenantTelemetryService telemetryService,
            EmailService emailService,
            Clock clock
    ) {
        this.analyticsRepository = analyticsRepository;
        this.redisTemplate = redisTemplate;
        this.centralTenantRepository = centralTenantRepository;
        this.quotaRepository = quotaRepository;
        this.centralUserRepository = centralUserRepository;
        this.telemetryService = telemetryService;
        this.emailService = emailService;
        this.clock = clock != null ? clock : Clock.systemDefaultZone();
    }

    @Autowired(required = false)
    public void setSseBroadcaster(@Lazy DashboardSseBroadcaster sseBroadcaster) {
        this.sseBroadcaster = sseBroadcaster;
    }

    @Override
    public void onTicketIssued(String tenantCode, TenantQueueTicket ticket) {
        if (tenantCode == null || tenantCode.isBlank() || ticket == null || ticket.getService() == null) {
            return;
        }
        LocalDate workDate = ticket.getWorkDate() != null ? ticket.getWorkDate() : LocalDate.now(clock);
        String serviceId = ticket.getService().getId();
        int apptInc = ticket.getCustomerType() == TicketCustomerType.APPOINTMENT ? 1 : 0;
        int walkInInc = apptInc == 1 ? 0 : 1;
        int hourSlot = ticket.getIssuedAt() != null ? ticket.getIssuedAt().getHour() : LocalDateTime.now(clock).getHour();

        if (analyticsRepository != null) {
            analyticsRepository.incrementDailyServiceCounters(
                    tenantCode, serviceId, workDate, 1, apptInc, walkInInc, 0, 0, 0, 0L, 0L);
            analyticsRepository.incrementHourlyTicketIssued(tenantCode, serviceId, workDate, hourSlot);
            analyticsRepository.incrementPlatformCounters(workDate, 1, 0, 0);
            evaluateAndAlertDailyTicketQuota(tenantCode, workDate);
        }

        invalidateAndNotify(tenantCode, workDate, "TICKET_ISSUED", true);
    }

    @Override
    public void onTicketServing(String tenantCode, TenantQueueTicket ticket) {
        if (tenantCode == null || tenantCode.isBlank() || ticket == null || ticket.getService() == null) {
            return;
        }
        LocalDate workDate = ticket.getWorkDate() != null ? ticket.getWorkDate() : LocalDate.now(clock);
        String serviceId = ticket.getService().getId();
        long waitMinutes = 0L;
        if (ticket.getIssuedAt() != null && ticket.getServedAt() != null && !ticket.getServedAt().isBefore(ticket.getIssuedAt())) {
            waitMinutes = Math.max(0L, Duration.between(ticket.getIssuedAt(), ticket.getServedAt()).toMinutes());
        }
        int hourSlot = ticket.getIssuedAt() != null ? ticket.getIssuedAt().getHour() : LocalDateTime.now(clock).getHour();

        if (analyticsRepository != null && waitMinutes > 0L) {
            analyticsRepository.incrementDailyServiceCounters(
                    tenantCode, serviceId, workDate, 0, 0, 0, 0, 0, 0, waitMinutes, 0L);
            analyticsRepository.updateHourlyAvgWait(tenantCode, serviceId, workDate, hourSlot, waitMinutes);
        }

        invalidateAndNotify(tenantCode, workDate, "TICKET_SERVING", false);
    }

    @Override
    public void onTicketCompleted(String tenantCode, TenantQueueTicket ticket) {
        if (tenantCode == null || tenantCode.isBlank() || ticket == null || ticket.getService() == null) {
            return;
        }
        LocalDate workDate = ticket.getWorkDate() != null ? ticket.getWorkDate() : LocalDate.now(clock);
        TenantService service = ticket.getService();
        int standardDurationMinutes = service.getStandardDuration() != null && service.getStandardDuration() > 0
                ? service.getStandardDuration()
                : 15;
        long actualSeconds = computeActualSeconds(ticket);
        long serveMinutes = Math.max(0L, Math.round(actualSeconds / 60.0d));
        int overtimeInc = actualSeconds > Math.round(OVERTIME_MULTIPLIER * standardDurationMinutes * 60.0d) ? 1 : 0;
        int hourSlot = ticket.getIssuedAt() != null ? ticket.getIssuedAt().getHour() : LocalDateTime.now(clock).getHour();
        TenantStaff provider = ticket.getProvider();

        if (analyticsRepository != null) {
            analyticsRepository.incrementDailyServiceCounters(
                    tenantCode, service.getId(), workDate, 0, 0, 0, 1, 0, 0, 0L, serveMinutes);
            analyticsRepository.incrementHourlyTicketCompleted(tenantCode, service.getId(), workDate, hourSlot);
            if (provider != null && provider.getId() != null) {
                analyticsRepository.incrementDailyStaffPerformance(
                        tenantCode, provider.getId(), service.getId(), workDate, serveMinutes, overtimeInc);
            }
            analyticsRepository.incrementPlatformCounters(workDate, 0, 1, 0);
        }

        invalidateAndNotify(tenantCode, workDate, "TICKET_COMPLETED", true);
    }

    @Override
    public void onTicketNoShow(String tenantCode, TenantQueueTicket ticket) {
        if (tenantCode == null || tenantCode.isBlank() || ticket == null || ticket.getService() == null) {
            return;
        }
        LocalDate workDate = ticket.getWorkDate() != null ? ticket.getWorkDate() : LocalDate.now(clock);

        if (analyticsRepository != null) {
            analyticsRepository.incrementDailyServiceCounters(
                    tenantCode, ticket.getService().getId(), workDate, 0, 0, 0, 0, 1, 0, 0L, 0L);
            analyticsRepository.incrementPlatformCounters(workDate, 0, 0, 1);
        }

        invalidateAndNotify(tenantCode, workDate, "TICKET_NO_SHOW", true);
    }

    @Override
    public void onAppointmentCancelled(String tenantCode, TenantAppointment appointment) {
        if (tenantCode == null || tenantCode.isBlank() || appointment == null || appointment.getService() == null) {
            return;
        }
        LocalDate reportDate = (appointment.getSlot() != null && appointment.getSlot().getWorkDate() != null)
                ? appointment.getSlot().getWorkDate()
                : LocalDate.now(clock);

        if (analyticsRepository != null) {
            analyticsRepository.incrementDailyServiceCounters(
                    tenantCode, appointment.getService().getId(), reportDate, 0, 0, 0, 0, 0, 1, 0L, 0L);
        }

        invalidateAndNotify(tenantCode, reportDate, "APPOINTMENT_CANCELLED", false);
    }

    @Override
    public void onFeedbackSubmitted(String tenantCode, TenantFeedback feedback) {
        if (tenantCode == null || tenantCode.isBlank() || feedback == null) {
            return;
        }
        LocalDate reportDate = feedback.getCreatedAt() != null
                ? feedback.getCreatedAt().toLocalDate()
                : LocalDate.now(clock);
        invalidateAndNotify(tenantCode, reportDate, "FEEDBACK_SUBMITTED", false);
    }

    @Override
    public void onTenantLifecycleChanged(String tenantCode, String eventType) {
        syncPlatformDailySnapshot(LocalDate.now(clock));
        if (sseBroadcaster != null) {
            sseBroadcaster.broadcastPlatformOverviewUpdate(eventType != null ? eventType : "TENANT_UPDATED");
        }
    }

    @Override
    public int syncPlatformDailySnapshot(LocalDate reportDate) {
        if (centralTenantRepository == null || analyticsRepository == null) {
            return 0;
        }
        LocalDate targetDate = reportDate != null ? reportDate : LocalDate.now(clock);
        List<CentralTenant> tenants = centralTenantRepository.findAll().stream()
                .filter(t -> !Boolean.TRUE.equals(t.getIsDeleted()))
                .toList();

        int activeCount = (int) tenants.stream().filter(t -> t.getStatus() == TenantStatus.ACTIVE).count();
        int suspendedCount = (int) tenants.stream().filter(t -> t.getStatus() == TenantStatus.SUSPENDED).count();
        int newTodayCount = (int) tenants.stream()
                .filter(t -> t.getCreatedAt() != null && t.getCreatedAt().toLocalDate().equals(targetDate))
                .count();
        int activeUsers = centralUserRepository != null ? (int) centralUserRepository.count() : 0;

        analyticsRepository.upsertPlatformDailySnapshot(
                targetDate, activeCount, suspendedCount, newTodayCount, activeUsers);

        Map<String, CentralTenantQuota> quotaMap = preloadQuotaMap();
        for (CentralTenant tenant : tenants) {
            if (tenant.getStatus() != TenantStatus.ACTIVE) {
                continue;
            }
            String code = tenant.getTenantCode();
            long staffCount = telemetryService != null ? telemetryService.countActiveStaff(code) : 0L;
            long todayTickets = telemetryService != null ? telemetryService.countTodayTickets(code) : 0L;
            long activeServices = analyticsRepository.countActiveServices(code);
            CentralTenantQuota quota = quotaMap.get(tenant.getId());
            boolean exceeded = quota != null
                    && quota.getDailyTicketQuota() != null
                    && quota.getDailyTicketQuota() > 0
                    && todayTickets >= quota.getDailyTicketQuota();

            analyticsRepository.upsertTenantSummarySnapshot(
                    tenant.getId(), targetDate, (int) staffCount, (int) activeServices, (int) todayTickets, exceeded);
        }

        invalidatePlatformCacheOnly();
        return tenants.size();
    }

    // -------------------------------------------------------------------------
    // Quota Evaluation, Cache Invalidation & Notification Helpers
    // -------------------------------------------------------------------------

    private void evaluateAndAlertDailyTicketQuota(String tenantCode, LocalDate workDate) {
        if (centralTenantRepository == null || quotaRepository == null) {
            return;
        }
        CentralTenant tenant = centralTenantRepository.findByTenantCodeIgnoreCaseAndIsDeletedFalse(tenantCode).orElse(null);
        if (tenant == null) {
            return;
        }
        CentralTenantQuota quota = quotaRepository.findByTenantId(tenant.getId()).orElse(null);
        int dailyQuota = quota != null && quota.getDailyTicketQuota() != null ? quota.getDailyTicketQuota() : -1;

        int currentCount = analyticsRepository.incrementAndFetchTenantDailyTicketCount(tenant.getId(), workDate, dailyQuota);
        if (dailyQuota <= 0) {
            return;
        }

        double pct = (currentCount * 100.0d) / dailyQuota;
        if (pct >= TICKET_WARNING_PCT) {
            boolean critical = pct >= TICKET_CRITICAL_PCT;
            String severity = critical ? "CRITICAL" : "WARNING";
            LogContract.warn(log, LogDomain.DASHBOARD, "tenant_ticket_quota_alert")
                    .outcome(LogOutcome.DEGRADED)
                    .tenant(tenantCode)
                    .field("severity", severity)
                    .field("used", currentCount)
                    .field("quota", dailyQuota)
                    .field("usagePct", Math.round(pct * 10.0d) / 10.0d)
                    .emit();
            dispatchQuotaAlertEmailOnce(tenant, workDate, currentCount, dailyQuota, pct, severity);
        }
    }

    private void dispatchQuotaAlertEmailOnce(
            CentralTenant tenant,
            LocalDate workDate,
            int used,
            int quota,
            double pct,
            String severity
    ) {
        if (emailService == null || !emailService.isConfigured()
                || tenant.getAdminEmail() == null || tenant.getAdminEmail().isBlank()) {
            return;
        }
        String alertKey = ValkeyKeyConstant.dashboardQuotaAlertKey(tenant.getTenantCode(), workDate) + ":" + severity;
        if (redisTemplate != null) {
            try {
                Boolean fresh = redisTemplate.opsForValue().setIfAbsent(alertKey, "1", Duration.ofHours(24));
                if (!Boolean.TRUE.equals(fresh)) {
                    return;
                }
            } catch (Exception ignored) {
                return;
            }
        }
        String subject = String.format("[%s] Cảnh báo hạn ngạch vé ngày (%s) - %s",
                tenant.getTenantName(), severity, tenant.getTenantCode());
        String html = "<p>Xin chào Quản trị viên <strong>" + tenant.getTenantName() + "</strong>,</p>"
                + "<p>Lượng vé phát sinh trong ngày <strong>" + workDate + "</strong> đã đạt mức <strong>"
                + used + " / " + quota + "</strong> (" + String.format("%.1f", pct) + "% - " + severity + ").</p>"
                + "<p>Vui lòng liên hệ quản trị nền tảng NextTurn nếu cần nâng cấp gói hạn ngạch.</p>";
        emailService.sendHtmlEmailAsync(tenant.getAdminEmail(), subject, html);
    }

    private Map<String, CentralTenantQuota> preloadQuotaMap() {
        Map<String, CentralTenantQuota> quotaMap = new HashMap<>();
        if (quotaRepository != null) {
            for (CentralTenantQuota quota : quotaRepository.findAll()) {
                quotaMap.put(quota.getTenantId(), quota);
            }
        }
        return quotaMap;
    }

    private long computeActualSeconds(TenantQueueTicket ticket) {
        if (ticket.getActualDuration() != null && ticket.getActualDuration() >= 0) {
            return ticket.getActualDuration();
        }
        if (ticket.getServedAt() != null && ticket.getCompletedAt() != null
                && !ticket.getCompletedAt().isBefore(ticket.getServedAt())) {
            return Duration.between(ticket.getServedAt(), ticket.getCompletedAt()).getSeconds();
        }
        return 0L;
    }

    private void invalidateAndNotify(String tenantCode, LocalDate workDate, String eventType, boolean notifyPlatform) {
        if (redisTemplate != null) {
            try {
                redisTemplate.delete(ValkeyKeyConstant.dashboardOverviewKey(tenantCode, workDate));
                if (notifyPlatform) {
                    redisTemplate.delete(ValkeyKeyConstant.PLATFORM_OVERVIEW_CACHE_KEY);
                }
            } catch (Exception ex) {
                LogContract.warn(log, LogDomain.DASHBOARD, "dashboard_cache_evict")
                        .outcome(LogOutcome.DEGRADED)
                        .tenant(tenantCode)
                        .cause(ex)
                        .emit();
            }
        }
        if (sseBroadcaster != null) {
            sseBroadcaster.broadcastTenantOverviewUpdate(tenantCode, eventType);
            if (notifyPlatform) {
                sseBroadcaster.broadcastPlatformOverviewUpdate(eventType);
            }
        }
    }

    private void invalidatePlatformCacheOnly() {
        if (redisTemplate != null) {
            try {
                redisTemplate.delete(ValkeyKeyConstant.PLATFORM_OVERVIEW_CACHE_KEY);
            } catch (Exception ignored) {
                // Non-fatal
            }
        }
    }
}
