package com.nextturn.service.auth;

import com.nextturn.dto.auth.AuthTokenResponse;
import com.nextturn.dto.auth.LogoutRequest;
import com.nextturn.dto.auth.RefreshTokenRequest;
import com.nextturn.dto.auth.TenantAuthRequest;
import com.nextturn.dto.auth.TenantAuthResponse;
import com.nextturn.dto.auth.TokenExchangeRequest;
import com.nextturn.entity.CentralTenant;
import com.nextturn.enums.UserRole;

import java.util.List;

/**
 * Service orchestrating REQ-AUTH-01: Tenant identification and staff login routing.
 */
public interface TenantAuthService {

    List<String> STAFF_ALLOWED_ROLES = List.of(
            UserRole.TENANT_ADMIN_VALUE,
            UserRole.COUNTER_STAFF_VALUE
    );

    List<String> MANAGEMENT_ALLOWED_ROLES = List.of(
            UserRole.SUPERADMIN_VALUE,
            UserRole.TENANT_ADMIN_VALUE,
            UserRole.COUNTER_STAFF_VALUE
    );

    TenantAuthResponse authenticateTenant(TenantAuthRequest request);

    String buildStaffLoginUrl(CentralTenant tenant);

    TenantAuthResponse resolveSuperAdminLogin();

    AuthTokenResponse exchangeAuthorizationCode(TokenExchangeRequest request);

    AuthTokenResponse refreshAccessToken(RefreshTokenRequest request);

    void logout(LogoutRequest request, String authHeader);
}
