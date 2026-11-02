package com.nextturn.dto.auth;

import com.nextturn.enums.IdpProviderType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Payload submitted by a TENANT_ADMIN to link an external Identity Provider (SSO) to their Keycloak Organization.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "External Identity Provider configuration payload for Keycloak Organization SSO")
public class ConfigureIdpRequest {

    @NotNull(message = "Identity provider type is required")
    @Schema(description = "Provider protocol or identity broker", example = "GOOGLE", allowableValues = {"GOOGLE", "MICROSOFT_ENTRA", "OIDC", "SAML"})
    private IdpProviderType providerType;

    @NotBlank(message = "Display name is required")
    @Schema(description = "Human-readable name displayed on login button", example = "Clinic Saigon Google Workspace")
    private String displayName;

    @NotBlank(message = "Client ID is required")
    @Schema(description = "OAuth2 / OIDC Client ID from external identity provider", example = "123456789-clinic.apps.googleusercontent.com")
    private String clientId;

    @NotBlank(message = "Client Secret is required")
    @Schema(description = "OAuth2 / OIDC Client Secret from external identity provider", example = "GOCSPX-very-secret-key")
    private String clientSecret;

    @Schema(description = "OpenID Connect discovery URL or metadata URL (required for generic OIDC/Microsoft Entra)", example = "https://accounts.google.com/.well-known/openid-configuration")
    private String discoveryUrl;

    @NotBlank(message = "Email domain is required")
    @Schema(description = "Corporate email domain routed to this IdP (e.g. staff entering @clinic-saigon.vn will automatically SSO)", example = "clinic-saigon.vn")
    private String emailDomain;
}
