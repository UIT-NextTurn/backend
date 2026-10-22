package com.nextturn.controller;

import com.nextturn.dto.BaseResponse;
import com.nextturn.dto.TenantAuthRequest;
import com.nextturn.dto.TenantAuthResponse;
import com.nextturn.service.TenantAuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Controller exposing REQ-AUTH-01: Tenant identification and staff login routing.
 * <p>
 * Operates like AWS IAM sign-in: takes a tenant code, resolves the tenant identity from
 * the central registry, and redirects/routes tenant staff (TENANT_ADMIN and COUNTER_STAFF)
 * to their pre-configured Keycloak organization authentication flow.
 */
@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "Authentication & Multi-Tenancy", description = "Endpoints for tenant resolution, authentication, and login routing")
@SecurityRequirements
public class TenantAuthController {

    private final TenantAuthService tenantAuthService;

    public TenantAuthController(TenantAuthService tenantAuthService) {
        this.tenantAuthService = tenantAuthService;
    }

    /**
     * POST endpoint to verify tenant code and generate staff login routing URL.
     *
     * @param request payload containing tenantCode
     * @return standard {@link BaseResponse} wrapping {@link TenantAuthResponse}
     */
    @PostMapping("/tenant/verify")
    @Operation(
            summary = "Verify tenant code and resolve staff login route (POST)",
            description = "REQ-AUTH-01: Validates tenant code in the master registry. If active, returns tenant profile and pre-configured Keycloak OIDC staff login URL."
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "Tenant recognized successfully and login URL generated",
                    content = @Content(schema = @Schema(implementation = BaseResponse.class))
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "Validation failure (blank code or invalid characters)",
                    content = @Content(schema = @Schema(implementation = BaseResponse.class))
            ),
            @ApiResponse(
                    responseCode = "403",
                    description = "Tenant facility is inactive, blocked, or deleted",
                    content = @Content(schema = @Schema(implementation = BaseResponse.class))
            ),
            @ApiResponse(
                    responseCode = "404",
                    description = "Tenant code not found in central registry",
                    content = @Content(schema = @Schema(implementation = BaseResponse.class))
            )
    })
    public ResponseEntity<BaseResponse<TenantAuthResponse>> verifyTenant(
            @Valid @RequestBody TenantAuthRequest request
    ) {
        TenantAuthResponse response = tenantAuthService.authenticateTenant(request);
        return ResponseEntity.ok(BaseResponse.ok("Tenant facility recognized successfully", response));
    }

    /**
     * GET endpoint alternative for direct URL/bookmark tenant code identification.
     *
     * @param tenantCode the alphanumeric tenant identifier
     * @return standard {@link BaseResponse} wrapping {@link TenantAuthResponse}
     */
    @GetMapping("/tenant/{tenantCode}")
    @Operation(
            summary = "Resolve tenant facility by code path parameter (GET)",
            description = "REQ-AUTH-01: Alternative path-based endpoint for bookmarking or direct facility identification."
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "Tenant recognized successfully",
                    content = @Content(schema = @Schema(implementation = BaseResponse.class))
            ),
            @ApiResponse(
                    responseCode = "403",
                    description = "Tenant facility is not active",
                    content = @Content(schema = @Schema(implementation = BaseResponse.class))
            ),
            @ApiResponse(
                    responseCode = "404",
                    description = "Tenant code not found",
                    content = @Content(schema = @Schema(implementation = BaseResponse.class))
            )
    })
    public ResponseEntity<BaseResponse<TenantAuthResponse>> getTenantByCode(
            @Parameter(description = "Alphanumeric tenant identifier (e.g. clinic_sai_gon)", required = true, example = "clinic_sai_gon")
            @PathVariable String tenantCode
    ) {
        TenantAuthRequest request = TenantAuthRequest.builder()
                .tenantCode(tenantCode)
                .build();
        TenantAuthResponse response = tenantAuthService.authenticateTenant(request);
        return ResponseEntity.ok(BaseResponse.ok("Tenant facility recognized successfully", response));
    }
}
