package com.nextturn.service.auth;

import com.nextturn.dto.auth.ConfigureIdpRequest;
import com.nextturn.dto.auth.TenantIdpResponse;
import com.nextturn.entity.CentralTenant;
import com.nextturn.exception.admin.AdminErrorCode;
import com.nextturn.exception.admin.AdminException;
import com.nextturn.exception.tenant.TenantErrorCode;
import com.nextturn.exception.tenant.TenantException;
import com.nextturn.infrastructure.multitenancy.TenantContextHolder;
import com.nextturn.infrastructure.security.keycloak.KeycloakTokenService;
import com.nextturn.repository.auth.CentralTenantRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Service managing external Identity Provider (IdP) integration for enterprise Single Sign-On (SSO).
 * Connects the tenant facility's Keycloak Organization to external providers (Google, Microsoft Entra, OIDC, SAML)
 * and verifies domain routing. Belongs to the authentication domain.
 */
@Service
public class TenantIdpService {

    private static final Logger log = LoggerFactory.getLogger(TenantIdpService.class);

    private final CentralTenantRepository centralTenantRepository;
    private final KeycloakTokenService keycloakTokenService;
    private final TransactionTemplate transactionTemplate;

    @Autowired
    public TenantIdpService(
            CentralTenantRepository centralTenantRepository,
            KeycloakTokenService keycloakTokenService,
            @Autowired(required = false) TransactionTemplate transactionTemplate
    ) {
        this.centralTenantRepository = centralTenantRepository;
        this.keycloakTokenService = keycloakTokenService;
        this.transactionTemplate = transactionTemplate;
    }

    public TenantIdpService(
            CentralTenantRepository centralTenantRepository,
            KeycloakTokenService keycloakTokenService
    ) {
        this(centralTenantRepository, keycloakTokenService, null);
    }

    private <T> T executeInTransaction(TransactionCallback<T> action) {
        if (transactionTemplate != null) {
            return transactionTemplate.execute(action);
        }
        return action.doInTransaction(null);
    }

    /**
     * Configures and associates an external Identity Provider with the tenant's Keycloak Organization.
     * Also registers the clinic's corporate email domain for automatic SSO redirection.
     *
     * @param request provider configuration payload
     * @return active {@link TenantIdpResponse}
     */
    public TenantIdpResponse configureIdp(ConfigureIdpRequest request) {
        String tenantCode = resolveCurrentTenantCode();
        CentralTenant tenant = resolveCentralTenant(tenantCode);

        String orgId = tenant.getOrganisationId();
        if (orgId == null || orgId.isBlank()) {
            orgId = keycloakTokenService.ensureOrganizationExists(tenantCode, tenant.getTenantName());
            final String finalOrgId = orgId;
            executeInTransaction(status -> {
                tenant.setOrganisationId(finalOrgId);
                return centralTenantRepository.save(tenant);
            });
        }

        log.info("Configuring external IdP [{}] for organization [{}] of tenant [{}]",
                request.getProviderType(), orgId, tenantCode);

        return keycloakTokenService.configureOrganizationIdp(orgId, tenantCode, request);
    }

    /**
     * Retrieves current external Identity Provider configuration for the caller's tenant.
     *
     * @return active {@link TenantIdpResponse} or throws {@link AdminException} if not found
     */
    @Transactional(readOnly = true)
    public TenantIdpResponse getIdpConfig() {
        String tenantCode = resolveCurrentTenantCode();
        CentralTenant tenant = resolveCentralTenant(tenantCode);

        TenantIdpResponse response = keycloakTokenService.getOrganizationIdp(tenant.getOrganisationId(), tenantCode);
        if (response == null) {
            throw new AdminException(AdminErrorCode.IDP_NOT_FOUND, "No external Identity Provider is currently configured for this clinic");
        }
        return response;
    }

    /**
     * Removes external Identity Provider link and deletes the IdP instance from Keycloak.
     */
    public void removeIdp() {
        String tenantCode = resolveCurrentTenantCode();
        CentralTenant tenant = resolveCentralTenant(tenantCode);

        keycloakTokenService.removeOrganizationIdp(tenant.getOrganisationId(), tenantCode);
        log.info("Successfully removed external IdP configuration for tenant [{}]", tenantCode);
    }

    private String resolveCurrentTenantCode() {
        String tenantCode = TenantContextHolder.getTenantCode();
        if (tenantCode == null || tenantCode.isBlank()) {
            throw new TenantException(TenantErrorCode.TENANT_CONTEXT_MISSING);
        }
        return tenantCode.trim();
    }

    private CentralTenant resolveCentralTenant(String tenantCode) {
        return centralTenantRepository.findByTenantCodeAndIsDeletedFalse(tenantCode)
                .orElseThrow(() -> new TenantException(TenantErrorCode.TENANT_NOT_FOUND, "Tenant [" + tenantCode + "] not found in registry"));
    }
}
