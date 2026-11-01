package com.nextturn.controller.tenant;

import com.nextturn.dto.BaseResponse;
import com.nextturn.dto.tenant.CreateTenantRequest;
import com.nextturn.dto.tenant.TenantQuotaDto;
import com.nextturn.dto.tenant.TenantQuotaResponse;
import com.nextturn.dto.tenant.TenantResponse;
import com.nextturn.dto.tenant.UpdateTenantRequest;
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
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller for tenant onboarding, administration, and operational quota configuration.
 * <p>
 * Strictly restricted to platform <b>SUPERADMIN</b> only:
 * - <b>REQ-TNT-01</b>: Provisioning new tenant organization & default administrator account.
 * - <b>REQ-TNT-02</b>: Updating tenant administrative info & configuring operational quotas.
 */
@RestController
@RequestMapping("/api/v1/central/tenants")
@Validated
@RequireSuperAdmin
@Tag(
        name = "Tenant Management",
        description = "Endpoints for platform Superadministrators to manage tenant lifecycle and operational quotas (REQ-TNT-01, REQ-TNT-02)"
)
@SecurityRequirement(name = "BearerAuth")
public class TenantManagementController {

    private final TenantService tenantService;

    @Autowired
    public TenantManagementController(TenantService tenantService) {
        this.tenantService = tenantService;
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
