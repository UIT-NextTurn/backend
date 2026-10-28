package com.nextturn.controller.auth;

import com.nextturn.constant.AuthEndpointConstant;
import com.nextturn.dto.BaseResponse;
import com.nextturn.dto.auth.CreateStaffRequest;
import com.nextturn.dto.auth.StaffResponse;
import com.nextturn.dto.auth.UpdateStaffRequest;
import com.nextturn.infrastructure.security.annotation.RequireTenantAdmin;
import com.nextturn.service.auth.TenantStaffService;
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
import org.springframework.web.bind.annotation.RestController;

/**
 * Authentication domain REST controller for Tenant Administrators to manage healthcare encounter staff.
 * Operates purely from the NextTurn Web Portal; Tenant Admins do not access Keycloak directly.
 */
@RestController
@RequestMapping(AuthEndpointConstant.TENANT_ADMIN_STAFF_BASE)
@RequireTenantAdmin
@Tag(name = "Authentication & Staff Management", description = "Endpoints for Tenant Administrators to onboard, manage, and provision encounter staff")
@SecurityRequirement(name = "bearerAuth")
public class TenantStaffController {

    private final TenantStaffService staffService;

    public TenantStaffController(TenantStaffService staffService) {
        this.staffService = staffService;
    }

    @PostMapping
    @Operation(
            summary = "Onboard and provision encounter staff member",
            description = "Creates staff user in Keycloak, assigns COUNTER_STAFF role, links to clinic Organization, and saves to tenant database."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Staff created and provisioned successfully",
                    content = @Content(schema = @Schema(implementation = BaseResponse.class))),
            @ApiResponse(responseCode = "400", description = "Validation failed for staff payload",
                    content = @Content(schema = @Schema(implementation = BaseResponse.class))),
            @ApiResponse(responseCode = "409", description = "Staff email, phone, or badge already exists",
                    content = @Content(schema = @Schema(implementation = BaseResponse.class)))
    })
    public ResponseEntity<BaseResponse<StaffResponse>> createStaff(@Valid @RequestBody CreateStaffRequest request) {
        StaffResponse response = staffService.createStaff(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(BaseResponse.created("Staff member provisioned successfully", response));
    }

    @GetMapping
    @Operation(summary = "List staff members with pagination", description = "Retrieves all active staff members belonging to the caller's tenant.")
    public ResponseEntity<BaseResponse<Page<StaffResponse>>> getStaffList(
            @PageableDefault(size = 20) Pageable pageable
    ) {
        Page<StaffResponse> page = staffService.getStaffList(pageable);
        return ResponseEntity.ok(BaseResponse.ok("Staff list retrieved successfully", page));
    }

    @GetMapping("/{staffId}")
    @Operation(summary = "Get staff profile by ID", description = "Retrieves details of a specific staff member.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Staff member found"),
            @ApiResponse(responseCode = "404", description = "Staff member not found")
    })
    public ResponseEntity<BaseResponse<StaffResponse>> getStaffById(
            @Parameter(description = "Staff UUID", required = true) @PathVariable String staffId
    ) {
        StaffResponse response = staffService.getStaffById(staffId);
        return ResponseEntity.ok(BaseResponse.ok("Staff member retrieved successfully", response));
    }

    @PutMapping("/{staffId}")
    @Operation(summary = "Update staff profile or status", description = "Updates staff details and syncs active status to Keycloak.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Staff member updated successfully"),
            @ApiResponse(responseCode = "404", description = "Staff member not found")
    })
    public ResponseEntity<BaseResponse<StaffResponse>> updateStaff(
            @Parameter(description = "Staff UUID", required = true) @PathVariable String staffId,
            @Valid @RequestBody UpdateStaffRequest request
    ) {
        StaffResponse response = staffService.updateStaff(staffId, request);
        return ResponseEntity.ok(BaseResponse.ok("Staff member updated successfully", response));
    }

    @DeleteMapping("/{staffId}")
    @Operation(summary = "Deactivate and delete staff member", description = "Soft-deletes staff from tenant database and disables Keycloak user.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Staff member deleted successfully"),
            @ApiResponse(responseCode = "404", description = "Staff member not found")
    })
    public ResponseEntity<BaseResponse<Void>> deleteStaff(
            @Parameter(description = "Staff UUID", required = true) @PathVariable String staffId
    ) {
        staffService.deleteStaff(staffId);
        return ResponseEntity.ok(BaseResponse.ok("Staff member deleted successfully", null));
    }
}
