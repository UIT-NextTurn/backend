package com.nextturn.controller.tenant;

import com.nextturn.dto.BaseResponse;
import com.nextturn.dto.tenant.CreateTenantRequest;
import com.nextturn.dto.tenant.RejectTenantRequest;
import com.nextturn.dto.tenant.TenantMonitoringResponse;
import com.nextturn.dto.tenant.TenantQuotaDto;
import com.nextturn.dto.tenant.TenantQuotaResponse;
import com.nextturn.dto.tenant.TenantResponse;
import com.nextturn.dto.tenant.UpdateTenantRequest;
import com.nextturn.dto.tenant.UpdateTenantStatusRequest;
import com.nextturn.enums.TenantStatus;
import com.nextturn.infrastructure.security.annotation.RequireSuperAdmin;
import com.nextturn.service.tenant.TenantService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * REST controller for tenant onboarding, administration, operational quota configuration,
 * lifecycle state transitions (REQ-TNT-03), and global search/monitoring (REQ-TNT-04).
 * <p>
 * Strictly restricted to platform <b>SUPERADMIN</b> only:
 * - <b>REQ-TNT-01</b>: Provisioning new tenant organization & default administrator account.
 * - <b>REQ-TNT-02</b>: Updating tenant administrative info & configuring operational quotas.
 * - <b>REQ-TNT-03</b>: Lifecycle management (suspending/reactivating organization and hard-delete prevention).
 * - <b>REQ-TNT-04</b>: Multi-criteria lookup, pagination, and telemetry monitoring (<= 300ms latency).
 */
@RestController
@RequestMapping("/api/v1/central/tenants")
@Validated
@RequireSuperAdmin
@Tag(
        name = "Tenant Management",
        description = "Endpoints for platform Superadministrators to manage tenant lifecycle, operational quotas, and global telemetry (REQ-TNT-01 to REQ-TNT-04)"
)
@SecurityRequirement(name = "BearerAuth")
public class TenantManagementController {

    private final TenantService tenantService;

    @Autowired
    public TenantManagementController(TenantService tenantService) {
        this.tenantService = tenantService;
    }

    /**
     * Searches, filters with pagination, and provides operational monitoring metrics (REQ-TNT-04).
     */
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    @RequireSuperAdmin
    @Operation(
            summary = "Tra cứu, phân trang và giám sát toàn cục (REQ-TNT-04)",
            description = "Tìm kiếm và lọc danh sách các tổ chức đa tiêu chí (keyword, status) kèm metadata phân trang và chỉ số giám sát tài nguyên (số nhân viên, số vé hôm nay)."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Truy vấn danh sách tổ chức thành công"),
            @ApiResponse(responseCode = "403", description = "Quyền truy cập bị từ chối - chỉ chấp nhận SUPERADMIN")
    })
    public ResponseEntity<BaseResponse<List<TenantMonitoringResponse>>> searchTenants(
            @RequestParam(value = "keyword", required = false) String keyword,
            @RequestParam(value = "status", required = false) TenantStatus status,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "limit", defaultValue = "10") int limit,
            @RequestParam(value = "sortBy", defaultValue = "createdAt") String sortBy,
            @RequestParam(value = "sortDirection", defaultValue = "DESC") String sortDirection
    ) {
        Page<TenantMonitoringResponse> pagedResult = tenantService.searchTenants(
                keyword, status, page, limit, sortBy, sortDirection
        );
        return ResponseEntity.ok(BaseResponse.okPaged(
                "Truy vấn danh sách tổ chức thành công",
                pagedResult
        ));
    }

    /**
     * Provisions a new tenant facility and default administrator account (REQ-TNT-01).
     */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @RequireSuperAdmin
    @Operation(
            summary = "Khởi tạo và cấp phát tổ chức mới (REQ-TNT-01)",
            description = "Superadministrator khai báo tổ chức mới, cấp tài khoản Keycloak cho Admin cơ sở, gán hạn ngạch mặc định và gửi email kích hoạt."
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "201",
                    description = "Khởi tạo tổ chức thành công",
                    content = @Content(schema = @Schema(implementation = BaseResponse.class))
            ),
            @ApiResponse(responseCode = "400", description = "Dữ liệu đầu vào không hợp lệ"),
            @ApiResponse(responseCode = "403", description = "Quyền truy cập bị từ chối - chỉ chấp nhận SUPERADMIN"),
            @ApiResponse(responseCode = "409", description = "Mã định danh tổ chức hoặc email quản trị đã tồn tại"),
            @ApiResponse(responseCode = "500", description = "Đồng bộ tài khoản sang Keycloak thất bại (đã rollback)")
    })
    public ResponseEntity<BaseResponse<TenantResponse>> createTenant(
            @Valid @RequestBody CreateTenantRequest request
    ) {
        TenantResponse response = tenantService.createTenant(request);
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(BaseResponse.created(
                        "Tổ chức đã được khởi tạo thành công",
                        response
                ));
    }

    /**
     * Updates tenant administrative information and operational capacity quotas (REQ-TNT-02).
     */
    @PutMapping(value = "/{tenantId}", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @RequireSuperAdmin
    @Operation(
            summary = "Cập nhật thông tin và cấu hình hạn ngạch vận hành (REQ-TNT-02)",
            description = "Superadministrator điều chỉnh thông tin hành chính, hạn ngạch số lượng nhân viên tối đa, hạn ngạch vé trong ngày và cuộc hẹn tương lai. Cảnh báo/từ chối nếu nhân sự thực tế vượt quá hạn mức mới."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Cập nhật thành công"),
            @ApiResponse(responseCode = "400", description = "Hạn ngạch nhân sự mới nhỏ hơn số lượng nhân viên hiện có, hoặc dữ liệu không hợp lệ"),
            @ApiResponse(responseCode = "403", description = "Quyền truy cập bị từ chối - chỉ chấp nhận SUPERADMIN"),
            @ApiResponse(responseCode = "404", description = "Không tìm thấy tổ chức")
    })
    public ResponseEntity<BaseResponse<TenantResponse>> updateTenant(
            @PathVariable("tenantId") String tenantId,
            @Valid @RequestBody UpdateTenantRequest request
    ) {
        TenantResponse response = tenantService.updateTenant(tenantId, request);
        return ResponseEntity.ok(BaseResponse.ok(
                "Thông tin và hạn ngạch vận hành của tổ chức đã được cập nhật thành công",
                response
        ));
    }

    /**
     * Updates operational capacity quotas separately (REQ-TNT-02).
     */
    @PutMapping(value = "/{tenantId}/quotas", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @RequireSuperAdmin
    @Operation(
            summary = "Cập nhật hạn ngạch vận hành (REQ-TNT-02)",
            description = "Superadministrator điều chỉnh riêng các hạn ngạch: số lượng nhân viên tối đa, vé trong ngày, dịch vụ và cuộc hẹn."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Cập nhật hạn ngạch thành công"),
            @ApiResponse(responseCode = "400", description = "Hạn ngạch nhân sự mới nhỏ hơn số lượng nhân viên hiện có"),
            @ApiResponse(responseCode = "403", description = "Quyền truy cập bị từ chối - chỉ chấp nhận SUPERADMIN"),
            @ApiResponse(responseCode = "404", description = "Không tìm thấy tổ chức")
    })
    public ResponseEntity<BaseResponse<TenantResponse>> updateTenantQuotas(
            @PathVariable("tenantId") String tenantId,
            @Valid @RequestBody TenantQuotaDto quotaDto
    ) {
        UpdateTenantRequest updateRequest = UpdateTenantRequest.builder()
                .maxStaffs(quotaDto.getMaxStaffs())
                .dailyTicketQuota(quotaDto.getDailyTicketQuota())
                .maxServices(quotaDto.getMaxServices())
                .maxAppointments(quotaDto.getMaxAppointments())
                .build();
        TenantResponse response = tenantService.updateTenant(tenantId, updateRequest);
        return ResponseEntity.ok(BaseResponse.ok(
                "Hạn ngạch vận hành của tổ chức đã được cập nhật thành công",
                response
        ));
    }

    /**
     * Suspends organization operations and disables staff credentials in Keycloak (REQ-TNT-03).
     */
    @PostMapping(value = "/{tenantId}/suspend", produces = MediaType.APPLICATION_JSON_VALUE)
    @RequireSuperAdmin
    @Operation(
            summary = "Tạm ngừng tổ chức (REQ-TNT-03)",
            description = "Chuyển trạng thái sang SUSPENDED, tạm khóa toàn bộ tài khoản nhân sự và thu hồi phiên đăng nhập Keycloak, tạm dừng cổng tiếp nhận lịch hẹn."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Tạm ngừng tổ chức thành công"),
            @ApiResponse(responseCode = "400", description = "Tổ chức đã ở trạng thái SUSPENDED"),
            @ApiResponse(responseCode = "403", description = "Quyền truy cập bị từ chối - chỉ chấp nhận SUPERADMIN"),
            @ApiResponse(responseCode = "404", description = "Không tìm thấy tổ chức")
    })
    public ResponseEntity<BaseResponse<TenantResponse>> suspendTenant(
            @PathVariable("tenantId") String tenantId
    ) {
        TenantResponse response = tenantService.suspendTenant(tenantId);
        return ResponseEntity.ok(BaseResponse.ok(
                "Tổ chức đã được tạm ngừng hoạt động thành công",
                response
        ));
    }

    /**
     * Reactivates a suspended organization and restores Keycloak login access (REQ-TNT-03).
     */
    @PostMapping(value = "/{tenantId}/activate", produces = MediaType.APPLICATION_JSON_VALUE)
    @RequireSuperAdmin
    @Operation(
            summary = "Kích hoạt lại tổ chức (REQ-TNT-03)",
            description = "Chuyển trạng thái sang ACTIVE, khôi phục quyền đăng nhập Keycloak cho nhân sự và mở lại dịch vụ trực tuyến."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Kích hoạt lại tổ chức thành công"),
            @ApiResponse(responseCode = "400", description = "Tổ chức đã ở trạng thái ACTIVE"),
            @ApiResponse(responseCode = "403", description = "Quyền truy cập bị từ chối - chỉ chấp nhận SUPERADMIN"),
            @ApiResponse(responseCode = "404", description = "Không tìm thấy tổ chức")
    })
    public ResponseEntity<BaseResponse<TenantResponse>> activateTenant(
            @PathVariable("tenantId") String tenantId
    ) {
        TenantResponse response = tenantService.activateTenant(tenantId);
        return ResponseEntity.ok(BaseResponse.ok(
                "Tổ chức đã được kích hoạt lại thành công",
                response
        ));
    }

    /**
     * Approves a pending tenant registration application (REQ-TNT-06).
     */
    @PostMapping(value = "/{tenantId}/approve", produces = MediaType.APPLICATION_JSON_VALUE)
    @RequireSuperAdmin
    @Operation(
            summary = "Phê duyệt yêu cầu đăng ký tổ chức (REQ-TNT-06)",
            description = "Chuyển trạng thái từ PENDING_APPROVAL sang ACTIVE, kích hoạt tài khoản Admin trên Keycloak và gửi email thông báo chúc mừng kèm hướng dẫn đăng nhập."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Phê duyệt tổ chức thành công"),
            @ApiResponse(responseCode = "400", description = "Tổ chức không ở trạng thái PENDING_APPROVAL"),
            @ApiResponse(responseCode = "403", description = "Quyền truy cập bị từ chối - chỉ chấp nhận SUPERADMIN"),
            @ApiResponse(responseCode = "404", description = "Không tìm thấy tổ chức")
    })
    public ResponseEntity<BaseResponse<TenantResponse>> approveTenantRegistration(
            @PathVariable("tenantId") String tenantId
    ) {
        TenantResponse response = tenantService.approveTenantRegistration(tenantId);
        return ResponseEntity.ok(BaseResponse.ok(
                "Phê duyệt tổ chức thành công. Tài khoản Quản trị viên cơ sở đã được kích hoạt.",
                response
        ));
    }

    /**
     * Rejects a pending tenant registration application with a mandatory reason (REQ-TNT-06).
     */
    @PostMapping(value = "/{tenantId}/reject", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @RequireSuperAdmin
    @Operation(
            summary = "Từ chối yêu cầu đăng ký tổ chức (REQ-TNT-06)",
            description = "Chuyển trạng thái sang REJECTED, xóa tài khoản tạm thời trên Keycloak và gửi email thông báo từ chối kèm lý do cho người đăng ký."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Từ chối yêu cầu đăng ký tổ chức thành công"),
            @ApiResponse(responseCode = "400", description = "Tổ chức không ở trạng thái PENDING_APPROVAL hoặc thiếu lý do từ chối"),
            @ApiResponse(responseCode = "403", description = "Quyền truy cập bị từ chối - chỉ chấp nhận SUPERADMIN"),
            @ApiResponse(responseCode = "404", description = "Không tìm thấy tổ chức")
    })
    public ResponseEntity<BaseResponse<TenantResponse>> rejectTenantRegistration(
            @PathVariable("tenantId") String tenantId,
            @Valid @RequestBody RejectTenantRequest request
    ) {
        TenantResponse response = tenantService.rejectTenantRegistration(tenantId, request);
        return ResponseEntity.ok(BaseResponse.ok(
                "Đã từ chối yêu cầu đăng ký tổ chức thành công",
                response
        ));
    }

    /**
     * General lifecycle status update dispatch (REQ-TNT-03).
     */
    @PatchMapping(value = "/{tenantId}/status", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @RequireSuperAdmin
    @Operation(
            summary = "Cập nhật trạng thái vòng đời tổ chức (REQ-TNT-03)",
            description = "Chuyển đổi trạng thái tổ chức giữa ACTIVE và SUSPENDED kèm đồng bộ quyền Keycloak."
    )
    public ResponseEntity<BaseResponse<TenantResponse>> updateTenantStatus(
            @PathVariable("tenantId") String tenantId,
            @Valid @RequestBody UpdateTenantStatusRequest request
    ) {
        TenantResponse response = tenantService.updateTenantStatus(tenantId, request);
        return ResponseEntity.ok(BaseResponse.ok(
                "Cập nhật trạng thái tổ chức thành công",
                response
        ));
    }

    /**
     * Deletes a tenant organization (REQ-TNT-03). Refuses physical deletion if historical data exists.
     */
    @DeleteMapping(value = "/{tenantId}", produces = MediaType.APPLICATION_JSON_VALUE)
    @RequireSuperAdmin
    @Operation(
            summary = "Xóa tổ chức (REQ-TNT-03)",
            description = "Hệ thống tuyệt đối từ chối thao tác xóa vật lý (hard-delete) nếu tổ chức đã phát sinh dữ liệu vé, lịch hẹn hoặc đánh giá của khách hàng."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Xóa tổ chức thành công"),
            @ApiResponse(responseCode = "403", description = "Quyền truy cập bị từ chối - chỉ chấp nhận SUPERADMIN"),
            @ApiResponse(responseCode = "404", description = "Không tìm thấy tổ chức"),
            @ApiResponse(responseCode = "409", description = "Từ chối xóa do tổ chức đã phát sinh dữ liệu lịch sử")
    })
    public ResponseEntity<BaseResponse<Void>> deleteTenant(
            @PathVariable("tenantId") String tenantId
    ) {
        tenantService.deleteTenant(tenantId);
        return ResponseEntity.ok(BaseResponse.ok(
                "Tổ chức đã được xóa an toàn khỏi hệ thống"
        ));
    }

    /**
     * Retrieves detailed profile and quotas of a tenant by ID or tenant code.
     */
    @GetMapping(value = "/{tenantId}", produces = MediaType.APPLICATION_JSON_VALUE)
    @RequireSuperAdmin
    @Operation(
            summary = "Tra cứu chi tiết tổ chức",
            description = "Lấy thông tin hồ sơ tổ chức và hạn ngạch tài nguyên hiện tại kèm số lượng nhân sự đang hoạt động."
    )
    public ResponseEntity<BaseResponse<TenantResponse>> getTenant(
            @PathVariable("tenantId") String tenantId
    ) {
        TenantResponse response = tenantService.getTenant(tenantId);
        return ResponseEntity.ok(BaseResponse.ok(
                "Lấy thông tin tổ chức thành công",
                response
        ));
    }

    /**
     * Retrieves operational quotas of a tenant by ID or tenant code.
     */
    @GetMapping(value = "/{tenantId}/quotas", produces = MediaType.APPLICATION_JSON_VALUE)
    @RequireSuperAdmin
    @Operation(
            summary = "Tra cứu hạn ngạch vận hành của tổ chức",
            description = "Lấy các chỉ số hạn ngạch tài nguyên và số lượng nhân sự thực tế hiện có."
    )
    public ResponseEntity<BaseResponse<TenantQuotaResponse>> getTenantQuota(
            @PathVariable("tenantId") String tenantId
    ) {
        TenantQuotaResponse response = tenantService.getTenantQuota(tenantId);
        return ResponseEntity.ok(BaseResponse.ok(
                "Lấy thông tin hạn ngạch tổ chức thành công",
                response
        ));
    }
}
