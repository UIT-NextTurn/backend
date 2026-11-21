package com.nextturn.helper;
 
import com.nextturn.constant.OAuth2Constant;
import com.nextturn.entity.CentralTenant;
import com.nextturn.infrastructure.security.keycloak.KeycloakProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Stateless helper component for generating Keycloak and OIDC authentication URLs.
 * Encapsulates URL formatting and query parameter composition to support the service layer.
 * <p>
 * This helper contains no persistence or database repository dependencies.
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
        return buildStaffLoginUrl(
                tenant.getTenantCode(),
                tenant.getOrganisationId(),
                tenant.getTenantName(),
                tenant.getLogoUrl()
        );
    }

    /**
     * Constructs the Keycloak authorization URL with tenant code and optional organization ID.
     *
     * @param tenantCode     the unique tenant code (e.g. "clinic_sai_gon")
     * @param organisationId the Keycloak organization identifier (optional)
     * @return absolute Keycloak OIDC authorization URL
     */
    public String buildStaffLoginUrl(String tenantCode, String organisationId) {
        return buildStaffLoginUrl(tenantCode, organisationId, null, null);
    }

    /**
     * Constructs the Keycloak authorization URL with full tenant branding parameters (Method 2).
     *
     * @param tenantCode     the unique tenant code
     * @param organisationId the Keycloak organization identifier (optional)
     * @param tenantName     the human-readable facility name
     * @param logoUrl        the URL of the tenant's custom logo image (optional)
     * @return absolute Keycloak OIDC authorization URL
     */
    public String buildStaffLoginUrl(String tenantCode, String organisationId, String tenantName, String logoUrl) {
        String authEndpoint = keycloakProperties.authEndpoint();

        UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(authEndpoint)
                .queryParam(OAuth2Constant.PARAM_CLIENT_ID, keycloakProperties.clientId())
                .queryParam(OAuth2Constant.PARAM_RESPONSE_TYPE, OAuth2Constant.RESPONSE_TYPE_CODE)
                .queryParam(OAuth2Constant.PARAM_SCOPE, OAuth2Constant.SCOPE_OPENID_ORGANIZATION)
                .queryParam(OAuth2Constant.PARAM_REDIRECT_URI, defaultRedirectUri);

        if (tenantCode != null && !tenantCode.isBlank()) {
            builder.queryParam(OAuth2Constant.PARAM_TENANT_CODE, tenantCode.trim());
        }

        // When Keycloak Organization feature is linked, pass organization parameter
        if (organisationId != null && !organisationId.isBlank()) {
            builder.queryParam(OAuth2Constant.PARAM_ORGANIZATION, organisationId.trim());
        }

        if (tenantName != null && !tenantName.isBlank()) {
            builder.queryParam(OAuth2Constant.PARAM_TENANT_NAME, tenantName.trim());
        }

        if (logoUrl != null && !logoUrl.isBlank()) {
            builder.queryParam(OAuth2Constant.PARAM_LOGO_URL, logoUrl.trim());
        }

        return builder.toUriString();
    }

    /**
     * Constructs the Keycloak authorization URL for platform-wide Super Admin login.
     * Attaches portal=superadmin to trigger NextTurn platform branding in the login theme.
     *
     * @return absolute Keycloak OIDC authorization URL for Super Admin
     */
    public String buildSuperAdminLoginUrl() {
        String authEndpoint = keycloakProperties.authEndpoint();

        return UriComponentsBuilder.fromUriString(authEndpoint)
                .queryParam(OAuth2Constant.PARAM_CLIENT_ID, keycloakProperties.clientId())
                .queryParam(OAuth2Constant.PARAM_RESPONSE_TYPE, OAuth2Constant.RESPONSE_TYPE_CODE)
                .queryParam(OAuth2Constant.PARAM_SCOPE, OAuth2Constant.SCOPE_OPENID)
                .queryParam(OAuth2Constant.PARAM_REDIRECT_URI, defaultRedirectUri)
                .queryParam(OAuth2Constant.PARAM_PORTAL, "superadmin")
                .toUriString();
    }

    public String getDefaultRedirectUri() {
        return defaultRedirectUri;
    }

    public KeycloakProperties getKeycloakProperties() {
        return keycloakProperties;
    }
}
