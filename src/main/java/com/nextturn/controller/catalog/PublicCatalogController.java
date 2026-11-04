package com.nextturn.controller.catalog;

import com.nextturn.constant.ServiceEndpointConstant;
import com.nextturn.constant.TenantHeaderConstant;
import com.nextturn.dto.BaseResponse;
import com.nextturn.dto.service.ServiceResponse;
import com.nextturn.enums.ServiceStatus;
import com.nextturn.service.service.TenantServiceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Public and Customer-facing REST controller for browsing and searching available booking services.
 * Serves service catalog metadata to customer pre-booking and online scheduling portals.
 */
@RestController
@RequestMapping(ServiceEndpointConstant.PUBLIC_SERVICES_BASE)
@Tag(name = "Public Service Catalog", description = "Public / Customer endpoints for browsing services and online appointment scheduling")
public class PublicCatalogController {

    private final TenantServiceService catalogService;

    public PublicCatalogController(TenantServiceService catalogService) {
        this.catalogService = catalogService;
    }

    @GetMapping
    @Operation(
            summary = "Cấp danh mục dịch vụ cho màn hình đặt lịch của khách hàng",
            description = "Tra cứu và phân trang danh mục dịch vụ của cơ sở (mặc định chỉ lấy các dịch vụ ACTIVE đang hoạt động)"
    )
    public ResponseEntity<BaseResponse<List<ServiceResponse>>> getPublicServices(
            @Parameter(description = "Mã định danh cơ sở (ví dụ: tenant-alpha)")
            @RequestHeader(value = TenantHeaderConstant.X_TENANT_CODE_HEADER, required = false) String headerTenantCode,
            @Parameter(description = "Mã định danh cơ sở dạng query param")
            @RequestParam(required = false) String tenantCode,
            @Parameter(description = "Từ khóa tìm kiếm (tên dịch vụ hoặc mã SVC-xxxx)")
            @RequestParam(required = false) String keyword,
            @Parameter(description = "Bộ lọc trạng thái hoạt động (mặc định: ACTIVE)")
            @RequestParam(required = false) ServiceStatus status,
            @PageableDefault(size = 20, sort = "serviceCode") Pageable pageable
    ) {
        String targetTenant = (headerTenantCode != null && !headerTenantCode.isBlank()) ? headerTenantCode : tenantCode;
        Page<ServiceResponse> page = catalogService.searchPublicServices(targetTenant, keyword, status, pageable);
        return ResponseEntity.ok(BaseResponse.okPaged("Lấy danh mục dịch vụ cơ sở thành công", page));
    }
}
