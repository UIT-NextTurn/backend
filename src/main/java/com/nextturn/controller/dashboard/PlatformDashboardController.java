package com.nextturn.controller.dashboard;

import com.nextturn.constant.DashboardEndpointConstant;
import com.nextturn.dto.BaseResponse;
import com.nextturn.dto.dashboard.DashboardStreamTokenResponse;
import com.nextturn.dto.dashboard.PlatformDashboardOverviewResponse;
import com.nextturn.dto.dashboard.TenantQuotaMonitoringResponse;
import com.nextturn.enums.QuotaMonitorFilter;
import com.nextturn.infrastructure.security.annotation.CurrentUser;
import com.nextturn.infrastructure.security.annotation.RequireSuperAdmin;
import com.nextturn.infrastructure.security.principal.AuthenticatedUser;
import com.nextturn.service.dashboard.DashboardSseBroadcaster;
import com.nextturn.service.dashboard.DashboardStreamTokenService;
import com.nextturn.service.dashboard.DashboardStreamTokenService.StreamScope;
import com.nextturn.service.dashboard.PlatformDashboardService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Superadmin central controller for platform-wide traffic monitoring and tenant quota alerts
 * (UI-05, REQ-DASHBOARD-05, REQ-DASHBOARD-06).
 */
@RestController
@RequestMapping(DashboardEndpointConstant.CENTRAL_DASHBOARD_BASE)
@RequireSuperAdmin
@Tag(name = "Central Platform Monitoring", description = "Superadmin platform overview metrics, real-time SSE stream, and tenant quota alerts (REQ-DASHBOARD-05, REQ-DASHBOARD-06)")
@SecurityRequirement(name = "bearerAuth")
public class PlatformDashboardController {

    private final PlatformDashboardService platformDashboardService;
    private final DashboardSseBroadcaster sseBroadcaster;
    private DashboardStreamTokenService streamTokenService;

    @Autowired
    public PlatformDashboardController(
            PlatformDashboardService platformDashboardService,
            @Autowired(required = false) DashboardSseBroadcaster sseBroadcaster
    ) {
        this.platformDashboardService = platformDashboardService;
        this.sseBroadcaster = sseBroadcaster;
    }

    @Autowired(required = false)
    public void setStreamTokenService(DashboardStreamTokenService streamTokenService) {
        this.streamTokenService = streamTokenService;
    }

    @GetMapping(DashboardEndpointConstant.OVERVIEW)
    @Operation(summary = "Giám sát chỉ số tổng quan toàn nền tảng và biểu đồ lưu lượng (REQ-DASHBOARD-05)")
    public ResponseEntity<BaseResponse<PlatformDashboardOverviewResponse>> getPlatformOverview(
            @RequestParam(defaultValue = "7") int days
    ) {
        PlatformDashboardOverviewResponse overview = platformDashboardService.getPlatformOverview(days);
        return ResponseEntity.ok(BaseResponse.ok("Tải dữ liệu giám sát toàn nền tảng thành công", overview));
    }

    @PostMapping(DashboardEndpointConstant.OVERVIEW_STREAM_TOKEN)
    @Operation(
            summary = "Cấp token ngắn hạn dùng một lần để mở luồng SSE giám sát toàn nền tảng",
            description = "Mints a 30-second single-use opaque streamToken bound to (userId, PLATFORM_DASHBOARD) so browser EventSource can connect without sending an Authorization header."
    )
    public ResponseEntity<BaseResponse<DashboardStreamTokenResponse>> createPlatformOverviewStreamToken(
            @CurrentUser AuthenticatedUser currentUser,
            Authentication authentication
    ) {
        DashboardStreamTokenResponse response = streamTokenService.issueToken(
                currentUser,
                authentication != null ? authentication.getAuthorities() : null,
                StreamScope.PLATFORM_DASHBOARD,
                null
        );
        return ResponseEntity.ok(BaseResponse.ok("Cấp token kết nối SSE toàn nền tảng thành công", response));
    }

    @GetMapping(value = DashboardEndpointConstant.OVERVIEW_STREAM, produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(
            summary = "Kênh SSE đồng bộ chỉ số giám sát toàn nền tảng (REQ-DASHBOARD-05)",
            description = "Supports either Authorization: Bearer <jwt> header or a short-lived single-use ?streamToken=... minted via POST /api/v1/central/dashboard/overview/stream-token."
    )
    public SseEmitter streamPlatformOverview() {
        if (sseBroadcaster == null) {
            SseEmitter emitter = new SseEmitter(0L);
            emitter.complete();
            return emitter;
        }
        return sseBroadcaster.subscribePlatformOverview();
    }

    @GetMapping(DashboardEndpointConstant.QUOTAS)
    @Operation(summary = "Giám sát tiêu thụ tài nguyên và cảnh báo hạn ngạch các tổ chức (REQ-DASHBOARD-06)")
    public ResponseEntity<BaseResponse<TenantQuotaMonitoringResponse>> getTenantQuotaMonitoring(
            @RequestParam(defaultValue = "ALL") QuotaMonitorFilter filter
    ) {
        TenantQuotaMonitoringResponse result = platformDashboardService.getTenantQuotaMonitoring(filter);
        return ResponseEntity.ok(BaseResponse.ok("Tải danh sách giám sát hạn ngạch tổ chức thành công", result));
    }
}
