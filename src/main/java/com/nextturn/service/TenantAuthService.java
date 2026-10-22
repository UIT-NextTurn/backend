package com.nextturn.service;

import com.nextturn.dto.TenantAuthRequest;
import com.nextturn.dto.TenantAuthResponse;
import com.nextturn.entity.CentralTenant;
import com.nextturn.enums.TenantStatus;
import com.nextturn.enums.UserRole;
import com.nextturn.exception.AppException;
import com.nextturn.exception.auth.AuthErrorCode;
import com.nextturn.exception.auth.AuthException;
import com.nextturn.helper.KeycloakAuthHelper;
import com.nextturn.repository.CentralTenantRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Service orchestrating REQ-AUTH-01: Tenant identification and staff login routing.
 * <p>
 * Validates the tenant code against {@code central__tenants} in the master schema,
 * checks active status, and uses {@link KeycloakAuthHelper} to generate the staff login URL.
 */
@Service
public class TenantAuthService {

    private static final Logger log = LoggerFactory.getLogger(TenantAuthService.class);

    public static final List<String> STAFF_ALLOWED_ROLES = List.of(
            UserRole.TENANT_ADMIN_VALUE,
            UserRole.COUNTER_STAFF_VALUE
    );

    private final CentralTenantRepository centralTenantRepository;
    private final KeycloakAuthHelper keycloakAuthHelper;

    public TenantAuthService(
            CentralTenantRepository centralTenantRepository,
            KeycloakAuthHelper keycloakAuthHelper
    ) {
        this.centralTenantRepository = centralTenantRepository;
        this.keycloakAuthHelper = keycloakAuthHelper;
    }

    /**
     * Resolves and authenticates a tenant by their tenant code.
     *
     * @param request the tenant code request payload
     * @return {@link TenantAuthResponse} with tenant information and staff login redirect URL
     * @throws AuthException if tenant is not found (AUTH_003) or is inactive/blocked (AUTH_004)
     */
    @Transactional(readOnly = true)
    public TenantAuthResponse authenticateTenant(TenantAuthRequest request) {
        if (request == null || request.getTenantCode() == null || request.getTenantCode().isBlank()) {
            throw new AuthException(AuthErrorCode.TENANT_CODE_REQUIRED, "Tenant code must not be blank");
        }

        String cleanCode = request.getTenantCode().trim();
        log.info("Processing tenant resolution for code: [{}]", cleanCode);

        CentralTenant tenant = centralTenantRepository.findByTenantCodeIgnoreCaseAndIsDeletedFalse(cleanCode)
                .orElseThrow(() -> {
                    log.warn("Tenant resolution failed: Tenant code [{}] not found or deleted", cleanCode);
                    return new AuthException(AuthErrorCode.TENANT_NOT_FOUND, "Tenant not found with code: " + cleanCode);
                });

        if (tenant.getStatus() != TenantStatus.ACTIVE) {
            log.warn("Tenant resolution rejected: Tenant [{}] has inactive status [{}]", cleanCode, tenant.getStatus());
            throw new AuthException(
                    AuthErrorCode.TENANT_INACTIVE,
                    "Tenant facility is not active. Current status: " + tenant.getStatus()
            );
        }

        String loginUrl = buildStaffLoginUrl(tenant);

        return TenantAuthResponse.builder()
                .tenantId(tenant.getId())
                .tenantCode(tenant.getTenantCode())
                .tenantName(tenant.getTenantName())
                .status(tenant.getStatus().name())
                .organisationId(tenant.getOrganisationId())
                .loginUrl(loginUrl)
                .allowedRoles(STAFF_ALLOWED_ROLES)
                .build();
    }

    /**
     * Delegates staff login URL construction to {@link KeycloakAuthHelper}.
     *
     * @param tenant the authenticated tenant entity
     * @return absolute Keycloak OIDC authorization URL
     */
    public String buildStaffLoginUrl(CentralTenant tenant) {
        return keycloakAuthHelper.buildStaffLoginUrl(tenant);
    }
}
