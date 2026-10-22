package com.nextturn.multitenancy;

/**
 * Thread-local context holder for the active tenant code in NextTurn's schema-per-tenant architecture.
 * Ensures isolation across concurrent requests and prevents memory leaks (*rò rỉ bộ nhớ luồng*)
 * via {@link #clear()}.
 */
public final class TenantContextHolder {

    private static final ThreadLocal<String> CURRENT_TENANT = new ThreadLocal<>();

    private TenantContextHolder() {
        // Prevent instantiation
    }

    /**
     * Sets the active tenant code for the current thread.
     *
     * @param tenantCode the tenant identifier/code (e.g. "tenant1", "clinic_a")
     */
    public static void setTenantCode(String tenantCode) {
        if (tenantCode == null || tenantCode.isBlank()) {
            CURRENT_TENANT.remove();
        } else {
            CURRENT_TENANT.set(tenantCode.trim());
        }
    }

    /**
     * Retrieves the active tenant code for the current thread.
     *
     * @return the tenant code, or {@code null} if no tenant context is set
     */
    public static String getTenantCode() {
        return CURRENT_TENANT.get();
    }

    /**
     * Alias for {@link #setTenantCode(String)}.
     */
    public static void setTenantId(String tenantId) {
        setTenantCode(tenantId);
    }

    /**
     * Alias for {@link #getTenantCode()}.
     */
    public static String getTenantId() {
        return getTenantCode();
    }

    /**
     * Checks if a tenant context is currently set on the current thread.
     */
    public static boolean hasTenant() {
        String tenantCode = CURRENT_TENANT.get();
        return tenantCode != null && !tenantCode.isBlank();
    }

    /**
     * Clears the active tenant context for the current thread.
     * MUST be called in a {@code finally} block to prevent thread-pool memory leaks.
     */
    public static void clear() {
        CURRENT_TENANT.remove();
    }
}
