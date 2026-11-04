package com.nextturn.controller.staff;

import com.nextturn.constant.AuthEndpointConstant;
import com.nextturn.constant.StaffEndpointConstant;
import com.nextturn.dto.BaseResponse;
import com.nextturn.dto.service.ServiceResponse;
import com.nextturn.dto.staff.AssignStaffServicesRequest;
import com.nextturn.dto.staff.CreateStaffRequest;
import com.nextturn.dto.staff.StaffResponse;
import com.nextturn.dto.staff.UpdateStaffRequest;
import com.nextturn.enums.StaffRole;
import com.nextturn.enums.StaffStatus;
import com.nextturn.infrastructure.security.annotation.RequireTenantAdmin;
import com.nextturn.service.staff.TenantStaffService;
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
 * REST controller for Tenant Administrators to manage staff onboarding,
 * profiles, status, and service assignments.
 */
@RestController
@RequestMapping({StaffEndpointConstant.STAFF_BASE, AuthEndpointConstant.TENANT_ADMIN_STAFF_BASE})
@RequireTenantAdmin
@Tag(name = "Tenant Staff Management", description = "Endpoints for Tenant Administrators to manage staff accounts, roles, and service assignments")
@SecurityRequirement(name = "bearerAuth")
public class TenantStaffController {

    private final TenantStaffService staffService;

    public TenantStaffController(TenantStaffService staffService) {
        this.staffService = staffService;
    }

    @PostMapping
    @Operation(
            summary = "Tiếp nhận và khởi tạo hồ sơ nhân viên",
            description = "Thêm mới nhân viên vào cơ sở, kiểm tra trùng lặp email/SĐT, tạo tài khoản Keycloak, lưu bản ghi MySQL với trạng thái Active và sinh mã STAFF-xxxx"
    )
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Khởi tạo nhân viên thành công",
                    content = @Content(schema = @Schema(implementation = BaseResponse.class))),
            @ApiResponse(responseCode = "400", description = "Dữ liệu không hợp lệ hoặc thiếu trường bắt buộc",
                    content = @Content(schema = @Schema(implementation = BaseResponse.class))),
            @ApiResponse(responseCode = "409", description = "Email hoặc số điện thoại đã tồn tại trong hệ thống",
                    content = @Content(schema = @Schema(implementation = BaseResponse.class))),
            @ApiResponse(responseCode = "500", description = "Lỗi đồng bộ tài khoản xác thực Keycloak",
                    content = @Content(schema = @Schema(implementation = BaseResponse.class)))
    })
    public ResponseEntity<BaseResponse<StaffResponse>> createStaff(
            @Valid @RequestBody CreateStaffRequest request
    ) {
        StaffResponse response = staffService.createStaff(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(BaseResponse.created("Khởi tạo nhân viên thành công", response));
    }

    @GetMapping
    @Operation(
            summary = "Danh sách nhân viên",
            description = "Lấy danh sách nhân viên trong cơ sở kèm thông tin phân trang và hỗ trợ lọc theo từ khóa, vai trò, trạng thái"
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Lấy danh sách nhân viên thành công")
    })
    public ResponseEntity<BaseResponse<List<StaffResponse>>> getStaffList(
            @Parameter(description = "Từ khóa tìm kiếm (họ tên, email, SĐT, mã nhân viên)") @RequestParam(required = false) String keyword,
            @Parameter(description = "Bộ lọc vai trò (ADMIN, COUNTER, SPECIALIST)") @RequestParam(required = false) StaffRole role,
            @Parameter(description = "Bộ lọc trạng thái (ACTIVE, INACTIVE)") @RequestParam(required = false) StaffStatus status,
            @PageableDefault(size = 20, sort = "staffCode") Pageable pageable
    ) {
        Page<StaffResponse> page = (keyword != null || role != null || status != null)
                ? staffService.searchStaff(keyword, role, status, pageable)
                : staffService.getStaffList(pageable);
        return ResponseEntity.ok(BaseResponse.okPaged("Lấy danh sách nhân viên thành công", page));
    }

    @GetMapping(StaffEndpointConstant.STAFF_BY_ID)
    @Operation(
            summary = "Chi tiết nhân viên",
            description = "Lấy thông tin chi tiết một nhân viên theo ID"
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Lấy chi tiết nhân viên thành công"),
            @ApiResponse(responseCode = "404", description = "Nhân viên không tồn tại hoặc đã bị xóa")
    })
    public ResponseEntity<BaseResponse<StaffResponse>> getStaffById(
            @Parameter(description = "Staff UUID", required = true) @PathVariable String staffId
    ) {
        StaffResponse response = staffService.getStaffById(staffId);
        return ResponseEntity.ok(BaseResponse.ok("Lấy chi tiết nhân viên thành công", response));
    }

    @PutMapping(StaffEndpointConstant.STAFF_BY_ID)
    @Operation(
            summary = "Cập nhật hồ sơ hoặc trạng thái nhân viên",
            description = "Cập nhật họ tên, số điện thoại, vai trò hoặc trạng thái hoạt động (khóa/tạm ngưng) của nhân viên. Khi khóa/tạm ngưng (status = INACTIVE), hệ thống kiểm tra các cuộc hẹn hoặc ca trực trong tương lai và đồng bộ trạng thái tài khoản trên Keycloak."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Cập nhật thông tin nhân viên thành công"),
            @ApiResponse(responseCode = "400", description = "Dữ liệu không hợp lệ"),
            @ApiResponse(responseCode = "404", description = "Nhân viên không tồn tại hoặc đã bị xóa"),
            @ApiResponse(responseCode = "409", description = "Số điện thoại bị trùng lặp hoặc nhân viên còn lịch hẹn/ca trực tương lai chưa chuyển giao",
                    content = @Content(schema = @Schema(implementation = BaseResponse.class)))
    })
    public ResponseEntity<BaseResponse<StaffResponse>> updateStaff(
            @Parameter(description = "Staff UUID", required = true) @PathVariable String staffId,
            @Valid @RequestBody UpdateStaffRequest request
    ) {
        StaffResponse response = staffService.updateStaff(staffId, request);
        return ResponseEntity.ok(BaseResponse.ok("Cập nhật thông tin nhân viên thành công", response));
    }

    @DeleteMapping(StaffEndpointConstant.STAFF_BY_ID)
    @Operation(
            summary = "Xóa hoặc vô hiệu hóa nhân viên",
            description = "Đánh dấu xóa logic nhân viên khỏi hệ thống (is_deleted = true, status = INACTIVE). Nếu hardDelete = true, hệ thống kiểm tra lịch sử gọi vé và phản hồi đánh giá, tuyệt đối từ chối xóa vật lý nếu đã có dữ liệu lịch sử."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Xóa hoặc vô hiệu hóa nhân viên thành công"),
            @ApiResponse(responseCode = "404", description = "Nhân viên không tồn tại hoặc đã bị xóa"),
            @ApiResponse(responseCode = "409", description = "Còn lịch hẹn/ca trực tương lai hoặc có dữ liệu lịch sử ngăn cản xóa vật lý",
                    content = @Content(schema = @Schema(implementation = BaseResponse.class)))
    })
    public ResponseEntity<BaseResponse<Void>> deleteStaff(
            @Parameter(description = "Staff UUID", required = true) @PathVariable String staffId,
            @Parameter(description = "Tùy chọn xóa vật lý (mặc định false - xóa logic)") @RequestParam(defaultValue = "false", required = false) boolean hardDelete
    ) {
        staffService.deleteStaff(staffId, hardDelete);
        return ResponseEntity.ok(BaseResponse.ok(
                hardDelete ? "Xóa vật lý nhân viên thành công" : "Vô hiệu hóa và xóa nhân viên thành công",
                null
        ));
    }

    @PutMapping(StaffEndpointConstant.STAFF_SERVICES)
    @Operation(
            summary = "Phân công dịch vụ cho nhân viên",
            description = "Chỉ định các loại dịch vụ cụ thể mà một nhân viên có chuyên môn và thẩm quyền phụ trách cung cấp. Cập nhật bảng staff_services và xóa cache trên Valkey."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Phân công dịch vụ thành công",
                    content = @Content(schema = @Schema(implementation = BaseResponse.class))),
            @ApiResponse(responseCode = "400", description = "Nhân viên không ở trạng thái hoạt động hoặc danh sách dịch vụ rỗng",
                    content = @Content(schema = @Schema(implementation = BaseResponse.class))),
            @ApiResponse(responseCode = "404", description = "Nhân viên hoặc dịch vụ không tồn tại trong hệ thống",
                    content = @Content(schema = @Schema(implementation = BaseResponse.class)))
    })
    public ResponseEntity<BaseResponse<StaffResponse>> assignServices(
            @Parameter(description = "Staff UUID", required = true) @PathVariable String staffId,
            @Valid @RequestBody AssignStaffServicesRequest request
    ) {
        StaffResponse response = staffService.assignServices(staffId, request);
        return ResponseEntity.ok(BaseResponse.ok("Phân công dịch vụ cho nhân viên thành công", response));
    }

    @GetMapping(StaffEndpointConstant.STAFF_SERVICES)
    @Operation(
            summary = "Danh sách dịch vụ được phân công của nhân viên",
            description = "Lấy danh sách các dịch vụ mà nhân viên đang được chỉ định phụ trách"
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Lấy danh sách dịch vụ phân công thành công"),
            @ApiResponse(responseCode = "404", description = "Nhân viên không tồn tại hoặc đã bị xóa")
    })
    public ResponseEntity<BaseResponse<List<ServiceResponse>>> getAssignedServices(
            @Parameter(description = "Staff UUID", required = true) @PathVariable String staffId
    ) {
        List<ServiceResponse> services = staffService.getAssignedServices(staffId);
        return ResponseEntity.ok(BaseResponse.ok("Lấy danh sách dịch vụ phân công thành công", services));
    }
}

