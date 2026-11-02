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
        description = "Endpoints for platform Superadministrators to manage tenant lifecycle, operational quotas, and global telemetry (REQ-TNT-01 to REQ-TNT-04, REQ-TNT-06)"
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
            summary = "Search, filter, and monitor tenants (REQ-TNT-04)",
            description = "Searches and filters organizations by multiple criteria (keyword, status) with pagination metadata and operational monitoring metrics (active staff count, today's queue tickets)."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Tenants retrieved successfully"),
            @ApiResponse(responseCode = "403", description = "Access denied - Superadministrator role required")
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
                "Tenants retrieved successfully",
                pagedResult
        ));
    }

    /**
     * Provisions a new tenant facility and default administrator account (REQ-TNT-01).
     */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @RequireSuperAdmin
    @Operation(
            summary = "Provision new tenant organization (REQ-TNT-01)",
            description = "Superadministrator registers a new facility, provisions an isolated tenant database schema, provisions the administrator account on Keycloak, assigns default operational quotas, and dispatches activation email."
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "201",
                    description = "Tenant provisioned successfully",
                    content = @Content(schema = @Schema(implementation = BaseResponse.class))
            ),
            @ApiResponse(responseCode = "400", description = "Invalid request payload"),
            @ApiResponse(responseCode = "403", description = "Access denied - Superadministrator role required"),
            @ApiResponse(responseCode = "409", description = "Tenant code or administrator email already registered"),
            @ApiResponse(responseCode = "500", description = "Keycloak organization synchronization failed (rolled back)")
    })
    public ResponseEntity<BaseResponse<TenantResponse>> createTenant(
            @Valid @RequestBody CreateTenantRequest request
    ) {
        TenantResponse response = tenantService.createTenant(request);
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(BaseResponse.created(
                        "Tenant provisioned successfully",
                        response
                ));
    }

    /**
     * Updates tenant administrative information and operational capacity quotas (REQ-TNT-02).
     */
    @PutMapping(value = "/{tenantId}", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @RequireSuperAdmin
    @Operation(
            summary = "Update tenant profile and operational quotas (REQ-TNT-02)",
            description = "Superadministrator updates administrative profile and operational resource quotas (maximum active staff, daily tickets, services, appointments). Rejects if current active staff exceeds new limit."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Tenant updated successfully"),
            @ApiResponse(responseCode = "400", description = "New staff quota is less than active staff count, or invalid input"),
            @ApiResponse(responseCode = "403", description = "Access denied - Superadministrator role required"),
            @ApiResponse(responseCode = "404", description = "Tenant not found")
    })
    public ResponseEntity<BaseResponse<TenantResponse>> updateTenant(
            @PathVariable("tenantId") String tenantId,
            @Valid @RequestBody UpdateTenantRequest request
    ) {
        TenantResponse response = tenantService.updateTenant(tenantId, request);
        return ResponseEntity.ok(BaseResponse.ok(
                "Tenant profile and operational quotas updated successfully",
                response
        ));
    }

    /**
     * Updates operational capacity quotas separately (REQ-TNT-02).
     */
    @PutMapping(value = "/{tenantId}/quotas", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @RequireSuperAdmin
    @Operation(
            summary = "Update operational capacity quotas (REQ-TNT-02)",
            description = "Superadministrator modifies operational quotas: maximum active staff, daily ticket quota, services, and future appointments. Supports -1 for unlimited."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Tenant quotas updated successfully"),
            @ApiResponse(responseCode = "400", description = "New staff quota is less than current active staff count"),
            @ApiResponse(responseCode = "403", description = "Access denied - Superadministrator role required"),
            @ApiResponse(responseCode = "404", description = "Tenant not found")
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
                "Tenant operational quotas updated successfully",
                response
        ));
    }

    /**
     * Suspends organization operations and disables staff credentials in Keycloak (REQ-TNT-03).
     */
    @PostMapping(value = "/{tenantId}/suspend", produces = MediaType.APPLICATION_JSON_VALUE)
    @RequireSuperAdmin
    @Operation(
            summary = "Suspend tenant organization (REQ-TNT-03)",
            description = "Transitions tenant status to SUSPENDED (strictly allowed only from ACTIVE). Disables staff credentials, revokes active Keycloak sessions, and halts public queue/booking intake."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Tenant suspended successfully"),
            @ApiResponse(responseCode = "400", description = "Tenant is not in ACTIVE status or is already SUSPENDED"),
            @ApiResponse(responseCode = "403", description = "Access denied - Superadministrator role required"),
            @ApiResponse(responseCode = "404", description = "Tenant not found")
    })
    public ResponseEntity<BaseResponse<TenantResponse>> suspendTenant(
            @PathVariable("tenantId") String tenantId
    ) {
        TenantResponse response = tenantService.suspendTenant(tenantId);
        return ResponseEntity.ok(BaseResponse.ok(
                "Tenant organization suspended successfully",
                response
        ));
    }

    /**
     * Reactivates a suspended organization and restores Keycloak login access (REQ-TNT-03).
     */
    @PostMapping(value = "/{tenantId}/activate", produces = MediaType.APPLICATION_JSON_VALUE)
    @RequireSuperAdmin
    @Operation(
            summary = "Reactivate suspended tenant (REQ-TNT-03)",
            description = "Transitions tenant status back to ACTIVE (strictly allowed only from SUSPENDED; for PENDING_APPROVAL please use the approve endpoint). Restores staff Keycloak access and resumes online services."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Tenant reactivated successfully"),
            @ApiResponse(responseCode = "400", description = "Tenant is not in SUSPENDED status or is already ACTIVE"),
            @ApiResponse(responseCode = "403", description = "Access denied - Superadministrator role required"),
            @ApiResponse(responseCode = "404", description = "Tenant not found")
    })
    public ResponseEntity<BaseResponse<TenantResponse>> activateTenant(
            @PathVariable("tenantId") String tenantId
    ) {
        TenantResponse response = tenantService.activateTenant(tenantId);
        return ResponseEntity.ok(BaseResponse.ok(
                "Tenant organization reactivated successfully",
                response
        ));
    }

    /**
     * Approves a pending tenant registration application (REQ-TNT-06).
     */
    @PostMapping(value = "/{tenantId}/approve", produces = MediaType.APPLICATION_JSON_VALUE)
    @RequireSuperAdmin
    @Operation(
            summary = "Approve pending tenant registration (REQ-TNT-06)",
            description = "Approves a pending registration application by transitioning status from PENDING_APPROVAL to ACTIVE, enabling the Keycloak administrator account, and dispatching a welcome onboarding email."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Tenant registration approved successfully"),
            @ApiResponse(responseCode = "400", description = "Tenant is not in PENDING_APPROVAL status"),
            @ApiResponse(responseCode = "403", description = "Access denied - Superadministrator role required"),
            @ApiResponse(responseCode = "404", description = "Tenant not found")
    })
    public ResponseEntity<BaseResponse<TenantResponse>> approveTenantRegistration(
            @PathVariable("tenantId") String tenantId
    ) {
        TenantResponse response = tenantService.approveTenantRegistration(tenantId);
        return ResponseEntity.ok(BaseResponse.ok(
                "Tenant registration approved successfully. Administrator account is now active.",
                response
        ));
    }

    /**
     * Rejects a pending tenant registration application with a mandatory reason (REQ-TNT-06).
     */
    @PostMapping(value = "/{tenantId}/reject", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @RequireSuperAdmin
    @Operation(
            summary = "Reject pending tenant registration (REQ-TNT-06)",
            description = "Rejects a pending registration application by transitioning status to REJECTED, removing the staged Keycloak admin account, and sending a rejection notification email with the mandatory reason."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Tenant registration rejected successfully"),
            @ApiResponse(responseCode = "400", description = "Tenant is not in PENDING_APPROVAL status or rejection reason is blank"),
            @ApiResponse(responseCode = "403", description = "Access denied - Superadministrator role required"),
            @ApiResponse(responseCode = "404", description = "Tenant not found")
    })
    public ResponseEntity<BaseResponse<TenantResponse>> rejectTenantRegistration(
            @PathVariable("tenantId") String tenantId,
            @Valid @RequestBody RejectTenantRequest request
    ) {
        TenantResponse response = tenantService.rejectTenantRegistration(tenantId, request);
        return ResponseEntity.ok(BaseResponse.ok(
                "Tenant registration application rejected successfully",
                response
        ));
    }

    /**
     * General lifecycle status update dispatch (REQ-TNT-03).
     */
    @PatchMapping(value = "/{tenantId}/status", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @RequireSuperAdmin
    @Operation(
            summary = "Update tenant lifecycle status (REQ-TNT-03)",
            description = "Dispatches general status updates between ACTIVE and SUSPENDED with corresponding Keycloak account synchronization."
    )
    public ResponseEntity<BaseResponse<TenantResponse>> updateTenantStatus(
            @PathVariable("tenantId") String tenantId,
            @Valid @RequestBody UpdateTenantStatusRequest request
    ) {
        TenantResponse response = tenantService.updateTenantStatus(tenantId, request);
        return ResponseEntity.ok(BaseResponse.ok(
                "Tenant status updated successfully",
                response
        ));
    }

    /**
     * Deletes a tenant organization (REQ-TNT-03). Refuses physical deletion if historical data exists.
     */
    @DeleteMapping(value = "/{tenantId}", produces = MediaType.APPLICATION_JSON_VALUE)
    @RequireSuperAdmin
    @Operation(
            summary = "Delete tenant organization (REQ-TNT-03)",
            description = "Safely marks organization as soft-deleted and disables Keycloak credentials. Hard deletion is strictly refused if historical tickets, appointments, or reviews exist."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Tenant deleted successfully"),
            @ApiResponse(responseCode = "403", description = "Access denied - Superadministrator role required"),
            @ApiResponse(responseCode = "404", description = "Tenant not found"),
            @ApiResponse(responseCode = "409", description = "Cannot delete tenant with existing historical operational data")
    })
    public ResponseEntity<BaseResponse<Void>> deleteTenant(
            @PathVariable("tenantId") String tenantId
    ) {
        tenantService.deleteTenant(tenantId);
        return ResponseEntity.ok(BaseResponse.ok(
                "Tenant organization deleted successfully"
        ));
    }

    /**
     * Retrieves detailed profile and quotas of a tenant by ID or tenant code.
     */
    @GetMapping(value = "/{tenantId}", produces = MediaType.APPLICATION_JSON_VALUE)
    @RequireSuperAdmin
    @Operation(
            summary = "Get tenant details by ID or code",
            description = "Retrieves complete tenant profile, resource quotas, and current active staff count with Valkey read-through caching."
    )
    public ResponseEntity<BaseResponse<TenantResponse>> getTenant(
            @PathVariable("tenantId") String tenantId
    ) {
        TenantResponse response = tenantService.getTenant(tenantId);
        return ResponseEntity.ok(BaseResponse.ok(
                "Tenant details retrieved successfully",
                response
        ));
    }

    /**
     * Retrieves operational quotas of a tenant by ID or tenant code.
     */
    @GetMapping(value = "/{tenantId}/quotas", produces = MediaType.APPLICATION_JSON_VALUE)
    @RequireSuperAdmin
    @Operation(
            summary = "Get tenant operational quotas",
            description = "Retrieves operational quota limits and current active staff usage for the tenant."
    )
    public ResponseEntity<BaseResponse<TenantQuotaResponse>> getTenantQuota(
            @PathVariable("tenantId") String tenantId
    ) {
        TenantQuotaResponse response = tenantService.getTenantQuota(tenantId);
        return ResponseEntity.ok(BaseResponse.ok(
                "Tenant operational quotas retrieved successfully",
                response
        ));
    }
}
