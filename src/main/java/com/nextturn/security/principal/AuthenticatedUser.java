package com.nextturn.security.principal;

import com.nextturn.enums.UserRole;
import org.springframework.security.oauth2.jwt.Jwt;

import java.io.Serializable;
import java.util.Arrays;
import java.util.Collections;
import java.util.Set;

/**
 * Immutable representation of an authenticated user principal in the NextTurn application.
 *
 * @param userId   the subject identifier (sub claim)
 * @param email    the email address (email claim or fallback to sub)
 * @param tenantId the identifier/code of the tenant this user belongs to, if applicable
 * @param roles    the set of application roles granted to the user
 * @param jwt      the raw underlying JWT token
 */
public record AuthenticatedUser(
        String userId,
        String email,
        String tenantId,
        Set<UserRole> roles,
        Jwt jwt
) implements Serializable {

    public AuthenticatedUser {
        roles = roles == null ? Collections.emptySet() : Collections.unmodifiableSet(roles);
    }

    public AuthenticatedUser(String userId, String email, String tenantId, Set<UserRole> roles) {
        this(userId, email, tenantId, roles, null);
    }

    /**
     * Checks if the user has a specific role.
     */
    public boolean hasRole(UserRole role) {
        return role != null && roles.contains(role);
    }

    /**
     * Checks if the user has any of the specified roles.
     */
    public boolean hasAnyRole(UserRole... targetRoles) {
        if (targetRoles == null || targetRoles.length == 0) {
            return false;
        }
        return Arrays.stream(targetRoles).anyMatch(this::hasRole);
    }

    /**
     * Checks if the user is a SUPERADMIN.
     */
    public boolean isSuperAdmin() {
        return hasRole(UserRole.SUPERADMIN);
    }

    /**
     * Checks if the user is a TENANT_ADMIN.
     */
    public boolean isTenantAdmin() {
        return hasRole(UserRole.TENANT_ADMIN);
    }

    /**
     * Checks if the user is a COUNTER_STAFF.
     */
    public boolean isCounterStaff() {
        return hasRole(UserRole.COUNTER_STAFF);
    }

    /**
     * Checks if the user is an END_USER.
     */
    public boolean isEndUser() {
        return hasRole(UserRole.END_USER);
    }

    /**
     * Checks if the user is a facility staff member (TENANT_ADMIN or COUNTER_STAFF).
     */
    public boolean isStaff() {
        return hasAnyRole(UserRole.TENANT_ADMIN, UserRole.COUNTER_STAFF);
    }
}
