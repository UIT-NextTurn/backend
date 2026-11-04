package com.nextturn.service.auth;

import com.nextturn.dto.auth.ConfigureIdpRequest;
import com.nextturn.dto.auth.TenantIdpResponse;

/**
 * Service contract managing external Identity Provider (IdP) integration for enterprise Single Sign-On (SSO).
 * Connects the tenant's Keycloak Organization to external providers (Google, Microsoft Entra, OIDC, SAML)
 * and verifies domain routing.
 */
public interface TenantIdpService {

    /**
     * Configures and associates an external Identity Provider with the tenant's Keycloak Organization.
     * Also registers the tenant's corporate email domain for automatic SSO redirection.
     *
     * @param request provider configuration payload
     * @return active {@link TenantIdpResponse}
     */
    TenantIdpResponse configureIdp(ConfigureIdpRequest request);

    /**
     * Retrieves current external Identity Provider configuration for the caller's tenant.
     *
     * @return active {@link TenantIdpResponse}
     */
    TenantIdpResponse getIdpConfig();

    /**
     * Removes external Identity Provider link and deletes the IdP instance from Keycloak.
     */
    void removeIdp();
}
