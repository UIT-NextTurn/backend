package com.nextturn.service.dashboard;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nextturn.dto.dashboard.CancellationNoShowAnalyticsResponse;
import com.nextturn.dto.dashboard.FeedbackTrendAnalyticsResponse;
import com.nextturn.dto.dashboard.ServicePerformanceAnalyticsResponse;
import com.nextturn.dto.dashboard.TenantDashboardOverviewResponse;
import com.nextturn.entity.TenantAppointment;
import com.nextturn.entity.TenantFeedback;
import com.nextturn.entity.TenantQueueTicket;
import com.nextturn.entity.TenantService;
import com.nextturn.entity.TenantStaff;
import com.nextturn.entity.TenantTimeSlot;
import com.nextturn.enums.AppointmentStatus;
import com.nextturn.enums.DashboardDatePreset;
import com.nextturn.enums.DashboardExportFormat;
import com.nextturn.enums.TicketCustomerType;
import com.nextturn.enums.TicketStatus;
import com.nextturn.exception.dashboard.DashboardErrorCode;
import com.nextturn.exception.dashboard.DashboardException;
import com.nextturn.infrastructure.multitenancy.TenantContextHolder;
import com.nextturn.repository.appointment.TenantAppointmentRepository;
import com.nextturn.repository.feedback.TenantFeedbackRepository;
import com.nextturn.repository.ticket.TenantQueueTicketRepository;
import com.nextturn.service.dashboard.DashboardExportService.ExportedReportFile;
import com.nextturn.service.dashboard.impl.DashboardExportServiceImpl;
import com.nextturn.service.dashboard.impl.TenantDashboardServiceImpl;
import com.nextturn.service.feedback.impl.TenantFeedbackAnalyticsServiceImpl;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TenantDashboardServiceTest {

    private static final String TENANT_CODE = "clinic-alpha";
    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2026-11-23T08:00:00Z"), ZoneOffset.UTC);

    @Mock
    private TenantQueueTicketRepository ticketRepository;
    @Mock
    private TenantAppointmentRepository appointmentRepository;
    @Mock
    private TenantFeedbackRepository feedbackRepository;
    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private ValueOperations<String, String> valueOperations;

    private TenantDashboardServiceImpl dashboardService;
    private DashboardExportServiceImpl exportService;

    @BeforeEach
    void setUp() {
        TenantContextHolder.setTenantCode(TENANT_CODE);
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        TenantFeedbackAnalyticsServiceImpl feedbackAnalytics =
                new TenantFeedbackAnalyticsServiceImpl(null, null, new ObjectMapper().findAndRegisterModules());
        dashboardService = new TenantDashboardServiceImpl(
                ticketRepository,
                appointmentRepository,
                feedbackRepository,
                feedbackAnalytics,
                redisTemplate,
                new ObjectMapper().findAndRegisterModules(),
                FIXED_CLOCK
        );
        exportService = new DashboardExportServiceImpl(ticketRepository, feedbackRepository, FIXED_CLOCK);
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
    }

    @Test
    @DisplayName("REQ-DASHBOARD-01: Computes real-time KPI overview, no-show severity, and CSAT")
    void getOverview_ComputesAllDailyKpisAccurately() {
        LocalDate today = LocalDate.now(FIXED_CLOCK);
        TenantService service = TenantService.builder().id("srv-1").serviceCode("SVC-0001").serviceName("Khám Nội").standardDuration(10).build();
        TenantStaff staff = TenantStaff.builder().id("stf-1").staffCode("STAFF-0001").fullName("BS. An").build();

        List<TenantQueueTicket> tickets = List.of(
                ticket("t1", "A-001", today, service, staff, "Quầy 1", TicketCustomerType.APPOINTMENT, TicketStatus.COMPLETED, 8, 600),
                ticket("t2", "A-002", today, service, staff, "Quầy 1", TicketCustomerType.WALK_IN, TicketStatus.WAITING, 10, null),
                ticket("t3", "A-003", today, service, staff, "Quầy 1", TicketCustomerType.WALK_IN, TicketStatus.SERVING, 12, null),
                ticket("t4", "A-004", today, service, staff, "Quầy 1", TicketCustomerType.APPOINTMENT, TicketStatus.NO_SHOW, 5, null)
        );
        List<TenantAppointment> appts = List.of(
                appointment("a1", today, service, AppointmentStatus.CONFIRMED),
                appointment("a2", today, service, AppointmentStatus.CANCELLED)
        );
        List<TenantFeedback> feedbacks = List.of(
                TenantFeedback.builder().id("fb1").ratingScore(5).createdAt(today.atTime(10, 0)).build(),
                TenantFeedback.builder().id("fb2").ratingScore(4).createdAt(today.atTime(11, 0)).build()
        );

        when(valueOperations.get(anyString())).thenReturn(null);
        when(ticketRepository.findByWorkDateBetweenWithDetails(today, today)).thenReturn(tickets);
        when(appointmentRepository.findBySlotWorkDateBetweenWithDetails(today, today)).thenReturn(appts);
        when(feedbackRepository.findAllForSummary(any(), any(), any(), any(), any(), any())).thenReturn(feedbacks);

        TenantDashboardOverviewResponse overview = dashboardService.getOverview();

        assertThat(overview.getTotalTickets()).isEqualTo(4L);
        assertThat(overview.getAppointmentTickets()).isEqualTo(2L);
        assertThat(overview.getWalkInTickets()).isEqualTo(2L);
        assertThat(overview.getCompletedTickets()).isEqualTo(1L);
        assertThat(overview.getWaitingTickets()).isEqualTo(1L);
        assertThat(overview.getServingTickets()).isEqualTo(1L);
        assertThat(overview.getNoShowTickets()).isEqualTo(1L);
        assertThat(overview.getNoShowRate()).isEqualTo(25.0d);
        assertThat(overview.getNoShowSeverity()).isEqualTo("RED");
        assertThat(overview.getCancellationRate()).isEqualTo(50.0d);
        assertThat(overview.getAvgServeMinutes()).isEqualTo(10L);
        assertThat(overview.getAvgCsatRating()).isEqualTo(4.5d);
    }

    @Test
    @DisplayName("REQ-DASHBOARD-02: Validates invalid custom date range and builds hourly/service breakdowns")
    void getCancellationAndNoShowAnalytics_ValidatesDateAndComputesBreakdown() {
        LocalDate today = LocalDate.now(FIXED_CLOCK);
        assertThatThrownBy(() -> dashboardService.getCancellationAndNoShowAnalytics(
                DashboardDatePreset.CUSTOM, today, today.minusDays(2), null, null))
                .isInstanceOf(DashboardException.class)
                .satisfies(ex -> assertThat(((DashboardException) ex).getCode())
                        .isEqualTo(DashboardErrorCode.INVALID_DATE_RANGE.code()));

        TenantService svc = TenantService.builder().id("srv-1").serviceCode("SVC-0001").serviceName("Xét nghiệm").standardDuration(10).build();
        when(ticketRepository.findByWorkDateBetweenWithDetails(today.minusDays(6), today)).thenReturn(List.of(
                ticket("t1", "B-001", today, svc, null, "Quầy 1", TicketCustomerType.WALK_IN, TicketStatus.NO_SHOW, 5, null),
                ticket("t2", "B-002", today, svc, null, "Quầy 1", TicketCustomerType.APPOINTMENT, TicketStatus.COMPLETED, 8, 540)
        ));
        when(appointmentRepository.findBySlotWorkDateBetweenWithDetails(today.minusDays(6), today)).thenReturn(List.of(
                appointment("a1", today, svc, AppointmentStatus.CANCELLED),
                appointment("a2", today, svc, AppointmentStatus.CHECKED_IN)
        ));

        CancellationNoShowAnalyticsResponse res = dashboardService.getCancellationAndNoShowAnalytics(
                DashboardDatePreset.LAST_7_DAYS, null, null, null, null);

        assertThat(res.getTotalTickets()).isEqualTo(2L);
        assertThat(res.getNoShowRate()).isEqualTo(50.0d);
        assertThat(res.getCancellationRate()).isEqualTo(50.0d);
        assertThat(res.getAppointmentShareRate()).isEqualTo(50.0d);
        assertThat(res.getServiceBreakdown()).hasSize(1);
        assertThat(res.getHourlyBreakdown()).hasSize(2);
        assertThat(res.getHourlyBreakdown().get(0).getHourSlot()).isEqualTo(5);
        assertThat(res.getHourlyBreakdown().get(1).getHourSlot()).isEqualTo(8);
    }

    @Test
    @DisplayName("REQ-DASHBOARD-03: Triggers YELLOW alert only when 3 trailing completed tickets exceed 1.5 * T_standard")
    void getServicePerformanceAnalytics_DetectsThreeConsecutiveOvertimeAlerts() {
        LocalDate today = LocalDate.now(FIXED_CLOCK);
        TenantService svc = TenantService.builder().id("srv-1").serviceCode("SVC-0001").serviceName("Khám Tổng quát").standardDuration(10).build();
        TenantStaff fastStaff = TenantStaff.builder().id("stf-fast").staffCode("STAFF-0001").fullName("BS. Nhanh").build();
        TenantStaff slowStaff = TenantStaff.builder().id("stf-slow").staffCode("STAFF-0002").fullName("BS. Chậm").build();

        // Threshold = 1.5 * 10 min = 15 min (900s)
        // Quầy 1 has 3 consecutive > 900s; Quầy 2 had 3 slow THEN 1 fast (should NOT alert)
        List<TenantQueueTicket> tickets = List.of(
                completedAt("s1", today, svc, slowStaff, "Quầy 1", 10, 1000),
                completedAt("s2", today, svc, slowStaff, "Quầy 1", 11, 1100),
                completedAt("s3", today, svc, slowStaff, "Quầy 1", 12, 1200),
                completedAt("f1", today, svc, fastStaff, "Quầy 2", 9, 1000),
                completedAt("f2", today, svc, fastStaff, "Quầy 2", 10, 1000),
                completedAt("f3", today, svc, fastStaff, "Quầy 2", 11, 1000),
                completedAt("f4", today, svc, fastStaff, "Quầy 2", 12, 480)
        );

        when(ticketRepository.findByWorkDateBetweenWithDetails(today, today)).thenReturn(tickets);
        when(feedbackRepository.findAllForSummary(any(), any(), any(), any(), any(), any())).thenReturn(List.of());

        ServicePerformanceAnalyticsResponse perf = dashboardService.getServicePerformanceAnalytics(
                DashboardDatePreset.TODAY, null, null, null, null);

        assertThat(perf.getCounterOvertimeAlerts()).hasSize(1);
        assertThat(perf.getCounterOvertimeAlerts().get(0).getCounterLabel()).isEqualTo("Quầy 1");
        assertThat(perf.getCounterOvertimeAlerts().get(0).getConsecutiveOvertimeCount()).isEqualTo(3);
        assertThat(perf.getCounterOvertimeAlerts().get(0).getSeverity()).isEqualTo("YELLOW");
        assertThat(perf.getStaffLeaderboard()).hasSize(2);
    }

    @Test
    @DisplayName("REQ-DASHBOARD-04: Groups CSAT weekly/monthly trends and generates CSV (formula-safe) + valid XLSX exports")
    void feedbackTrendsAndExports_RenderValidCsvAndXlsx() throws Exception {
        LocalDate today = LocalDate.now(FIXED_CLOCK);
        TenantService svc = TenantService.builder().id("srv-1").serviceCode("SVC-0001").serviceName("Nha khoa").standardDuration(15).build();
        TenantStaff staff = TenantStaff.builder().id("stf-1").staffCode("STAFF-0001").fullName("BS. Nam").build();
        TenantQueueTicket t1 = ticket("t1", "C-001", today, svc, staff, "Quầy 1", TicketCustomerType.WALK_IN, TicketStatus.COMPLETED, 9, 900);
        TenantFeedback fb1 = TenantFeedback.builder()
                .id("fb1")
                .ticket(t1)
                .service(svc)
                .provider(staff)
                .ratingScore(5)
                .comment("=CMD('calc') Dịch vụ rất tốt")
                .createdAt(today.atTime(10, 15))
                .build();

        when(feedbackRepository.findAllForSummary(any(), any(), any(), any(), any(), any())).thenReturn(List.of(fb1));
        when(ticketRepository.findByWorkDateBetweenWithDetails(today, today)).thenReturn(List.of(t1));

        FeedbackTrendAnalyticsResponse trends = dashboardService.getFeedbackTrends(
                DashboardDatePreset.TODAY, null, null, null, null);
        assertThat(trends.getTotalFeedbacks()).isEqualTo(1L);
        assertThat(trends.getWeeklyTrends()).hasSize(1);
        assertThat(trends.getMonthlyTrends()).hasSize(1);

        ExportedReportFile csvFile = exportService.exportOperationalReport(
                DashboardExportFormat.CSV, DashboardDatePreset.TODAY, null, null, null, null);
        String csvText = new String(csvFile.bytes(), StandardCharsets.UTF_8);
        assertThat(csvFile.fileName()).endsWith(".csv");
        assertThat(csvText).contains("C-001").contains("'=CMD('calc')");

        ExportedReportFile xlsxFile = exportService.exportOperationalReport(
                DashboardExportFormat.XLSX, DashboardDatePreset.TODAY, null, null, null, null);
        assertThat(xlsxFile.fileName()).endsWith(".xlsx");
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(xlsxFile.bytes()))) {
            assertThat(wb.getSheetAt(0).getRow(0).getCell(1).getStringCellValue()).isEqualTo("Ticket Code");
            assertThat(wb.getSheetAt(0).getRow(1).getCell(1).getStringCellValue()).isEqualTo("C-001");
        }
    }

    private TenantQueueTicket ticket(
            String id, String code, LocalDate date, TenantService svc, TenantStaff provider,
            String counterLabel, TicketCustomerType type, TicketStatus status, int issuedHour, Integer durationSec
    ) {
        LocalDateTime issued = date.atTime(issuedHour, 0);
        return TenantQueueTicket.builder()
                .id(id)
                .ticketCode(code)
                .workDate(date)
                .service(svc)
                .provider(provider)
                .counterLabel(counterLabel)
                .customerName("Khách " + code)
                .customerPhone("0901234567")
                .customerType(type)
                .status(status)
                .issuedAt(issued)
                .servedAt(durationSec != null ? issued.plusMinutes(5) : null)
                .completedAt(durationSec != null ? issued.plusMinutes(5).plusSeconds(durationSec) : null)
                .actualDuration(durationSec)
                .autoCompleted(false)
                .build();
    }

    private TenantQueueTicket completedAt(
            String id, LocalDate date, TenantService svc, TenantStaff provider, String counter, int hour, int durationSec
    ) {
        return ticket(id, id.toUpperCase(), date, svc, provider, counter, TicketCustomerType.WALK_IN, TicketStatus.COMPLETED, hour, durationSec);
    }

    private TenantAppointment appointment(String id, LocalDate date, TenantService svc, AppointmentStatus status) {
        return TenantAppointment.builder()
                .id(id)
                .appointmentCode("APT-" + id)
                .service(svc)
                .slot(TenantTimeSlot.builder().id("slot-" + id).workDate(date).build())
                .status(status)
                .build();
    }
}
