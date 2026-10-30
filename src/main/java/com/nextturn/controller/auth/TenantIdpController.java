package com.nextturn.controller.auth;

import com.nextturn.constant.AuthEndpointConstant;
import com.nextturn.dto.BaseResponse;
import com.nextturn.dto.auth.ConfigureIdpRequest;
import com.nextturn.dto.auth.TenantIdpResponse;
import com.nextturn.infrastructure.security.annotation.RequireTenantAdmin;
import com.nextturn.service.auth.TenantIdpService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Authentication domain REST controller for Tenant Administrators to configure external Identity Providers (IdP) for SSO.
 * Operates purely from the NextTurn Web Portal; Tenant Admins do not configure Keycloak directly.
 */
@RestController
@RequestMapping(AuthEndpointConstant.TENANT_ADMIN_IDP_BASE)
@RequireTenantAdmin
@Tag(name = "Authentication & Tenant SSO", description = "Endpoints for Tenant Administrators to configure enterprise Single Sign-On via Keycloak Organizations")
@SecurityRequirement(name = "bearerAuth")
public class TenantIdpController {

    private final TenantIdpService idpService;

    public TenantIdpController(TenantIdpService idpService) {
        this.idpService = idpService;
    }

    @PostMapping
    @Operation(
            summary = "Configure or update external Identity Provider for tenant SSO",
            description = "Links Google, Microsoft Entra, or OIDC provider to the clinic's Keycloak Organization and registers verified email domains."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Identity Provider linked successfully",
                    content = @Content(schema = @Schema(implementation = BaseResponse.class))),
            @ApiResponse(responseCode = "400", description = "Validation failed for IdP payload",
                    content = @Content(schema = @Schema(implementation = BaseResponse.class)))
    })
    public ResponseEntity<BaseResponse<TenantIdpResponse>> configureIdp(
            @Valid @RequestBody ConfigureIdpRequest request
    ) {
        TenantIdpResponse response = idpService.configureIdp(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(BaseResponse.created("Identity Provider configured successfully", response));
    }

    @GetMapping
    @Operation(summary = "Get configured Identity Provider details", description = "Returns active SSO provider and email domains for caller's clinic.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "IdP configuration retrieved successfully"),
            @ApiResponse(responseCode = "404", description = "No IdP configured for this tenant")
    })
    public ResponseEntity<BaseResponse<TenantIdpResponse>> getIdpConfig() {
        TenantIdpResponse response = idpService.getIdpConfig();
        return ResponseEntity.ok(BaseResponse.ok("Identity Provider configuration retrieved successfully", response));
    }

    @DeleteMapping
    @Operation(summary = "Remove external Identity Provider configuration", description = "Unlinks and deletes the IdP instance from Keycloak.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "IdP removed successfully")
    })
    public ResponseEntity<BaseResponse<Void>> removeIdp() {
        idpService.removeIdp();
        return ResponseEntity.ok(BaseResponse.ok("Identity Provider removed successfully", null));
    }
}
