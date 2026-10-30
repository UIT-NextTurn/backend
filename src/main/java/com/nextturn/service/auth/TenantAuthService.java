package com.nextturn.service.auth;

import com.nextturn.dto.auth.AuthTokenResponse;
import com.nextturn.dto.auth.LogoutRequest;
import com.nextturn.dto.auth.RefreshTokenRequest;
import com.nextturn.dto.auth.TenantAuthRequest;
import com.nextturn.dto.auth.TenantAuthResponse;
import com.nextturn.dto.auth.TokenExchangeRequest;
import com.nextturn.entity.CentralTenant;
import com.nextturn.enums.TenantStatus;
import com.nextturn.enums.UserRole;
import com.nextturn.exception.auth.AuthErrorCode;
import com.nextturn.exception.auth.AuthException;
import com.nextturn.helper.KeycloakAuthHelper;
import com.nextturn.infrastructure.multitenancy.TenantContextHolder;
import com.nextturn.infrastructure.security.keycloak.KeycloakTokenService;
import com.nextturn.repository.auth.CentralTenantRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.context.SecurityContextHolder;
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

    public static final List<String> MANAGEMENT_ALLOWED_ROLES = List.of(
            UserRole.SUPERADMIN_VALUE,
            UserRole.TENANT_ADMIN_VALUE,
            UserRole.COUNTER_STAFF_VALUE
    );

    private final CentralTenantRepository centralTenantRepository;
    private final KeycloakAuthHelper keycloakAuthHelper;
    private final KeycloakTokenService keycloakTokenService;

    public TenantAuthService(
            CentralTenantRepository centralTenantRepository,
            KeycloakAuthHelper keycloakAuthHelper,
            KeycloakTokenService keycloakTokenService
    ) {
        this.centralTenantRepository = centralTenantRepository;
        this.keycloakAuthHelper = keycloakAuthHelper;
        this.keycloakTokenService = keycloakTokenService;
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

    /**
     * Resolves Super Admin login routing without tenant restriction.
     *
     * @return {@link TenantAuthResponse} pre-configured for Super Admin login
     */
    public TenantAuthResponse resolveSuperAdminLogin() {
        String loginUrl = keycloakAuthHelper.buildSuperAdminLoginUrl();
        return TenantAuthResponse.builder()
                .tenantId(null)
                .tenantCode(null)
                .tenantName("NextTurn Global Platform")
                .status("ACTIVE")
                .loginUrl(loginUrl)
                .allowedRoles(List.of(UserRole.SUPERADMIN_VALUE))
                .build();
    }

    /**
     * Exchanges OAuth2 authorization code with Keycloak, validates tenant status,
     * and strictly rejects END_USER roles.
     *
     * @param request code and optional redirectUri
     * @return {@link AuthTokenResponse} with tokens, verified roles, and routing targets
     */
    public AuthTokenResponse exchangeAuthorizationCode(TokenExchangeRequest request) {
        if (keycloakTokenService == null) {
            throw new AuthException(AuthErrorCode.KEYCLOAK_UNAVAILABLE, "Token service is not configured");
        }
        if (request == null || request.getCode() == null || request.getCode().isBlank()) {
            throw new AuthException(AuthErrorCode.INVALID_TOKEN, "Authorization code must not be blank");
        }

        String redirectUri = (request.getRedirectUri() != null && !request.getRedirectUri().isBlank())
                ? request.getRedirectUri().trim()
                : keycloakAuthHelper.getDefaultRedirectUri();

        AuthTokenResponse response = keycloakTokenService.exchangeAuthorizationCode(request.getCode().trim(), redirectUri);
        validateTenantAffiliationIfPresent(response);
        return response;
    }

    /**
     * Refreshes access tokens via Keycloak.
     *
     * @param request refresh token payload
     * @return {@link AuthTokenResponse} with fresh tokens
     */
    public AuthTokenResponse refreshAccessToken(RefreshTokenRequest request) {
        if (keycloakTokenService == null) {
            throw new AuthException(AuthErrorCode.KEYCLOAK_UNAVAILABLE, "Token service is not configured");
        }
        if (request == null || request.getRefreshToken() == null || request.getRefreshToken().isBlank()) {
            throw new AuthException(AuthErrorCode.INVALID_TOKEN, "Refresh token must not be blank");
        }

        AuthTokenResponse response = keycloakTokenService.refreshAccessToken(request.getRefreshToken().trim());
        validateTenantAffiliationIfPresent(response);
        return response;
    }

    private void validateTenantAffiliationIfPresent(AuthTokenResponse response) {
        if (response != null && response.getTenantCode() != null && !response.getTenantCode().isBlank()) {
            validateTenantCode(response.getTenantCode());
        }
    }

    private void validateTenantCode(String tenantCode) {
        CentralTenant tenant = centralTenantRepository.findByTenantCodeIgnoreCaseAndIsDeletedFalse(tenantCode)
                .orElseThrow(() -> new AuthException(AuthErrorCode.TENANT_NOT_FOUND, "Tenant facility not found: " + tenantCode));
        if (tenant.getStatus() != TenantStatus.ACTIVE) {
            throw new AuthException(AuthErrorCode.TENANT_INACTIVE, "Tenant facility is not active. Status: " + tenant.getStatus());
        }
    }

    /**
     * Logs out the user session, revokes refresh token in Keycloak, and clears Security and Tenant contexts.
     *
     * @param request    optional {@link LogoutRequest} containing the refresh token
     * @param authHeader optional Authorization header
     */
    public void logout(LogoutRequest request, String authHeader) {
        if (request != null && request.refreshToken() != null && !request.refreshToken().isBlank()) {
            if (keycloakTokenService != null) {
                keycloakTokenService.logout(request.refreshToken());
            }
        }
        SecurityContextHolder.clearContext();
        TenantContextHolder.clear();
        log.info("Cleared security context and processed logout request");
    }
}
