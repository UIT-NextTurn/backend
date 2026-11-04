package com.nextturn.controller.catalog;

import com.nextturn.constant.ServiceEndpointConstant;
import com.nextturn.dto.BaseResponse;
import com.nextturn.dto.service.CreateServiceRequest;
import com.nextturn.dto.service.ServiceResponse;
import com.nextturn.dto.service.UpdateServiceRequest;
import com.nextturn.enums.ServiceStatus;
import com.nextturn.infrastructure.security.annotation.RequireTenantAdmin;
import com.nextturn.service.service.TenantServiceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * REST controller for Tenant Administrators to manage services catalog,
 * configure standard service durations, update operational status, and search services.
 */
@RestController
@RequestMapping(ServiceEndpointConstant.SERVICES_BASE)
@RequireTenantAdmin
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "Tenant Service Catalog", description = "Endpoints for Tenant Administrators to manage services, standard durations, status, and catalog search")
public class TenantCatalogController {

    private final TenantServiceService catalogService;

    public TenantCatalogController(TenantServiceService catalogService) {
        this.catalogService = catalogService;
    }

    @PostMapping
    @Operation(
            summary = "Khởi tạo dịch vụ mới",
            description = "Tạo một dịch vụ mới vào danh mục hoạt động của cơ sở, kiểm tra chống trùng lặp tên và tự động sinh mã SVC-xxxx"
    )
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Khởi tạo dịch vụ thành công",
                    content = @Content(schema = @Schema(implementation = BaseResponse.class))),
            @ApiResponse(responseCode = "400", description = "Dữ liệu không hợp lệ hoặc thiếu trường bắt buộc",
                    content = @Content(schema = @Schema(implementation = BaseResponse.class))),
            @ApiResponse(responseCode = "409", description = "Tên dịch vụ đã tồn tại trong danh mục này",
                    content = @Content(schema = @Schema(implementation = BaseResponse.class)))
    })
    public ResponseEntity<BaseResponse<ServiceResponse>> createService(
            @Valid @RequestBody CreateServiceRequest request
    ) {
        ServiceResponse response = catalogService.createService(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(BaseResponse.created("Khởi tạo dịch vụ thành công", response));
    }

    @PutMapping(ServiceEndpointConstant.SERVICE_BY_ID)
    @Operation(
            summary = "Chỉnh sửa dịch vụ & cấu hình thời lượng chuẩn",
            description = "Cập nhật tên, đơn giá, trạng thái hoạt động và thời lượng phục vụ chuẩn (T_standard) của dịch vụ hiện hành. Làm mới cache trên Valkey."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Cập nhật dịch vụ thành công",
                    content = @Content(schema = @Schema(implementation = BaseResponse.class))),
            @ApiResponse(responseCode = "400", description = "Dữ liệu không hợp lệ hoặc thiếu trường bắt buộc",
                    content = @Content(schema = @Schema(implementation = BaseResponse.class))),
            @ApiResponse(responseCode = "404", description = "Dịch vụ không tồn tại trong hệ thống hoặc đã bị xóa",
                    content = @Content(schema = @Schema(implementation = BaseResponse.class))),
            @ApiResponse(responseCode = "409", description = "Tên dịch vụ đã tồn tại trong danh mục này",
                    content = @Content(schema = @Schema(implementation = BaseResponse.class)))
    })
    public ResponseEntity<BaseResponse<ServiceResponse>> updateService(
            @Parameter(description = "Service UUID", required = true) @PathVariable String serviceId,
            @Valid @RequestBody UpdateServiceRequest request
    ) {
        ServiceResponse response = catalogService.updateService(serviceId, request);
        return ResponseEntity.ok(BaseResponse.ok("Cập nhật dịch vụ thành công", response));
    }

    @GetMapping(ServiceEndpointConstant.SERVICE_BY_ID)
    @Operation(
            summary = "Chi tiết dịch vụ",
            description = "Lấy thông tin chi tiết của một dịch vụ theo ID"
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Lấy chi tiết dịch vụ thành công"),
            @ApiResponse(responseCode = "404", description = "Dịch vụ không tồn tại trong hệ thống hoặc đã bị xóa")
    })
    public ResponseEntity<BaseResponse<ServiceResponse>> getServiceById(
            @Parameter(description = "Service UUID", required = true) @PathVariable String serviceId
    ) {
        ServiceResponse response = catalogService.getServiceById(serviceId);
        return ResponseEntity.ok(BaseResponse.ok("Lấy chi tiết dịch vụ thành công", response));
    }

    @GetMapping
    @Operation(
            summary = "Tra cứu & phân trang danh mục dịch vụ (Admin)",
            description = "Tìm kiếm đa tiêu chí theo từ khóa (tên hoặc mã SVC-xxxx), lọc theo trạng thái hoạt động và phân trang"
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Lấy danh mục dịch vụ thành công")
    })
    public ResponseEntity<BaseResponse<List<ServiceResponse>>> getServiceList(
            @Parameter(description = "Từ khóa tìm kiếm (tên dịch vụ hoặc mã SVC-xxxx)")
            @RequestParam(required = false) String keyword,
            @Parameter(description = "Bộ lọc trạng thái hoạt động (ACTIVE, INACTIVE)")
            @RequestParam(required = false) ServiceStatus status,
            @PageableDefault(size = 20, sort = "serviceCode") Pageable pageable
    ) {
        Page<ServiceResponse> page = catalogService.searchServices(keyword, status, pageable);
        return ResponseEntity.ok(BaseResponse.okPaged("Lấy danh sách dịch vụ thành công", page));
    }

    @DeleteMapping(ServiceEndpointConstant.SERVICE_BY_ID)
    @Operation(
            summary = "Xóa logic dịch vụ",
            description = "Xóa logic (soft-delete) dịch vụ khỏi danh mục. Kiểm tra toàn vẹn quan hệ và từ chối xóa nếu còn lịch hẹn hoặc vé chờ chưa xử lý."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Xóa dịch vụ thành công"),
            @ApiResponse(responseCode = "404", description = "Dịch vụ không tồn tại trong hệ thống hoặc đã bị xóa"),
            @ApiResponse(responseCode = "409", description = "Dịch vụ có lịch hẹn hoặc vé chờ chưa hoàn tất, từ chối xóa")
    })
    public ResponseEntity<BaseResponse<Void>> deleteService(
            @Parameter(description = "Service UUID", required = true) @PathVariable String serviceId
    ) {
        catalogService.deleteService(serviceId);
        return ResponseEntity.ok(BaseResponse.ok("Dịch vụ đã được xóa thành công", null));
    }
}
