package com.nextturn.infrastructure.security.keycloak;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Configuration properties binding Keycloak server and OAuth2 resource server settings.
 *
 * @param clientId      Keycloak client identifier (default: nextturn-backend)
 * @param clientSecret  Keycloak client secret for confidential clients
 * @param realm         Keycloak realm name (default: nextturn)
 * @param authServerUrl Base Keycloak server URL
 * @param issuerUri     OIDC issuer URI
 * @param jwkSetUri     JWK Set URI for token verification
 */
@ConfigurationProperties(prefix = "keycloak")
public record KeycloakProperties(
        @DefaultValue("nextturn-backend") String clientId,
        @DefaultValue("") String clientSecret,
        @DefaultValue("nextturn") String realm,
        @DefaultValue("http://localhost:8180") String authServerUrl,
        @DefaultValue("http://localhost:8180/realms/nextturn") String issuerUri,
        @DefaultValue("http://localhost:8180/realms/nextturn/protocol/openid-connect/certs") String jwkSetUri
) {
    public KeycloakProperties {
        if (clientId == null || clientId.isBlank()) {
            clientId = "nextturn-backend";
        }
        if (realm == null || realm.isBlank()) {
            realm = "nextturn";
        }
        if (authServerUrl == null || authServerUrl.isBlank() || authServerUrl.equals("http://localhost:8180")) {
            if (issuerUri != null && !issuerUri.isBlank() && !issuerUri.startsWith("http://localhost:8180")) {
                int realmsIdx = issuerUri.indexOf("/realms/");
                authServerUrl = (realmsIdx > 0) ? issuerUri.substring(0, realmsIdx) : issuerUri;
            } else if (authServerUrl == null || authServerUrl.isBlank()) {
                authServerUrl = "http://localhost:8180";
            }
        }
    }

    /**
     * Resolves absolute OAuth2 token endpoint URL (e.g. {@code http://ryan-a1:8180/realms/nextturn/protocol/openid-connect/token}).
     */
    public String tokenEndpoint() {
        if (issuerUri != null && !issuerUri.isBlank()) {
            return issuerUri.replaceAll("/+$", "") + "/protocol/openid-connect/token";
        }
        return String.format("%s/realms/%s/protocol/openid-connect/token",
                authServerUrl.replaceAll("/+$", ""),
                realm);
    }

    /**
     * Resolves absolute OAuth2 authorization endpoint URL (e.g. {@code http://ryan-a1:8180/realms/nextturn/protocol/openid-connect/auth}).
     */
    public String authEndpoint() {
        if (issuerUri != null && !issuerUri.isBlank()) {
            return issuerUri.replaceAll("/+$", "") + "/protocol/openid-connect/auth";
        }
        return String.format("%s/realms/%s/protocol/openid-connect/auth",
                authServerUrl.replaceAll("/+$", ""),
                realm);
    }

    /**
     * Resolves absolute Keycloak Admin Users REST API endpoint (e.g. {@code http://localhost:8180/admin/realms/nextturn/users}).
     */
    public String adminUsersEndpoint() {
        return String.format("%s/admin/realms/%s/users",
                authServerUrl.replaceAll("/+$", ""),
                realm);
    }
}
