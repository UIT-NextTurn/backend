package com.nextturn.constant;

/**
 * Constants for Keycloak token claims and security authority prefixes
 * (*các claim đặc thù của Keycloak trong token và tiền tố quyền hạn Spring Security*).
 */
public final class KeycloakClaimConstant {

    private KeycloakClaimConstant() {
        // Prevent instantiation
    }

    public static final String DEFAULT_CLIENT_ID = "nextturn-backend";
    public static final String RESOURCE_ACCESS_CLAIM = "resource_access";
    public static final String REALM_ACCESS_CLAIM = "realm_access";
    public static final String ROLES_CLAIM = "roles";
    public static final String AZP_CLAIM = "azp";
    public static final String ROLE_PREFIX = "ROLE_";
}
