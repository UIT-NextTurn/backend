package com.nextturn.service.dashboard.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.nextturn.constant.ValkeyKeyConstant;
import com.nextturn.dto.dashboard.CancellationNoShowAnalyticsResponse;
import com.nextturn.dto.dashboard.CancellationNoShowAnalyticsResponse.HourlyTrafficPoint;
import com.nextturn.dto.dashboard.CancellationNoShowAnalyticsResponse.ServiceOutcomeBreakdown;
import com.nextturn.dto.dashboard.FeedbackTrendAnalyticsResponse;
import com.nextturn.dto.dashboard.FeedbackTrendAnalyticsResponse.CsatTrendPoint;
import com.nextturn.dto.dashboard.ServicePerformanceAnalyticsResponse;
import com.nextturn.dto.dashboard.ServicePerformanceAnalyticsResponse.CounterOvertimeAlert;
import com.nextturn.dto.dashboard.ServicePerformanceAnalyticsResponse.HourlyServeTrendPoint;
import com.nextturn.dto.dashboard.ServicePerformanceAnalyticsResponse.ServiceVarianceRow;
import com.nextturn.dto.dashboard.ServicePerformanceAnalyticsResponse.StaffLeaderboardRow;
import com.nextturn.dto.dashboard.TenantDashboardOverviewResponse;
import com.nextturn.dto.feedback.FeedbackSummaryResponse;
import com.nextturn.entity.TenantAppointment;
import com.nextturn.entity.TenantFeedback;
import com.nextturn.entity.TenantQueueTicket;
import com.nextturn.entity.TenantService;
import com.nextturn.entity.TenantStaff;
import com.nextturn.enums.AppointmentStatus;
import com.nextturn.enums.DashboardDatePreset;
import com.nextturn.enums.TicketCustomerType;
import com.nextturn.enums.TicketStatus;
import com.nextturn.infrastructure.logging.LogContract;
import com.nextturn.infrastructure.logging.LogDomain;
import com.nextturn.infrastructure.logging.LogOutcome;
import com.nextturn.repository.appointment.TenantAppointmentRepository;
import com.nextturn.repository.feedback.TenantFeedbackRepository;
import com.nextturn.repository.ticket.TenantQueueTicketRepository;
import com.nextturn.service.BaseService;
import com.nextturn.service.dashboard.TenantDashboardService;
import com.nextturn.service.dashboard.impl.DashboardSupport.DateRange;
import com.nextturn.service.feedback.TenantFeedbackAnalyticsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.WeekFields;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * Default implementation of {@link TenantDashboardService} fulfilling SRS REQ-DASHBOARD-01..04:
 * Valkey-cached real-time KPIs, no-show & cancellation breakdowns, T_standard variance +
 * 3-consecutive-ticket overtime alerts, and CSAT weekly/monthly trend series.
 */
@Service
public class TenantDashboardServiceImpl extends BaseService implements TenantDashboardService {

    private static final Logger log = LoggerFactory.getLogger(TenantDashboardServiceImpl.class);
    private static final Duration OVERVIEW_CACHE_TTL = Duration.ofSeconds(60);
    private static final double OVERTIME_MULTIPLIER = 1.5d;
    private static final int CONSECUTIVE_OVERTIME_THRESHOLD = 3;

    private final TenantQueueTicketRepository ticketRepository;
    private final TenantAppointmentRepository appointmentRepository;
    private final TenantFeedbackRepository feedbackRepository;
    private final TenantFeedbackAnalyticsService feedbackAnalyticsService;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @Autowired
    public TenantDashboardServiceImpl(
            TenantQueueTicketRepository ticketRepository,
            TenantAppointmentRepository appointmentRepository,
            TenantFeedbackRepository feedbackRepository,
            @Autowired(required = false) TenantFeedbackAnalyticsService feedbackAnalyticsService,
            @Autowired(required = false) StringRedisTemplate redisTemplate,
            @Autowired(required = false) ObjectMapper objectMapper
    ) {
        this(ticketRepository, appointmentRepository, feedbackRepository, feedbackAnalyticsService,
                redisTemplate, objectMapper, Clock.systemDefaultZone());
    }

    public TenantDashboardServiceImpl(
            TenantQueueTicketRepository ticketRepository,
            TenantAppointmentRepository appointmentRepository,
            TenantFeedbackRepository feedbackRepository,
            TenantFeedbackAnalyticsService feedbackAnalyticsService,
            StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper,
            Clock clock
    ) {
        this.ticketRepository = ticketRepository;
        this.appointmentRepository = appointmentRepository;
        this.feedbackRepository = feedbackRepository;
        this.feedbackAnalyticsService = feedbackAnalyticsService;
        this.redisTemplate = redisTemplate;
        this.objectMapper = (objectMapper != null ? objectMapper.copy() : new ObjectMapper())
                .findAndRegisterModules()
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        this.clock = clock != null ? clock : Clock.systemDefaultZone();
    }

    @Override
    @Transactional(readOnly = true)
    public TenantDashboardOverviewResponse getOverview() {
        String tenantCode = resolveCurrentTenantCode();
        LocalDate today = LocalDate.now(clock);

        TenantDashboardOverviewResponse cached = readCachedOverview(tenantCode, today);
        if (cached != null) {
            return cached;
        }

        List<TenantQueueTicket> tickets = ticketRepository.findByWorkDateBetweenWithDetails(today, today);
        List<TenantAppointment> appointments = appointmentRepository.findBySlotWorkDateBetweenWithDetails(today, today);
        List<TenantFeedback> feedbacks = feedbackRepository.findAllForSummary(
                null, null, null, null, today.atStartOfDay(), today.atTime(LocalTime.MAX));

        long totalTickets = tickets.size();
        long apptTickets = tickets.stream().filter(t -> t.getCustomerType() == TicketCustomerType.APPOINTMENT).count();
        long walkInTickets = totalTickets - apptTickets;
        long completedTickets = tickets.stream().filter(t -> t.getStatus() == TicketStatus.COMPLETED).count();
        long waitingTickets = tickets.stream().filter(t -> t.getStatus() == TicketStatus.WAITING).count();
        long servingTickets = tickets.stream().filter(t -> t.getStatus() == TicketStatus.SERVING).count();
        long noShowTickets = tickets.stream().filter(t -> t.getStatus() == TicketStatus.NO_SHOW).count();

        long totalAppointments = appointments.size();
        long cancelledAppointments = appointments.stream().filter(a -> a.getStatus() == AppointmentStatus.CANCELLED).count();

        double noShowRate = DashboardSupport.computePercentage(noShowTickets, totalTickets);
        double cancellationRate = DashboardSupport.computePercentage(cancelledAppointments, totalAppointments);
        long avgServeMinutes = computeAverageServeMinutes(tickets);

        double avgCsatRating = 0.0d;
        if (!feedbacks.isEmpty()) {
            double avg = feedbacks.stream()
                    .filter(f -> f.getRatingScore() != null)
                    .mapToInt(TenantFeedback::getRatingScore)
                    .average()
                    .orElse(0.0d);
            avgCsatRating = DashboardSupport.roundOneDecimal(avg);
        }

        TenantDashboardOverviewResponse response = TenantDashboardOverviewResponse.builder()
                .workDate(today)
                .generatedAt(LocalDateTime.now(clock))
                .totalTickets(totalTickets)
                .appointmentTickets(apptTickets)
                .walkInTickets(walkInTickets)
                .completedTickets(completedTickets)
                .waitingTickets(waitingTickets)
                .servingTickets(servingTickets)
                .noShowTickets(noShowTickets)
                .totalAppointments(totalAppointments)
                .cancelledAppointments(cancelledAppointments)
                .noShowRate(noShowRate)
                .cancellationRate(cancellationRate)
                .noShowSeverity(classifyNoShowSeverity(noShowRate))
                .avgServeMinutes(avgServeMinutes)
                .avgCsatRating(avgCsatRating)
                .totalFeedbacksToday(feedbacks.size())
                .build();

        writeCachedOverview(tenantCode, today, response);
        return response;
    }

    @Override
    @Transactional(readOnly = true)
    public CancellationNoShowAnalyticsResponse getCancellationAndNoShowAnalytics(
            DashboardDatePreset preset,
            LocalDate fromDate,
            LocalDate toDate,
            String serviceId,
            String staffId
    ) {
        resolveCurrentTenantCode();
        DateRange range = DashboardSupport.resolveDateRange(preset, fromDate, toDate, LocalDate.now(clock));
        String cleanService = DashboardSupport.normalizeId(serviceId);
        String cleanStaff = DashboardSupport.normalizeId(staffId);

        List<TenantQueueTicket> tickets = ticketRepository
                .findByWorkDateBetweenWithDetails(range.fromDate(), range.toDate()).stream()
                .filter(t -> DashboardSupport.matchesTicketFilters(t, cleanService, cleanStaff))
                .toList();

        List<TenantAppointment> appointments = appointmentRepository
                .findBySlotWorkDateBetweenWithDetails(range.fromDate(), range.toDate()).stream()
                .filter(a -> cleanService == null || (a.getService() != null && cleanService.equals(a.getService().getId())))
                .toList();

        long totalTickets = tickets.size();
        long completedTickets = tickets.stream().filter(t -> t.getStatus() == TicketStatus.COMPLETED).count();
        long noShowTickets = tickets.stream().filter(t -> t.getStatus() == TicketStatus.NO_SHOW).count();
        long apptTickets = tickets.stream().filter(t -> t.getCustomerType() == TicketCustomerType.APPOINTMENT).count();
        long walkInTickets = totalTickets - apptTickets;

        long totalAppointments = appointments.size();
        long cancelledAppointments = appointments.stream().filter(a -> a.getStatus() == AppointmentStatus.CANCELLED).count();

        return CancellationNoShowAnalyticsResponse.builder()
                .fromDate(range.fromDate())
                .toDate(range.toDate())
                .serviceId(cleanService)
                .staffId(cleanStaff)
                .totalTickets(totalTickets)
                .completedTickets(completedTickets)
                .noShowTickets(noShowTickets)
                .noShowRate(DashboardSupport.computePercentage(noShowTickets, totalTickets))
                .totalAppointments(totalAppointments)
                .cancelledAppointments(cancelledAppointments)
                .cancellationRate(DashboardSupport.computePercentage(cancelledAppointments, totalAppointments))
                .appointmentTickets(apptTickets)
                .walkInTickets(walkInTickets)
                .appointmentShareRate(DashboardSupport.computePercentage(apptTickets, totalTickets))
                .walkInShareRate(DashboardSupport.computePercentage(walkInTickets, totalTickets))
                .hourlyBreakdown(buildHourlyTrafficBreakdown(tickets))
                .serviceBreakdown(buildServiceOutcomeBreakdown(tickets, appointments))
                .build();
    }

    @Override
    @Transactional(readOnly = true)
    public ServicePerformanceAnalyticsResponse getServicePerformanceAnalytics(
            DashboardDatePreset preset,
            LocalDate fromDate,
            LocalDate toDate,
            String serviceId,
            String staffId
    ) {
        resolveCurrentTenantCode();
        DateRange range = DashboardSupport.resolveDateRange(preset, fromDate, toDate, LocalDate.now(clock));
        String cleanService = DashboardSupport.normalizeId(serviceId);
        String cleanStaff = DashboardSupport.normalizeId(staffId);

        List<TenantQueueTicket> allRangeTickets = ticketRepository
                .findByWorkDateBetweenWithDetails(range.fromDate(), range.toDate()).stream()
                .filter(t -> DashboardSupport.matchesTicketFilters(t, cleanService, cleanStaff))
                .toList();

        List<TenantQueueTicket> completedTickets = allRangeTickets.stream()
                .filter(t -> t.getStatus() == TicketStatus.COMPLETED)
                .toList();

        List<TenantFeedback> feedbacks = feedbackRepository.findAllForSummary(
                null,
                null,
                cleanService,
                cleanStaff,
                range.fromDate().atStartOfDay(),
                range.toDate().atTime(LocalTime.MAX)
        );

        return ServicePerformanceAnalyticsResponse.builder()
                .fromDate(range.fromDate())
                .toDate(range.toDate())
                .serviceId(cleanService)
                .staffId(cleanStaff)
                .totalCompletedServed(completedTickets.size())
                .overallAvgServeMinutes(computeAverageServeMinutes(completedTickets))
                .hourlyServeTrend(buildHourlyServeTrend(allRangeTickets))
                .serviceVariances(buildServiceVariances(completedTickets))
                .staffLeaderboard(buildStaffLeaderboard(completedTickets, feedbacks))
                .counterOvertimeAlerts(detectThreeConsecutiveOvertimeAlerts(completedTickets))
                .build();
    }

    @Override
    @Transactional(readOnly = true)
    public FeedbackTrendAnalyticsResponse getFeedbackTrends(
            DashboardDatePreset preset,
            LocalDate fromDate,
            LocalDate toDate,
            String serviceId,
            String providerId
    ) {
        resolveCurrentTenantCode();
        DateRange range = DashboardSupport.resolveDateRange(preset, fromDate, toDate, LocalDate.now(clock));
        String cleanService = DashboardSupport.normalizeId(serviceId);
        String cleanProvider = DashboardSupport.normalizeId(providerId);

        List<TenantFeedback> feedbacks = feedbackRepository.findAllForSummary(
                null,
                null,
                cleanService,
                cleanProvider,
                range.fromDate().atStartOfDay(),
                range.toDate().atTime(LocalTime.MAX)
        );

        FeedbackSummaryResponse overall = feedbackAnalyticsService != null
                ? feedbackAnalyticsService.computeSummary(feedbacks)
                : computeFallbackFeedbackSummary(feedbacks);

        return FeedbackTrendAnalyticsResponse.builder()
                .fromDate(range.fromDate())
                .toDate(range.toDate())
                .serviceId(cleanService)
                .providerId(cleanProvider)
                .totalFeedbacks(overall.getTotalFeedbacks())
                .averageRating(overall.getAverageRating())
                .csatPercentage(overall.getCsatPercentage())
                .negativeCount(overall.getNegativeCount())
                .ratingDistribution(overall.getRatingDistribution())
                .weeklyTrends(groupFeedbackTrends(feedbacks, true))
                .monthlyTrends(groupFeedbackTrends(feedbacks, false))
                .build();
    }

    // -------------------------------------------------------------------------
    // Aggregation & Detection Helpers
    // -------------------------------------------------------------------------

    private String classifyNoShowSeverity(double noShowRate) {
        if (noShowRate > 15.0d) {
            return "RED";
        }
        if (noShowRate >= 5.0d) {
            return "YELLOW";
        }
        return "GREEN";
    }

    private long computeAverageServeMinutes(List<TenantQueueTicket> tickets) {
        List<Long> serveSeconds = tickets.stream()
                .filter(t -> t.getStatus() == TicketStatus.COMPLETED)
                .filter(t -> t.getActualDuration() != null || (t.getServedAt() != null && t.getCompletedAt() != null))
                .map(DashboardSupport::resolveServeSeconds)
                .toList();
        if (serveSeconds.isEmpty()) {
            return 0L;
        }
        double avgSeconds = serveSeconds.stream().mapToLong(Long::longValue).average().orElse(0.0d);
        return Math.round(avgSeconds / 60.0d);
    }

    private List<HourlyTrafficPoint> buildHourlyTrafficBreakdown(List<TenantQueueTicket> tickets) {
        Map<Integer, List<TenantQueueTicket>> byHour = tickets.stream()
                .filter(t -> t.getIssuedAt() != null)
                .collect(Collectors.groupingBy(t -> t.getIssuedAt().getHour(), TreeMap::new, Collectors.toList()));

        List<HourlyTrafficPoint> points = new ArrayList<>();
        for (Map.Entry<Integer, List<TenantQueueTicket>> entry : byHour.entrySet()) {
            List<TenantQueueTicket> bucket = entry.getValue();
            long completed = bucket.stream().filter(t -> t.getStatus() == TicketStatus.COMPLETED).count();
            long noShow = bucket.stream().filter(t -> t.getStatus() == TicketStatus.NO_SHOW).count();
            double avgWait = bucket.stream()
                    .filter(t -> t.getServedAt() != null && t.getIssuedAt() != null)
                    .mapToLong(DashboardSupport::resolveWaitMinutes)
                    .average()
                    .orElse(0.0d);

            points.add(HourlyTrafficPoint.builder()
                    .hourSlot(entry.getKey())
                    .totalTickets(bucket.size())
                    .completedTickets(completed)
                    .noShowTickets(noShow)
                    .avgWaitMinutes(DashboardSupport.roundOneDecimal(avgWait))
                    .build());
        }
        return points;
    }

    private List<ServiceOutcomeBreakdown> buildServiceOutcomeBreakdown(
            List<TenantQueueTicket> tickets,
            List<TenantAppointment> appointments
    ) {
        Map<String, List<TenantQueueTicket>> ticketsByService = tickets.stream()
                .filter(t -> t.getService() != null)
                .collect(Collectors.groupingBy(t -> t.getService().getId(), LinkedHashMap::new, Collectors.toList()));

        Map<String, List<TenantAppointment>> apptsByService = appointments.stream()
                .filter(a -> a.getService() != null)
                .collect(Collectors.groupingBy(a -> a.getService().getId(), LinkedHashMap::new, Collectors.toList()));

        Map<String, TenantService> serviceMeta = new LinkedHashMap<>();
        tickets.stream().map(TenantQueueTicket::getService).filter(s -> s != null)
                .forEach(s -> serviceMeta.putIfAbsent(s.getId(), s));
        appointments.stream().map(TenantAppointment::getService).filter(s -> s != null)
                .forEach(s -> serviceMeta.putIfAbsent(s.getId(), s));

        List<ServiceOutcomeBreakdown> rows = new ArrayList<>();
        for (Map.Entry<String, TenantService> entry : serviceMeta.entrySet()) {
            String sid = entry.getKey();
            TenantService svc = entry.getValue();
            List<TenantQueueTicket> svcTickets = ticketsByService.getOrDefault(sid, List.of());
            List<TenantAppointment> svcAppts = apptsByService.getOrDefault(sid, List.of());

            long tTotal = svcTickets.size();
            long tCompleted = svcTickets.stream().filter(t -> t.getStatus() == TicketStatus.COMPLETED).count();
            long tNoShow = svcTickets.stream().filter(t -> t.getStatus() == TicketStatus.NO_SHOW).count();
            long aTotal = svcAppts.size();
            long aCancelled = svcAppts.stream().filter(a -> a.getStatus() == AppointmentStatus.CANCELLED).count();

            rows.add(ServiceOutcomeBreakdown.builder()
                    .serviceId(sid)
                    .serviceCode(svc.getServiceCode())
                    .serviceName(svc.getServiceName())
                    .totalTickets(tTotal)
                    .completedTickets(tCompleted)
                    .noShowTickets(tNoShow)
                    .noShowRate(DashboardSupport.computePercentage(tNoShow, tTotal))
                    .totalAppointments(aTotal)
                    .cancelledAppointments(aCancelled)
                    .cancellationRate(DashboardSupport.computePercentage(aCancelled, aTotal))
                    .build());
        }
        rows.sort(Comparator.comparingLong(ServiceOutcomeBreakdown::getTotalTickets).reversed());
        return rows;
    }

    private List<HourlyServeTrendPoint> buildHourlyServeTrend(List<TenantQueueTicket> tickets) {
        Map<Integer, List<TenantQueueTicket>> byHour = tickets.stream()
                .filter(t -> t.getIssuedAt() != null)
                .collect(Collectors.groupingBy(t -> t.getIssuedAt().getHour(), TreeMap::new, Collectors.toList()));

        List<HourlyServeTrendPoint> points = new ArrayList<>();
        for (Map.Entry<Integer, List<TenantQueueTicket>> entry : byHour.entrySet()) {
            List<TenantQueueTicket> bucket = entry.getValue();
            List<TenantQueueTicket> completed = bucket.stream()
                    .filter(t -> t.getStatus() == TicketStatus.COMPLETED)
                    .toList();
            long avgServe = computeAverageServeMinutes(completed);
            double avgWait = bucket.stream()
                    .filter(t -> t.getServedAt() != null && t.getIssuedAt() != null)
                    .mapToLong(DashboardSupport::resolveWaitMinutes)
                    .average()
                    .orElse(0.0d);

            points.add(HourlyServeTrendPoint.builder()
                    .hourSlot(entry.getKey())
                    .completedCount(completed.size())
                    .avgServeMinutes(avgServe)
                    .avgWaitMinutes(DashboardSupport.roundOneDecimal(avgWait))
                    .build());
        }
        return points;
    }

    private List<ServiceVarianceRow> buildServiceVariances(List<TenantQueueTicket> completedTickets) {
        Map<String, List<TenantQueueTicket>> byService = completedTickets.stream()
                .filter(t -> t.getService() != null)
                .collect(Collectors.groupingBy(t -> t.getService().getId(), LinkedHashMap::new, Collectors.toList()));

        List<ServiceVarianceRow> rows = new ArrayList<>();
        for (List<TenantQueueTicket> group : byService.values()) {
            TenantService svc = group.get(0).getService();
            int tStandard = svc.getStandardDuration() != null && svc.getStandardDuration() > 0
                    ? svc.getStandardDuration()
                    : 15;
            long thresholdSeconds = Math.round(OVERTIME_MULTIPLIER * tStandard * 60.0d);
            long avgServeMins = computeAverageServeMinutes(group);
            long overtimeCount = group.stream()
                    .filter(t -> DashboardSupport.resolveServeSeconds(t) > thresholdSeconds)
                    .count();

            rows.add(ServiceVarianceRow.builder()
                    .serviceId(svc.getId())
                    .serviceCode(svc.getServiceCode())
                    .serviceName(svc.getServiceName())
                    .standardDurationMinutes(tStandard)
                    .actualAvgServeMinutes(avgServeMins)
                    .varianceMinutes(avgServeMins - tStandard)
                    .servedCount(group.size())
                    .overtimeCount(overtimeCount)
                    .overtimeRate(DashboardSupport.computePercentage(overtimeCount, group.size()))
                    .build());
        }
        rows.sort(Comparator.comparingLong(ServiceVarianceRow::getServedCount).reversed());
        return rows;
    }

    private List<StaffLeaderboardRow> buildStaffLeaderboard(
            List<TenantQueueTicket> completedTickets,
            List<TenantFeedback> feedbacks
    ) {
        Map<String, List<TenantQueueTicket>> byProvider = completedTickets.stream()
                .filter(t -> t.getProvider() != null)
                .collect(Collectors.groupingBy(t -> t.getProvider().getId(), LinkedHashMap::new, Collectors.toList()));

        Map<String, List<TenantFeedback>> feedbacksByProvider = feedbacks.stream()
                .filter(f -> f.getProvider() != null)
                .collect(Collectors.groupingBy(f -> f.getProvider().getId(), LinkedHashMap::new, Collectors.toList()));

        Map<String, TenantStaff> staffMeta = new LinkedHashMap<>();
        completedTickets.stream().map(TenantQueueTicket::getProvider).filter(p -> p != null)
                .forEach(p -> staffMeta.putIfAbsent(p.getId(), p));
        feedbacks.stream().map(TenantFeedback::getProvider).filter(p -> p != null)
                .forEach(p -> staffMeta.putIfAbsent(p.getId(), p));

        List<StaffLeaderboardRow> rows = new ArrayList<>();
        for (Map.Entry<String, TenantStaff> entry : staffMeta.entrySet()) {
            String staffId = entry.getKey();
            TenantStaff staff = entry.getValue();
            List<TenantQueueTicket> staffTickets = byProvider.getOrDefault(staffId, List.of());
            List<TenantFeedback> staffFeedbacks = feedbacksByProvider.getOrDefault(staffId, List.of());

            long avgServe = computeAverageServeMinutes(staffTickets);
            long overtime = staffTickets.stream().filter(this::isTicketOvertime).count();
            long posFeedbacks = staffFeedbacks.stream()
                    .filter(f -> f.getRatingScore() != null && f.getRatingScore() >= 4)
                    .count();

            rows.add(StaffLeaderboardRow.builder()
                    .staffId(staffId)
                    .staffCode(staff.getStaffCode())
                    .staffName(staff.getFullName())
                    .servedCount(staffTickets.size())
                    .avgServeMinutes(avgServe)
                    .overtimeCount(overtime)
                    .totalFeedbackCount(staffFeedbacks.size())
                    .positiveFeedbackCount(posFeedbacks)
                    .positiveFeedbackRate(DashboardSupport.computePercentage(posFeedbacks, staffFeedbacks.size()))
                    .build());
        }
        rows.sort(Comparator.comparingLong(StaffLeaderboardRow::getServedCount).reversed()
                .thenComparingDouble(StaffLeaderboardRow::getPositiveFeedbackRate).reversed());
        return rows;
    }

    private List<CounterOvertimeAlert> detectThreeConsecutiveOvertimeAlerts(List<TenantQueueTicket> completedTickets) {
        Map<String, List<TenantQueueTicket>> byCounter = new LinkedHashMap<>();
        for (TenantQueueTicket ticket : completedTickets) {
            if (ticket.getServedAt() == null && ticket.getActualDuration() == null) {
                continue;
            }
            String counterKey = resolveCounterGroupKey(ticket);
            if (counterKey != null) {
                byCounter.computeIfAbsent(counterKey, k -> new ArrayList<>()).add(ticket);
            }
        }

        List<CounterOvertimeAlert> alerts = new ArrayList<>();
        for (Map.Entry<String, List<TenantQueueTicket>> entry : byCounter.entrySet()) {
            List<TenantQueueTicket> ordered = entry.getValue().stream()
                    .sorted(Comparator.comparing(
                            t -> t.getCompletedAt() != null ? t.getCompletedAt() : LocalDateTime.MIN,
                            Comparator.reverseOrder()))
                    .toList();

            int consecutiveStreak = 0;
            for (TenantQueueTicket ticket : ordered) {
                if (isTicketOvertime(ticket)) {
                    consecutiveStreak++;
                } else {
                    break;
                }
            }

            if (consecutiveStreak >= CONSECUTIVE_OVERTIME_THRESHOLD) {
                TenantQueueTicket latest = ordered.get(0);
                TenantService svc = latest.getService();
                int tStandard = svc != null && svc.getStandardDuration() != null && svc.getStandardDuration() > 0
                        ? svc.getStandardDuration()
                        : 15;
                long latestSec = DashboardSupport.resolveServeSeconds(latest);

                alerts.add(CounterOvertimeAlert.builder()
                        .counterKey(entry.getKey())
                        .counterLabel(latest.getCounterLabel())
                        .providerId(latest.getProvider() != null ? latest.getProvider().getId() : null)
                        .providerName(latest.getProvider() != null ? latest.getProvider().getFullName() : null)
                        .serviceName(svc != null ? svc.getServiceName() : null)
                        .consecutiveOvertimeCount(consecutiveStreak)
                        .standardDurationMinutes(tStandard)
                        .thresholdMinutes((int) Math.round(OVERTIME_MULTIPLIER * tStandard))
                        .lastTicketCode(latest.getTicketCode())
                        .lastActualMinutes((int) Math.round(latestSec / 60.0d))
                        .severity("YELLOW")
                        .build());
            }
        }
        return alerts;
    }

    private String resolveCounterGroupKey(TenantQueueTicket ticket) {
        if (ticket.getCounterLabel() != null && !ticket.getCounterLabel().isBlank()) {
            return "counter:" + ticket.getCounterLabel().trim();
        }
        if (ticket.getProvider() != null && ticket.getProvider().getId() != null) {
            return "provider:" + ticket.getProvider().getId();
        }
        return null;
    }

    private boolean isTicketOvertime(TenantQueueTicket ticket) {
        if (ticket == null || ticket.getService() == null) {
            return false;
        }
        int tStandard = ticket.getService().getStandardDuration() != null && ticket.getService().getStandardDuration() > 0
                ? ticket.getService().getStandardDuration()
                : 15;
        long thresholdSeconds = Math.round(OVERTIME_MULTIPLIER * tStandard * 60.0d);
        return DashboardSupport.resolveServeSeconds(ticket) > thresholdSeconds;
    }

    private List<CsatTrendPoint> groupFeedbackTrends(List<TenantFeedback> feedbacks, boolean weekly) {
        WeekFields isoWeek = WeekFields.ISO;
        Map<String, List<TenantFeedback>> grouped = new TreeMap<>();
        Map<String, LocalDate> periodStarts = new LinkedHashMap<>();

        for (TenantFeedback fb : feedbacks) {
            if (fb.getCreatedAt() == null || fb.getRatingScore() == null) {
                continue;
            }
            LocalDate date = fb.getCreatedAt().toLocalDate();
            String label;
            LocalDate start;
            if (weekly) {
                int weekYear = date.get(isoWeek.weekBasedYear());
                int weekNum = date.get(isoWeek.weekOfWeekBasedYear());
                label = String.format(Locale.ROOT, "%d-W%02d", weekYear, weekNum);
                start = date.with(isoWeek.dayOfWeek(), 1);
            } else {
                label = String.format(Locale.ROOT, "%d-%02d", date.getYear(), date.getMonthValue());
                start = date.withDayOfMonth(1);
            }
            grouped.computeIfAbsent(label, k -> new ArrayList<>()).add(fb);
            periodStarts.putIfAbsent(label, start);
        }

        List<CsatTrendPoint> points = new ArrayList<>();
        for (Map.Entry<String, List<TenantFeedback>> entry : grouped.entrySet()) {
            FeedbackSummaryResponse bucketSummary = feedbackAnalyticsService != null
                    ? feedbackAnalyticsService.computeSummary(entry.getValue())
                    : computeFallbackFeedbackSummary(entry.getValue());
            points.add(CsatTrendPoint.builder()
                    .periodLabel(entry.getKey())
                    .periodStart(periodStarts.get(entry.getKey()))
                    .totalFeedbacks(bucketSummary.getTotalFeedbacks())
                    .averageRating(bucketSummary.getAverageRating())
                    .csatPercentage(bucketSummary.getCsatPercentage())
                    .build());
        }
        return points;
    }

    private FeedbackSummaryResponse computeFallbackFeedbackSummary(List<TenantFeedback> feedbacks) {
        Map<Integer, Long> dist = new LinkedHashMap<>();
        for (int star = 1; star <= 5; star++) {
            dist.put(star, 0L);
        }
        long total = 0L;
        long sum = 0L;
        long pos = 0L;
        long neg = 0L;
        for (TenantFeedback fb : feedbacks) {
            Integer s = fb.getRatingScore();
            if (s == null || s < 1 || s > 5) {
                continue;
            }
            total++;
            sum += s;
            dist.put(s, dist.get(s) + 1L);
            if (s >= 4) {
                pos++;
            } else if (s <= 2) {
                neg++;
            }
        }
        return FeedbackSummaryResponse.builder()
                .totalFeedbacks(total)
                .averageRating(total > 0 ? DashboardSupport.roundOneDecimal((double) sum / total) : 0.0d)
                .csatPercentage(DashboardSupport.computePercentage(pos, total))
                .negativeCount(neg)
                .ratingDistribution(dist)
                .build();
    }

    private TenantDashboardOverviewResponse readCachedOverview(String tenantCode, LocalDate date) {
        if (redisTemplate == null) {
            return null;
        }
        try {
            String json = redisTemplate.opsForValue().get(ValkeyKeyConstant.dashboardOverviewKey(tenantCode, date));
            if (json != null && !json.isBlank()) {
                return objectMapper.readValue(json, TenantDashboardOverviewResponse.class);
            }
        } catch (Exception ex) {
            LogContract.warn(log, LogDomain.DASHBOARD, "tenant_overview_cache_read")
                    .outcome(LogOutcome.DEGRADED)
                    .tenant(tenantCode)
                    .cause(ex)
                    .emit();
        }
        return null;
    }

    private void writeCachedOverview(String tenantCode, LocalDate date, TenantDashboardOverviewResponse response) {
        if (redisTemplate == null || response == null) {
            return;
        }
        try {
            String json = objectMapper.writeValueAsString(response);
            redisTemplate.opsForValue().set(
                    ValkeyKeyConstant.dashboardOverviewKey(tenantCode, date), json, OVERVIEW_CACHE_TTL);
        } catch (Exception ex) {
            LogContract.warn(log, LogDomain.DASHBOARD, "tenant_overview_cache_write")
                    .outcome(LogOutcome.DEGRADED)
                    .tenant(tenantCode)
                    .cause(ex)
                    .emit();
        }
    }
}
