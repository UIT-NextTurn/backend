package com.nextturn.constant;

/**
 * Constants representing cache key prefixes and keys for Valkey/Redis
 * (*tiền tố và cấu trúc khóa bộ nhớ đệm Valkey*).
 */
public final class ValkeyKeyConstant {

    private ValkeyKeyConstant() {
        // Prevent instantiation
    }

    /**
     * Key prefix for active OTP verification codes.
     */
    public static final String OTP_CODE_PREFIX = "nextturn:otp:code:";

    /**
     * Key prefix for OTP verification failed attempt counters.
     */
    public static final String OTP_ATTEMPTS_PREFIX = "nextturn:otp:attempts:";

    /**
     * Initial attempt counter value.
     */
    public static final String INITIAL_ATTEMPTS_COUNT = "0";

    /**
     * Key prefix for OTP 60-second resend cooldown timer.
     */
    public static final String OTP_COOLDOWN_PREFIX = "nextturn:otp:cooldown:";

    /**
     * Key prefix for OTP 24-hour daily quota limit counters.
     */
    public static final String OTP_DAILY_PREFIX = "nextturn:otp:daily:";

    /**
     * Formats the Redis key for storing an OTP code.
     */
    public static String otpCodeKey(String email) {
        return OTP_CODE_PREFIX + (email != null ? email.trim().toLowerCase() : "");
    }

    /**
     * Formats the Redis key for tracking failed OTP verification attempts.
     */
    public static String otpAttemptsKey(String email) {
        return OTP_ATTEMPTS_PREFIX + (email != null ? email.trim().toLowerCase() : "");
    }

    /**
     * Formats the Redis key for tracking 60-second resend cooldown.
     */
    public static String otpCooldownKey(String contact) {
        return OTP_COOLDOWN_PREFIX + (contact != null ? contact.trim().toLowerCase() : "");
    }

    /**
     * Formats the Redis key for tracking 24-hour daily request count.
     */
    public static String otpDailyKey(String contact) {
        return OTP_DAILY_PREFIX + (contact != null ? contact.trim().toLowerCase() : "");
    }

    /**
     * Key prefix for tenant configuration and operational quotas.
     */
    public static final String TENANT_CONFIG_PREFIX = "nextturn:tenant:config:";

    /**
     * Key prefix for tenant daily tickets telemetry count.
     */
    public static final String TENANT_TODAY_TICKETS_PREFIX = "nextturn:tenant:telemetry:tickets:";

    /**
     * Key prefix for tenant active staff count telemetry.
     */
    public static final String TENANT_ACTIVE_STAFF_PREFIX = "nextturn:tenant:telemetry:staff:";

    /**
     * Standard cache TTL for tenant configurations (24 hours).
     */
    public static final int TENANT_PROFILE_TTL_HOURS = 24;

    /**
     * Formats the Redis key for caching tenant configuration and operational quota limits.
     *
     * @param tenantIdentifier tenant ID or tenant code
     * @return cache key
     */
    public static String tenantConfigKey(String tenantIdentifier) {
        return TENANT_CONFIG_PREFIX + (tenantIdentifier != null ? tenantIdentifier.trim().toLowerCase() : "");
    }

    /**
     * Formats the Redis key for caching a tenant's ticket count today.
     *
     * @param tenantCode tenant code
     * @return cache key
     */
    public static String tenantTodayTicketsKey(String tenantCode) {
        return TENANT_TODAY_TICKETS_PREFIX + (tenantCode != null ? tenantCode.trim().toLowerCase() : "");
    }

    /**
     * Formats the Redis key for caching a tenant's active staff count.
     *
     * @param tenantCode tenant code
     * @return cache key
     */
    public static String tenantActiveStaffKey(String tenantCode) {
        return TENANT_ACTIVE_STAFF_PREFIX + (tenantCode != null ? tenantCode.trim().toLowerCase() : "");
    }

    /**
     * Key prefix for tenant services catalog cache.
     */
    public static final String TENANT_SERVICES_PREFIX = "nextturn:tenant:services:";

    /**
     * Formats the Redis key for caching a tenant's service catalog list.
     *
     * @param tenantCode tenant code
     * @return cache key
     */
    public static String tenantServicesKey(String tenantCode) {
        return TENANT_SERVICES_PREFIX + (tenantCode != null ? tenantCode.trim().toLowerCase() : "");
    }
}
