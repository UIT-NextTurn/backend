package com.nextturn.enums;

/**
 * Supported external Identity Provider (IdP) protocol and provider types for Keycloak Organizations.
 */
public enum IdpProviderType {
    GOOGLE("google"),
    MICROSOFT_ENTRA("microsoft"),
    OIDC("oidc"),
    SAML("saml");

    private final String keycloakProviderId;

    IdpProviderType(String keycloakProviderId) {
        this.keycloakProviderId = keycloakProviderId;
    }

    public String getKeycloakProviderId() {
        return keycloakProviderId;
    }
}
