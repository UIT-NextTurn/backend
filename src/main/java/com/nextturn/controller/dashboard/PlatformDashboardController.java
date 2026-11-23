package com.nextturn.controller.dashboard;

import com.nextturn.constant.DashboardEndpointConstant;
import com.nextturn.dto.BaseResponse;
import com.nextturn.dto.dashboard.PlatformDashboardOverviewResponse;
import com.nextturn.dto.dashboard.TenantQuotaMonitoringResponse;
import com.nextturn.enums.QuotaMonitorFilter;
import com.nextturn.infrastructure.security.annotation.RequireSuperAdmin;
import com.nextturn.service.dashboard.DashboardSseBroadcaster;
import com.nextturn.service.dashboard.PlatformDashboardService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
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

    @Autowired
    public PlatformDashboardController(
            PlatformDashboardService platformDashboardService,
            @Autowired(required = false) DashboardSseBroadcaster sseBroadcaster
    ) {
        this.platformDashboardService = platformDashboardService;
        this.sseBroadcaster = sseBroadcaster;
    }

    @GetMapping(DashboardEndpointConstant.OVERVIEW)
    @Operation(summary = "Giám sát chỉ số tổng quan toàn nền tảng và biểu đồ lưu lượng (REQ-DASHBOARD-05)")
    public ResponseEntity<BaseResponse<PlatformDashboardOverviewResponse>> getPlatformOverview(
            @RequestParam(defaultValue = "7") int days
    ) {
        PlatformDashboardOverviewResponse overview = platformDashboardService.getPlatformOverview(days);
        return ResponseEntity.ok(BaseResponse.ok("Tải dữ liệu giám sát toàn nền tảng thành công", overview));
    }

    @GetMapping(value = DashboardEndpointConstant.OVERVIEW_STREAM, produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "Kênh SSE đồng bộ chỉ số giám sát toàn nền tảng (REQ-DASHBOARD-05)")
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
