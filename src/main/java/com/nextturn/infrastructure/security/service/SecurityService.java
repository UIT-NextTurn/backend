package com.nextturn.infrastructure.security.service;

import com.nextturn.enums.UserRole;
import com.nextturn.infrastructure.security.principal.AuthenticatedUser;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Collections;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Custom Authorization Service exposed to Spring EL expressions in `@PreAuthorize("@authz...")`.
 * Provides role checks for SUPERADMIN, TENANT_ADMIN, COUNTER_STAFF, and END_USER,
 * as well as user identity extraction for multi-tenant context.
 */
@Component("authz")
public class SecurityService {

    private final SecurityContextHelper securityContextHelper;

    @org.springframework.beans.factory.annotation.Autowired
    public SecurityService(SecurityContextHelper securityContextHelper) {
        this.securityContextHelper = securityContextHelper;
    }

    public SecurityService() {
        this(new SecurityContextHelper());
    }

    /**
     * Checks if current authenticated principal has the specified role.
     * Accepts both raw role ("SUPERADMIN") or prefixed ("ROLE_SUPERADMIN").
     */
    public boolean hasRole(String roleName) {
        if (roleName == null || roleName.isBlank()) {
            return false;
        }
        String expectedAuthority = roleName.startsWith("ROLE_") ? roleName : "ROLE_" + roleName;
        return getAuthorities().stream()
                .anyMatch(granted -> granted.getAuthority().equalsIgnoreCase(expectedAuthority));
    }

    /**
     * Overload for type-safe UserRole enum check.
     */
    public boolean hasRole(UserRole role) {
        return role != null && hasRole(role.getAuthority());
    }

    /**
     * Checks if current authenticated principal has any of the specified roles.
     */
    public boolean hasAnyRole(String... roleNames) {
        if (roleNames == null || roleNames.length == 0) {
            return false;
        }
        return Arrays.stream(roleNames).anyMatch(this::hasRole);
    }

    /**
     * Overload for type-safe UserRole enum varargs check.
     */
    public boolean hasAnyRole(UserRole... roles) {
        if (roles == null || roles.length == 0) {
            return false;
        }
        return Arrays.stream(roles).anyMatch(this::hasRole);
    }

    /**
     * Convenience check: is current user SUPERADMIN?
     */
    public boolean isSuperAdmin() {
        return hasRole(UserRole.SUPERADMIN);
    }

    /**
     * Convenience check: is current user TENANT_ADMIN?
     */
    public boolean isTenantAdmin() {
        return hasRole(UserRole.TENANT_ADMIN);
    }

    /**
     * Convenience check: is current user COUNTER_STAFF?
     */
    public boolean isCounterStaff() {
        return hasRole(UserRole.COUNTER_STAFF);
    }

    /**
     * Convenience check: is current user END_USER?
     */
    public boolean isEndUser() {
        return hasRole(UserRole.END_USER);
    }

    /**
     * Convenience check: is current user a facility staff member (TENANT_ADMIN or COUNTER_STAFF)?
     */
    public boolean isStaff() {
        return hasAnyRole(UserRole.TENANT_ADMIN, UserRole.COUNTER_STAFF);
    }

    /**
     * Checks if user is authenticated and not anonymous.
     */
    public boolean isAuthenticated() {
        return securityContextHelper.isAuthenticated();
    }

    /**
     * Returns the JWT token of the current authenticated user if available.
     */
    public Optional<Jwt> getCurrentJwt() {
        return securityContextHelper.getCurrentJwt();
    }

    /**
     * Extracts email from the active authenticated user.
     */
    public Optional<String> getCurrentUserEmail() {
        return securityContextHelper.getCurrentUserEmail();
    }

    /**
     * Extracts subject ID (sub UUID) from the active authenticated user.
     */
    public Optional<String> getCurrentUserId() {
        return securityContextHelper.getCurrentUserId();
    }

    /**
     * Extracts tenant identifier from the active authenticated user.
     */
    public Optional<String> getCurrentTenantId() {
        return securityContextHelper.getCurrentTenantId();
    }

    /**
     * Retrieves all UserRoles assigned to the current user.
     */
    public Set<UserRole> getCurrentUserRoles() {
        return securityContextHelper.getCurrentUserRoles();
    }

    /**
     * Retrieves the current AuthenticatedUser principal.
     */
    public Optional<AuthenticatedUser> getCurrentUser() {
        return securityContextHelper.getCurrentUser();
    }

    private Set<GrantedAuthority> getAuthorities() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getAuthorities() == null) {
            return Collections.emptySet();
        }
        return auth.getAuthorities().stream()
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
    }
}
