package com.nextturn.constant;

/**
 * Constants representing multi-tenant and organization JWT claims (*các claim xác thực tenant trong JWT*).
 * Follows the Single Responsibility Principle by encapsulating token claim keys.
 */
public final class TenantClaimConstant {

    private TenantClaimConstant() {
        // Prevent instantiation
    }

    /**
     * Primary JWT claim key for tenant identification.
     */
    public static final String TENANT_CODE_CLAIM = "tenant_code";

    /**
     * Secondary JWT claim key (snake_case) for tenant ID fallback.
     */
    public static final String TENANT_ID_CLAIM = "tenant_id";

    /**
     * Secondary JWT claim key (camelCase) for tenant ID fallback.
     */
    public static final String TENANT_ID_CAMEL_CLAIM = "tenantId";

    /**
     * Generic tenant claim key.
     */
    public static final String TENANT_CLAIM = "tenant";

    /**
     * Keycloak organization claim key (can be String or nested Map).
     */
    public static final String ORGANIZATION_CLAIM = "organization";
}
