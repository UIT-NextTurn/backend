package com.nextturn.service.auth.impl;

import com.nextturn.dto.auth.ConfigureIdpRequest;
import com.nextturn.dto.auth.TenantIdpResponse;
import com.nextturn.entity.CentralTenant;
import com.nextturn.exception.admin.AdminErrorCode;
import com.nextturn.exception.admin.AdminException;
import com.nextturn.exception.tenant.TenantErrorCode;
import com.nextturn.exception.tenant.TenantException;
import com.nextturn.infrastructure.security.keycloak.KeycloakTokenService;
import com.nextturn.repository.auth.CentralTenantRepository;
import com.nextturn.service.BaseService;
import com.nextturn.service.auth.TenantIdpService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Default implementation of {@link TenantIdpService} managing external Identity Provider (IdP) integration
 * for enterprise Single Sign-On (SSO).
 */
@Service
public class TenantIdpServiceImpl extends BaseService implements TenantIdpService {

    private static final Logger log = LoggerFactory.getLogger(TenantIdpServiceImpl.class);

    private final CentralTenantRepository centralTenantRepository;
    private final KeycloakTokenService keycloakTokenService;

    @Autowired
    public TenantIdpServiceImpl(
            CentralTenantRepository centralTenantRepository,
            KeycloakTokenService keycloakTokenService,
            @Autowired(required = false) TransactionTemplate transactionTemplate
    ) {
        super(transactionTemplate);
        this.centralTenantRepository = centralTenantRepository;
        this.keycloakTokenService = keycloakTokenService;
    }

    public TenantIdpServiceImpl(
            CentralTenantRepository centralTenantRepository,
            KeycloakTokenService keycloakTokenService
    ) {
        this(centralTenantRepository, keycloakTokenService, null);
    }

    @Override
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

    @Override
    @Transactional(readOnly = true)
    public TenantIdpResponse getIdpConfig() {
        String tenantCode = resolveCurrentTenantCode();
        CentralTenant tenant = resolveCentralTenant(tenantCode);

        TenantIdpResponse response = keycloakTokenService.getOrganizationIdp(tenant.getOrganisationId(), tenantCode);
        if (response == null) {
            throw new AdminException(AdminErrorCode.IDP_NOT_FOUND, "No external Identity Provider is currently configured for this tenant");
        }
        return response;
    }

    @Override
    public void removeIdp() {
        String tenantCode = resolveCurrentTenantCode();
        CentralTenant tenant = resolveCentralTenant(tenantCode);

        keycloakTokenService.removeOrganizationIdp(tenant.getOrganisationId(), tenantCode);
        log.info("Successfully removed external IdP configuration for tenant [{}]", tenantCode);
    }

    private CentralTenant resolveCentralTenant(String tenantCode) {
        return centralTenantRepository.findByTenantCodeAndIsDeletedFalse(tenantCode)
                .orElseThrow(() -> new TenantException(TenantErrorCode.TENANT_NOT_FOUND, "Tenant [" + tenantCode + "] not found in registry"));
    }
}

