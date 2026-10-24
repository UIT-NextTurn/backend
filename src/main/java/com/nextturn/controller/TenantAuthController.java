package com.nextturn.controller;

import com.nextturn.dto.AuthTokenResponse;
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

    /**
     * GET endpoint providing Keycloak OIDC authorization URL for Super Admin platform login.
     *
     * @return standard {@link BaseResponse} wrapping {@link TenantAuthResponse} for Super Admin
     */
    @GetMapping("/superadmin/login-url")
    @Operation(
            summary = "Get Superadmin Keycloak login redirect URL (GET)",
            description = "Constructs pre-configured Keycloak OIDC authorization URL for platform-wide Super Admin login."
    )
    public ResponseEntity<BaseResponse<TenantAuthResponse>> getSuperAdminLoginUrl() {
        TenantAuthResponse response = tenantAuthService.resolveSuperAdminLogin();
        return ResponseEntity.ok(BaseResponse.ok("Super Admin login route generated successfully", response));
    }

    /**
     * POST endpoint exchanging Keycloak authorization code for JWT tokens.
     * Allows SUPERADMIN, TENANT_ADMIN, and COUNTER_STAFF; strictly rejects END_USER.
     *
     * @param request code and optional redirectUri
     * @return standard {@link BaseResponse} wrapping {@link AuthTokenResponse}
     */
    @PostMapping("/token")
    @Operation(
            summary = "Exchange OAuth2 authorization code for JWT tokens (POST)",
            description = "Exchanges Keycloak code for access/refresh tokens. Validates client roles (SUPERADMIN, TENANT_ADMIN, COUNTER_STAFF) and rejects END_USER."
    )
    public ResponseEntity<BaseResponse<AuthTokenResponse>> exchangeToken(
            @Valid @RequestBody com.nextturn.dto.TokenExchangeRequest request
    ) {
        AuthTokenResponse response = tenantAuthService.exchangeAuthorizationCode(request);
        return ResponseEntity.ok(BaseResponse.ok("Authentication completed successfully", response));
    }


    /**
     * POST endpoint refreshing expired access tokens using a valid refresh token.
     *
     * @param request refresh token payload
     * @return standard {@link BaseResponse} wrapping {@link AuthTokenResponse}
     */
    @PostMapping("/refresh")
    @Operation(
            summary = "Refresh expired access token (POST)",
            description = "Renews access tokens using an active Keycloak refresh token."
    )
    public ResponseEntity<BaseResponse<AuthTokenResponse>> refreshToken(
            @Valid @RequestBody com.nextturn.dto.RefreshTokenRequest request
    ) {
        AuthTokenResponse response = tenantAuthService.refreshAccessToken(request);
        return ResponseEntity.ok(BaseResponse.ok("Token refreshed successfully", response));
    }

    /**
     * GET endpoint returning currently authenticated user profile and roles.
     *
     * @param user injected authenticated user principal
     * @return standard {@link BaseResponse} wrapping {@link com.nextturn.infrastructure.security.principal.AuthenticatedUser}
     */
    @GetMapping("/me")
    @Operation(
            summary = "Get currently authenticated user principal details (GET)",
            description = "Returns active user information, roles, and tenant context extracted from JWT Bearer token."
    )
    public ResponseEntity<BaseResponse<com.nextturn.infrastructure.security.principal.AuthenticatedUser>> getCurrentUser(
            @com.nextturn.infrastructure.security.annotation.CurrentUser com.nextturn.infrastructure.security.principal.AuthenticatedUser user
    ) {
        if (user == null) {
            throw new com.nextturn.exception.auth.AuthException(
                    com.nextturn.exception.auth.AuthErrorCode.INVALID_TOKEN,
                    "No authenticated user found in security context"
            );
        }
        return ResponseEntity.ok(BaseResponse.ok("Current user profile retrieved successfully", user));
    }
}
