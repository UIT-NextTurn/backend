package com.nextturn.dto.auth;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Details of an active external Identity Provider linked to the tenant's Keycloak Organization.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Configured Identity Provider details for tenant SSO")
public class TenantIdpResponse {

    @Schema(description = "Keycloak internal IdP alias", example = "idp-clinic_sai_gon")
    private String alias;

    @Schema(description = "Provider protocol ID", example = "google")
    private String providerId;

    @Schema(description = "Display name", example = "Clinic Saigon Google Workspace")
    private String displayName;

    @Schema(description = "Client ID in external provider", example = "123456789-clinic.apps.googleusercontent.com")
    private String clientId;

    @Schema(description = "Configured email domains for auto-routing SSO", example = "[\"clinic-saigon.vn\"]")
    private List<String> emailDomains;

    @Schema(description = "Whether the IdP is active and enabled", example = "true")
    private boolean enabled;
}
