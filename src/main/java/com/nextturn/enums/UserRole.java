package com.nextturn.enums;

import java.util.Arrays;
import java.util.Optional;

/**
 * Core user roles for NextTurn multi-tenant virtual queue and appointment platform.
 * Supports standard Spring Security role prefixing ("ROLE_") and Keycloak client role extraction.
 */
public enum UserRole {
    SUPERADMIN(UserRole.SUPERADMIN_VALUE),
    TENANT_ADMIN(UserRole.TENANT_ADMIN_VALUE),
    COUNTER_STAFF(UserRole.COUNTER_STAFF_VALUE),
    END_USER(UserRole.END_USER_VALUE);

    public static final String SUPERADMIN_VALUE = "SUPERADMIN";
    public static final String TENANT_ADMIN_VALUE = "TENANT_ADMIN";
    public static final String COUNTER_STAFF_VALUE = "COUNTER_STAFF";
    public static final String END_USER_VALUE = "END_USER";

    public static final String ROLE_SUPERADMIN = "ROLE_SUPERADMIN";
    public static final String ROLE_TENANT_ADMIN = "ROLE_TENANT_ADMIN";
    public static final String ROLE_COUNTER_STAFF = "ROLE_COUNTER_STAFF";
    public static final String ROLE_END_USER = "ROLE_END_USER";

    private final String roleName;

    UserRole(String roleName) {
        this.roleName = roleName;
    }

    public String getRoleName() {
        return roleName;
    }

    public String getAuthority() {
        return "ROLE_" + roleName;
    }

    /**
     * Case-insensitive parsing supporting both raw names (e.g. "SUPERADMIN")
     * and prefixed authority strings (e.g. "ROLE_SUPERADMIN").
     */
    public static Optional<UserRole> fromString(String rawRole) {
        if (rawRole == null || rawRole.isBlank()) {
            return Optional.empty();
        }
        String cleanRole = rawRole.trim().toUpperCase();
        if (cleanRole.startsWith("ROLE_")) {
            cleanRole = cleanRole.substring(5);
        }
        final String target = cleanRole;
        return Arrays.stream(values())
                .filter(role -> role.roleName.equalsIgnoreCase(target))
                .findFirst();
    }
}
