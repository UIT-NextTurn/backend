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
     * Key prefix for OTP 60-second resend cooldown timer (REQ-AUTH-06).
     */
    public static final String OTP_COOLDOWN_PREFIX = "nextturn:otp:cooldown:";

    /**
     * Key prefix for OTP 24-hour daily quota limit counters (REQ-AUTH-06).
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
}
