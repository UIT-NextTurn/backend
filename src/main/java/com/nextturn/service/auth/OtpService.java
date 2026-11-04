package com.nextturn.service.auth;

import com.nextturn.dto.auth.CustomerOtpResponse;

/**
 * Service contract managing OTP generation, distributed caching with Valkey, in-memory rate-limiting,
 * 60-second cooldowns, 24-hour daily quotas, and maximum 3 verification attempts (REQ-AUTH-06 & REQ-AUTH-07).
 */
public interface OtpService {

    /**
     * Generates a cryptographically secure 6-digit OTP, applies cooldown and daily limit validations,
     * persists to Valkey with 5-minute TTL, and asynchronously dispatches the notification (REQ-AUTH-06).
     *
     * @param contact        customer's email address or phone number
     * @param isExistingUser whether this contact corresponds to an existing customer record
     * @return {@link CustomerOtpResponse} containing expiration and cooldown metadata
     */
    CustomerOtpResponse generateAndSendOtp(String contact, boolean isExistingUser);

    /**
     * Verifies the submitted OTP against cached distributed state in Valkey with fallback to local memory (REQ-AUTH-07).
     * Enforces a hard limit of 3 invalid attempts before burning the code.
     *
     * @param contact       customer's email address or phone number
     * @param submittedCode 6-digit verification code entered by user
     */
    void verifyOtp(String contact, String submittedCode);

    /**
     * Clears any active OTP code and attempt counters for the specified contact.
     *
     * @param contact customer's email address or phone number
     */
    void clearOtp(String contact);

    /**
     * Clears cooldowns and daily limit counters (primarily for unit tests and administrative resets).
     *
     * @param contact customer's email address or phone number
     */
    void clearCooldown(String contact);

    /**
     * Resets all OTP states, cooldowns, and daily limits for a contact.
     *
     * @param contact customer's email address or phone number
     */
    void resetAllForContact(String contact);
}
