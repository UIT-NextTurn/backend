package com.nextturn.service.staff;

import com.nextturn.entity.TenantStaff;

import java.util.List;

/**
 * Service contract managing enterprise Identity Provider (IdP) SSO login synchronization
 * and Just-In-Time (JIT) provisioning for tenant staff records.
 */
public interface TenantStaffSyncService {

    /**
     * Synchronizes existing staff profile with claims from Keycloak/IdP upon login,
     * or Just-In-Time (JIT) provisions a new staff record in the tenant database if the user
     * authenticated via enterprise SSO.
     *
     * @param tenantCode the verified tenant code
     * @param keycloakUserId Keycloak sub UUID
     * @param email authenticated email address
     * @param fullName full name claim from IdP / token
     * @param roles client roles claim from token
     * @return updated or newly provisioned {@link TenantStaff}
     */
    TenantStaff syncOrProvisionStaffOnLogin(
            String tenantCode,
            String keycloakUserId,
            String email,
            String fullName,
            List<String> roles
    );
}
