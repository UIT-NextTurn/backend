package com.nextturn.controller.dashboard;

import com.nextturn.constant.DashboardEndpointConstant;
import com.nextturn.constant.TenantHeaderConstant;
import com.nextturn.controller.BaseController;
import com.nextturn.dto.BaseResponse;
import com.nextturn.dto.dashboard.CancellationNoShowAnalyticsResponse;
import com.nextturn.dto.dashboard.DashboardStreamTokenResponse;
import com.nextturn.dto.dashboard.FeedbackTrendAnalyticsResponse;
import com.nextturn.dto.dashboard.ServicePerformanceAnalyticsResponse;
import com.nextturn.dto.dashboard.TenantDashboardOverviewResponse;
import com.nextturn.enums.DashboardDatePreset;
import com.nextturn.enums.DashboardExportFormat;
import com.nextturn.infrastructure.security.annotation.CurrentUser;
import com.nextturn.infrastructure.security.annotation.RequireTenantAdmin;
import com.nextturn.infrastructure.security.principal.AuthenticatedUser;
import com.nextturn.service.dashboard.DashboardExportService;
import com.nextturn.service.dashboard.DashboardExportService.ExportedReportFile;
import com.nextturn.service.dashboard.DashboardSseBroadcaster;
import com.nextturn.service.dashboard.DashboardStreamTokenService;
import com.nextturn.service.dashboard.DashboardStreamTokenService.StreamScope;
import com.nextturn.service.dashboard.TenantDashboardService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.LocalDate;

/**
 * Authenticated Tenant Admin controller for Operations & Analytics Dashboard (UI-04, REQ-DASHBOARD-01..04).
 */
@RestController
@RequestMapping(DashboardEndpointConstant.TENANT_DASHBOARD_BASE)
@RequireTenantAdmin
@Tag(name = "Tenant Operations Dashboard", description = "Real-time tenant KPIs, cancellation/no-show analytics, staff performance & overtime alerts, CSAT trends, and CSV/XLSX exports (REQ-DASHBOARD-01..04)")
@SecurityRequirement(name = "bearerAuth")
public class TenantDashboardController extends BaseController {

    private final TenantDashboardService dashboardService;
    private final DashboardExportService exportService;
    private final DashboardSseBroadcaster sseBroadcaster;
    private DashboardStreamTokenService streamTokenService;

    @Autowired
    public TenantDashboardController(
            TenantDashboardService dashboardService,
            DashboardExportService exportService,
            @Autowired(required = false) DashboardSseBroadcaster sseBroadcaster
    ) {
        this.dashboardService = dashboardService;
        this.exportService = exportService;
        this.sseBroadcaster = sseBroadcaster;
    }

    @Autowired(required = false)
    public void setStreamTokenService(DashboardStreamTokenService streamTokenService) {
        this.streamTokenService = streamTokenService;
    }

    @GetMapping(DashboardEndpointConstant.OVERVIEW)
    @Operation(summary = "Trực quan hóa chỉ số tổng quan vận hành trong ngày (REQ-DASHBOARD-01)")
    public ResponseEntity<BaseResponse<TenantDashboardOverviewResponse>> getOverview(
            @RequestHeader(value = TenantHeaderConstant.X_TENANT_CODE_HEADER, required = false) String headerTenantCode
    ) {
        ensureTenantContext(headerTenantCode);
        TenantDashboardOverviewResponse overview = dashboardService.getOverview();
        return ResponseEntity.ok(BaseResponse.ok("Tải dữ liệu tổng quan vận hành thành công", overview));
    }

    @PostMapping(DashboardEndpointConstant.OVERVIEW_STREAM_TOKEN)
    @Operation(
            summary = "Cấp token ngắn hạn dùng một lần để mở luồng SSE Dashboard từ trình duyệt",
            description = "Mints a 30-second single-use opaque streamToken bound to (userId, tenantCode, TENANT_DASHBOARD) so browser EventSource can connect without sending an Authorization header."
    )
    public ResponseEntity<BaseResponse<DashboardStreamTokenResponse>> createOverviewStreamToken(
            @RequestHeader(value = TenantHeaderConstant.X_TENANT_CODE_HEADER, required = false) String headerTenantCode,
            @CurrentUser AuthenticatedUser currentUser,
            Authentication authentication
    ) {
        String tenantCode = ensureTenantContext(headerTenantCode);
        DashboardStreamTokenResponse response = streamTokenService.issueToken(
                currentUser,
                authentication != null ? authentication.getAuthorities() : null,
                StreamScope.TENANT_DASHBOARD,
                tenantCode
        );
        return ResponseEntity.ok(BaseResponse.ok("Cấp token kết nối SSE Dashboard thành công", response));
    }

    @GetMapping(value = DashboardEndpointConstant.OVERVIEW_STREAM, produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(
            summary = "Kênh SSE đồng bộ chỉ số Dashboard thời gian thực (REQ-DASHBOARD-01)",
            description = "Supports either Authorization: Bearer <jwt> header or a short-lived single-use ?streamToken=... minted via POST /api/v1/dashboard/overview/stream-token."
    )
    public SseEmitter streamOverview(
            @RequestHeader(value = TenantHeaderConstant.X_TENANT_CODE_HEADER, required = false) String headerTenantCode,
            @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId
    ) {
        String tenantCode = ensureTenantContext(headerTenantCode);
        if (sseBroadcaster == null) {
            SseEmitter emitter = new SseEmitter(0L);
            emitter.complete();
            return emitter;
        }
        return (lastEventId != null && !lastEventId.isBlank())
                ? sseBroadcaster.subscribeTenantOverview(tenantCode, lastEventId)
                : sseBroadcaster.subscribeTenantOverview(tenantCode);
    }

    @GetMapping(DashboardEndpointConstant.CANCELLATIONS_NOSHOWS)
    @Operation(summary = "Thống kê và phân tích tỷ lệ hủy lịch & khách vắng mặt (REQ-DASHBOARD-02)")
    public ResponseEntity<BaseResponse<CancellationNoShowAnalyticsResponse>> getCancellationAndNoShowAnalytics(
            @RequestHeader(value = TenantHeaderConstant.X_TENANT_CODE_HEADER, required = false) String headerTenantCode,
            @RequestParam(required = false) DashboardDatePreset preset,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate,
            @RequestParam(required = false) String serviceId,
            @RequestParam(required = false) String staffId
    ) {
        ensureTenantContext(headerTenantCode);
        CancellationNoShowAnalyticsResponse result = dashboardService.getCancellationAndNoShowAnalytics(
                preset, fromDate, toDate, serviceId, staffId);
        return ResponseEntity.ok(BaseResponse.ok("Thống kê tỷ lệ hủy và vắng mặt thành công", result));
    }

    @GetMapping(DashboardEndpointConstant.SERVICE_PERFORMANCE)
    @Operation(summary = "Thống kê thời gian phục vụ trung bình, hiệu suất nhân sự và cảnh báo vượt định mức (REQ-DASHBOARD-03)")
    public ResponseEntity<BaseResponse<ServicePerformanceAnalyticsResponse>> getServicePerformanceAnalytics(
            @RequestHeader(value = TenantHeaderConstant.X_TENANT_CODE_HEADER, required = false) String headerTenantCode,
            @RequestParam(required = false) DashboardDatePreset preset,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate,
            @RequestParam(required = false) String serviceId,
            @RequestParam(required = false) String staffId
    ) {
        ensureTenantContext(headerTenantCode);
        ServicePerformanceAnalyticsResponse result = dashboardService.getServicePerformanceAnalytics(
                preset, fromDate, toDate, serviceId, staffId);
        return ResponseEntity.ok(BaseResponse.ok("Thống kê hiệu suất dịch vụ và nhân sự thành công", result));
    }

    @GetMapping(DashboardEndpointConstant.FEEDBACK_TRENDS)
    @Operation(summary = "Báo cáo phân bổ sao và xu hướng điểm hài lòng CSAT (REQ-DASHBOARD-04)")
    public ResponseEntity<BaseResponse<FeedbackTrendAnalyticsResponse>> getFeedbackTrends(
            @RequestHeader(value = TenantHeaderConstant.X_TENANT_CODE_HEADER, required = false) String headerTenantCode,
            @RequestParam(required = false) DashboardDatePreset preset,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate,
            @RequestParam(required = false) String serviceId,
            @RequestParam(required = false) String staffId
    ) {
        ensureTenantContext(headerTenantCode);
        FeedbackTrendAnalyticsResponse result = dashboardService.getFeedbackTrends(
                preset, fromDate, toDate, serviceId, staffId);
        return ResponseEntity.ok(BaseResponse.ok("Thống kê xu hướng đánh giá hài lòng thành công", result));
    }

    @GetMapping(DashboardEndpointConstant.EXPORT)
    @Operation(summary = "Xuất báo cáo vận hành và đánh giá ra tệp CSV hoặc XLSX (REQ-DASHBOARD-04)")
    public ResponseEntity<byte[]> exportOperationalReport(
            @RequestHeader(value = TenantHeaderConstant.X_TENANT_CODE_HEADER, required = false) String headerTenantCode,
            @RequestParam(defaultValue = "CSV") DashboardExportFormat format,
            @RequestParam(required = false) DashboardDatePreset preset,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate,
            @RequestParam(required = false) String serviceId,
            @RequestParam(required = false) String staffId
    ) {
        ensureTenantContext(headerTenantCode);
        ExportedReportFile file = exportService.exportOperationalReport(
                format, preset, fromDate, toDate, serviceId, staffId);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + file.fileName() + "\"")
                .contentType(MediaType.parseMediaType(file.contentType()))
                .contentLength(file.bytes().length)
                .body(file.bytes());
    }
}
