package com.nextturn.security.keycloak;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Configuration properties binding Keycloak server and OAuth2 resource server settings.
 *
 * @param clientId      Keycloak client identifier (default: nextturn-backend)
 * @param realm         Keycloak realm name (default: nextturn)
 * @param authServerUrl Base Keycloak server URL
 * @param issuerUri     OIDC issuer URI
 * @param jwkSetUri     JWK Set URI for token verification
 */
@ConfigurationProperties(prefix = "keycloak")
public record KeycloakProperties(
        @DefaultValue("nextturn-backend") String clientId,
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
        if (authServerUrl == null || authServerUrl.isBlank()) {
            authServerUrl = "http://localhost:8180";
        }
    }
}
