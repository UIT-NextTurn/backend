package com.nextturn.constant;

/**
 * Constants representing multi-tenant HTTP headers (*tiêu đề HTTP tùy chỉnh của tenant*).
 * Follows the Single Responsibility Principle by encapsulating HTTP header keys.
 */
public final class TenantHeaderConstant {

    private TenantHeaderConstant() {
        // Prevent instantiation
    }

    /**
     * HTTP Header name for passing the tenant code.
     */
    public static final String X_TENANT_CODE_HEADER = "X-Tenant-Code";

    /**
     * HTTP Header name for passing the tenant ID.
     */
    public static final String X_TENANT_ID_HEADER = "X-Tenant-ID";
}
