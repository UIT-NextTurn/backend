package com.nextturn.controller.auth;

import com.nextturn.constant.AuthEndpointConstant;
import com.nextturn.dto.BaseResponse;
import com.nextturn.dto.auth.AuthTokenResponse;
import com.nextturn.dto.auth.RefreshTokenRequest;
import com.nextturn.dto.auth.TenantAuthRequest;
import com.nextturn.dto.auth.TenantAuthResponse;
import com.nextturn.dto.auth.TokenExchangeRequest;
import com.nextturn.service.auth.TenantAuthService;
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
@RequestMapping(AuthEndpointConstant.AUTH_BASE)
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
    @PostMapping(AuthEndpointConstant.TENANT_VERIFY)
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
    public ResponseEntity<BaseResponse<TenantAuthResponse>> verifyTenantPost(
            @Valid @RequestBody TenantAuthRequest request
    ) {
        TenantAuthResponse response = tenantAuthService.authenticateTenant(request);
        return ResponseEntity.ok(BaseResponse.ok("Tenant recognized successfully", response));
    }

    /**
     * GET endpoint supporting quick browser URL query / path parameter tenant lookup.
     *
     * @param tenantCode alphanumeric tenant code from URL path
     * @return standard {@link BaseResponse} wrapping {@link TenantAuthResponse}
     */
    @GetMapping({AuthEndpointConstant.TENANT_BY_CODE, AuthEndpointConstant.TENANT_VERIFY_BY_CODE})
    @Operation(
            summary = "Verify tenant code via path variable (GET)",
            description = "Idempotent tenant resolution via path variable for deep-linking or QR code scans."
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "Tenant recognized successfully and login URL generated",
                    content = @Content(schema = @Schema(implementation = BaseResponse.class))
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "Invalid tenant code format",
                    content = @Content(schema = @Schema(implementation = BaseResponse.class))
            ),
            @ApiResponse(
                    responseCode = "403",
                    description = "Tenant facility is inactive or blocked",
                    content = @Content(schema = @Schema(implementation = BaseResponse.class))
            ),
            @ApiResponse(
                    responseCode = "404",
                    description = "Tenant code not found in central registry",
                    content = @Content(schema = @Schema(implementation = BaseResponse.class))
            )
    })
    public ResponseEntity<BaseResponse<TenantAuthResponse>> verifyTenantGet(
            @Parameter(description = "Alphanumeric unique code for tenant facility", example = "clinic_sai_gon")
            @PathVariable String tenantCode
    ) {
        TenantAuthRequest request = TenantAuthRequest.builder()
                .tenantCode(tenantCode)
                .build();
        TenantAuthResponse response = tenantAuthService.authenticateTenant(request);
        return ResponseEntity.ok(BaseResponse.ok("Tenant recognized successfully", response));
    }

    /**
     * Resolves Super Admin login routing without tenant restriction.
     *
     * @return {@link BaseResponse} wrapping {@link TenantAuthResponse} for Super Admin
     */
    @GetMapping(AuthEndpointConstant.SUPERADMIN_LOGIN_URL)
    @Operation(
            summary = "Resolve platform-wide Super Admin login URL",
            description = "Returns pre-configured Keycloak authorization URL without tenant-specific parameters for platform governance."
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "Super Admin login URL generated successfully",
                    content = @Content(schema = @Schema(implementation = BaseResponse.class))
            )
    })
    public ResponseEntity<BaseResponse<TenantAuthResponse>> getSuperAdminLoginUrl() {
        TenantAuthResponse response = tenantAuthService.resolveSuperAdminLogin();
        return ResponseEntity.ok(BaseResponse.ok("Super Admin route resolved successfully", response));
    }

    /**
     * Exchanges OAuth2 authorization code with Keycloak for JWT access/refresh tokens.
     *
     * @param request code and optional redirectUri
     * @return standard {@link BaseResponse} wrapping {@link AuthTokenResponse}
     */
    @PostMapping({AuthEndpointConstant.TOKEN_EXCHANGE, AuthEndpointConstant.TOKEN_EXCHANGE_ALIAS})
    @Operation(
            summary = "Exchange authorization code for JWT tokens",
            description = "Exchanges Keycloak authorization code for tokens, verifies staff roles, and returns user session details."
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "Tokens exchanged successfully",
                    content = @Content(schema = @Schema(implementation = BaseResponse.class))
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "Invalid or expired authorization code",
                    content = @Content(schema = @Schema(implementation = BaseResponse.class))
            ),
            @ApiResponse(
                    responseCode = "403",
                    description = "User does not have required staff role",
                    content = @Content(schema = @Schema(implementation = BaseResponse.class))
            )
    })
    public ResponseEntity<BaseResponse<AuthTokenResponse>> exchangeCode(
            @Valid @RequestBody TokenExchangeRequest request
    ) {
        AuthTokenResponse response = tenantAuthService.exchangeAuthorizationCode(request);
        return ResponseEntity.ok(BaseResponse.ok("Tokens exchanged successfully", response));
    }

    /**
     * Refreshes access tokens via Keycloak.
     *
     * @param request payload containing refresh token
     * @return standard {@link BaseResponse} wrapping refreshed {@link AuthTokenResponse}
     */
    @PostMapping(AuthEndpointConstant.TOKEN_REFRESH)
    @Operation(
            summary = "Refresh expired access token",
            description = "Submits an active refresh token to Keycloak to obtain a fresh access token."
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "Access token refreshed successfully",
                    content = @Content(schema = @Schema(implementation = BaseResponse.class))
            ),
            @ApiResponse(
                    responseCode = "401",
                    description = "Refresh token expired or invalid",
                    content = @Content(schema = @Schema(implementation = BaseResponse.class))
            )
    })
    public ResponseEntity<BaseResponse<AuthTokenResponse>> refreshToken(
            @Valid @RequestBody RefreshTokenRequest request
    ) {
        AuthTokenResponse response = tenantAuthService.refreshAccessToken(request);
        return ResponseEntity.ok(BaseResponse.ok("Access token refreshed successfully", response));
    }
}
