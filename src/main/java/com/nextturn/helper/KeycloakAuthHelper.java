package com.nextturn.helper;

import com.nextturn.entity.CentralTenant;
import com.nextturn.infrastructure.security.keycloak.KeycloakProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Helper component for generating Keycloak and OIDC authentication URLs.
 * Encapsulates URL formatting and query parameter composition for login flows.
 */
@Component
public class KeycloakAuthHelper {

    private final KeycloakProperties keycloakProperties;
    private final String defaultRedirectUri;

    @Autowired
    public KeycloakAuthHelper(
            KeycloakProperties keycloakProperties,
            @Value("${app.auth.default-redirect-uri:http://localhost:3000/auth/callback}") String defaultRedirectUri
    ) {
        this.keycloakProperties = keycloakProperties;
        this.defaultRedirectUri = (defaultRedirectUri != null && !defaultRedirectUri.isBlank())
                ? defaultRedirectUri
                : "http://localhost:3000/auth/callback";
    }

    public KeycloakAuthHelper(KeycloakProperties keycloakProperties) {
        this(keycloakProperties, "http://localhost:3000/auth/callback");
    }

    /**
     * Constructs the Keycloak authorization URL pre-configured with tenant organization scope and parameters.
     *
     * @param tenant the tenant entity
     * @return absolute Keycloak OIDC authorization URL, or {@code null} if tenant is null
     */
    public String buildStaffLoginUrl(CentralTenant tenant) {
        if (tenant == null) {
            return null;
        }
        return buildStaffLoginUrl(tenant.getTenantCode(), tenant.getOrganisationId());
    }

    /**
     * Constructs the Keycloak authorization URL with tenant code and optional organization ID.
     *
     * @param tenantCode     the unique tenant code (e.g. "clinic_sai_gon")
     * @param organisationId the Keycloak organization identifier (optional)
     * @return absolute Keycloak OIDC authorization URL
     */
    public String buildStaffLoginUrl(String tenantCode, String organisationId) {
        String authEndpoint = String.format("%s/realms/%s/protocol/openid-connect/auth",
                keycloakProperties.authServerUrl(),
                keycloakProperties.realm());

        UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(authEndpoint)
                .queryParam("client_id", keycloakProperties.clientId())
                .queryParam("response_type", "code")
                .queryParam("scope", "openid profile email organization")
                .queryParam("redirect_uri", defaultRedirectUri);

        if (tenantCode != null && !tenantCode.isBlank()) {
            builder.queryParam("tenant_code", tenantCode.trim());
        }

        // When Keycloak Organization feature is linked, pass organization parameter
        if (organisationId != null && !organisationId.isBlank()) {
            builder.queryParam("organization", organisationId.trim());
        }

        return builder.toUriString();
    }
}
