package com.nextturn.service.auth;

import com.nextturn.constant.ValkeyKeyConstant;
import com.nextturn.dto.auth.CustomerOtpResponse;
import com.nextturn.exception.auth.AuthErrorCode;
import com.nextturn.exception.auth.AuthException;
import com.nextturn.service.notification.EmailService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Service managing OTP generation, distributed caching with Valkey, in-memory rate-limiting,
 * 60-second cooldowns, 24-hour daily quotas, and maximum 3 verification attempts (REQ-AUTH-06 & REQ-AUTH-07).
 */
@Service
public class OtpService {

    private static final Logger log = LoggerFactory.getLogger(OtpService.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final StringRedisTemplate redisTemplate;
    private final EmailService emailService;
    private final long ttlSeconds;
    private final int maxAttempts;
    private final long cooldownSeconds;
    private final int maxDailyRequests;

    // Concurrent in-memory stores for resilience, offline execution, and unit testing
    private final Map<String, OtpRecord> otpStore = new ConcurrentHashMap<>();
    private final Map<String, Instant> cooldownStore = new ConcurrentHashMap<>();
    private final Map<String, DailyLimitRecord> dailyStore = new ConcurrentHashMap<>();

    @Autowired
    public OtpService(
            @Autowired(required = false) StringRedisTemplate redisTemplate,
            @Autowired(required = false) EmailService emailService,
            @Value("${app.auth.otp.ttl-seconds:300}") long ttlSeconds,
            @Value("${app.auth.otp.max-attempts:3}") int maxAttempts,
            @Value("${app.auth.otp.cooldown-seconds:60}") long cooldownSeconds,
            @Value("${app.auth.otp.max-daily-requests:5}") int maxDailyRequests
    ) {
        this.redisTemplate = redisTemplate;
        this.emailService = emailService != null ? emailService : new EmailService(null, "", "", "no-reply@nextturn.com", "NextTurn");
        this.ttlSeconds = ttlSeconds;
        this.maxAttempts = maxAttempts;
        this.cooldownSeconds = cooldownSeconds;
        this.maxDailyRequests = maxDailyRequests;
    }

    public OtpService(
            StringRedisTemplate redisTemplate,
            EmailService emailService,
            long ttlSeconds,
            int maxAttempts
    ) {
        this(redisTemplate, emailService, ttlSeconds, maxAttempts, 60L, 5);
    }

    public OtpService(StringRedisTemplate redisTemplate, long ttlSeconds, int maxAttempts) {
        this(redisTemplate, null, ttlSeconds, maxAttempts, 60L, 5);
    }

    public OtpService(long ttlSeconds, int maxAttempts) {
        this(null, null, ttlSeconds, maxAttempts, 60L, 5);
    }

    /**
     * Generates a cryptographically secure 6-digit OTP, applies cooldown and daily limit validations,
     * persists to Valkey with 5-minute TTL, and asynchronously dispatches the notification (REQ-AUTH-06).
     *
     * @param contact        customer's email address or phone number
     * @param isExistingUser whether this contact corresponds to an existing customer record
     * @return {@link CustomerOtpResponse} containing expiration and cooldown metadata
     * @throws AuthException if cooldown is active (429) or daily limit is exceeded (429)
     */
    public CustomerOtpResponse generateAndSendOtp(String contact, boolean isExistingUser) {
        if (contact == null || contact.isBlank()) {
            throw new AuthException(AuthErrorCode.INVALID_CREDENTIALS, "Contact information must not be blank");
        }

        String normalizedContact = contact.trim().toLowerCase();

        // 1. Enforce 60-second cooldown between requests (REQ-AUTH-06)
        checkCooldown(normalizedContact);

        // 2. Enforce 24-hour daily quota limit (max 5 requests per day) (REQ-AUTH-06)
        checkDailyLimit(normalizedContact);

        // 3. Generate secure 6-digit OTP code
        String otpCode = String.format("%06d", RANDOM.nextInt(1_000_000));
        Instant expiresAt = Instant.now().plusSeconds(ttlSeconds);

        // 4. Update in-memory fallback stores
        otpStore.put(normalizedContact, new OtpRecord(otpCode, expiresAt, 0));
        cooldownStore.put(normalizedContact, Instant.now().plusSeconds(cooldownSeconds));
        incrementDailyFallback(normalizedContact);

        // 5. Distributed caching via Valkey with native TTLs
        if (redisTemplate != null) {
            try {
                String codeKey = ValkeyKeyConstant.otpCodeKey(normalizedContact);
                String attemptsKey = ValkeyKeyConstant.otpAttemptsKey(normalizedContact);
                String cooldownKey = ValkeyKeyConstant.otpCooldownKey(normalizedContact);
                String dailyKey = ValkeyKeyConstant.otpDailyKey(normalizedContact);

                Duration codeTtl = Duration.ofSeconds(ttlSeconds);
                Duration coolTtl = Duration.ofSeconds(cooldownSeconds);

                redisTemplate.opsForValue().set(codeKey, otpCode, codeTtl);
                redisTemplate.opsForValue().set(attemptsKey, ValkeyKeyConstant.INITIAL_ATTEMPTS_COUNT, codeTtl);
                redisTemplate.opsForValue().set(cooldownKey, "1", coolTtl);

                Long dailyCount = redisTemplate.opsForValue().increment(dailyKey);
                if (dailyCount != null && dailyCount == 1) {
                    redisTemplate.expire(dailyKey, Duration.ofHours(24));
                }

                log.info("Persisted OTP for [{}] to Valkey (TTL: {}s, Cooldown: {}s, DailyCount: {})",
                        normalizedContact, ttlSeconds, cooldownSeconds, dailyCount);
            } catch (Exception ex) {
                log.warn("Failed to store OTP in Valkey for [{}], using fallback store: {}", normalizedContact, ex.getMessage());
            }
        }

        // 6. Asynchronous delivery via branded HTML email / notification service
        if (emailService != null) {
            emailService.sendOtpEmail(normalizedContact, otpCode, ttlSeconds);
        } else {
            log.info("================================================================================");
            log.info("[OTP DISPATCH] Verification code for [{}] is: [{}]", normalizedContact, otpCode);
            log.info("               Expires at: {} (in {} seconds)", expiresAt, ttlSeconds);
            log.info("================================================================================");
        }

        return CustomerOtpResponse.builder()
                .email(normalizedContact)
                .contact(normalizedContact)
                .expiresInSeconds(ttlSeconds)
                .resendCooldownSeconds(cooldownSeconds)
                .message("Verification code has been dispatched to your email")
                .existingUser(isExistingUser)
                .build();
    }

    /**
     * Validates an incoming OTP code against Valkey records (or fallback store).
     * If valid, immediately purges the OTP to prevent reuse.
     * If invalid, decrements remaining attempts; after 3 failures, code is invalidated (REQ-AUTH-07).
     *
     * @param contact customer's email or phone number
     * @param rawOtp  the 6-digit verification code submitted
     * @throws AuthException if code is absent, expired, exceeds 3 attempts, or does not match
     */
    public void verifyOtp(String contact, String rawOtp) {
        if (contact == null || contact.isBlank() || rawOtp == null || rawOtp.isBlank()) {
            throw new AuthException(AuthErrorCode.INVALID_OTP, "Contact and verification code are required");
        }

        String normalizedContact = contact.trim().toLowerCase();
        String submittedOtp = rawOtp.trim();

        // 1. Try Valkey / Redis first if template is configured
        if (redisTemplate != null) {
            try {
                String codeKey = ValkeyKeyConstant.otpCodeKey(normalizedContact);
                String attemptsKey = ValkeyKeyConstant.otpAttemptsKey(normalizedContact);

                String cachedCode = redisTemplate.opsForValue().get(codeKey);
                if (cachedCode != null) {
                    String attemptsStr = redisTemplate.opsForValue().get(attemptsKey);
                    int attempts = attemptsStr != null ? Integer.parseInt(attemptsStr) : 0;

                    if (attempts >= maxAttempts) {
                        clearValkeyAndFallback(normalizedContact);
                        log.warn("OTP verification blocked in Valkey: Max failed attempts reached for [{}]", normalizedContact);
                        throw new AuthException(AuthErrorCode.OTP_TOO_MANY_ATTEMPTS, "Too many failed attempts. Verification code has been invalidated. Please request a new code.");
                    }

                    if (!cachedCode.equals(submittedOtp)) {
                        Long newAttempts = redisTemplate.opsForValue().increment(attemptsKey);
                        int currentAttempts = newAttempts != null ? newAttempts.intValue() : attempts + 1;
                        int remaining = maxAttempts - currentAttempts;

                        if (remaining <= 0) {
                            clearValkeyAndFallback(normalizedContact);
                            log.warn("OTP verification blocked: Max failed attempts exceeded for [{}]", normalizedContact);
                            throw new AuthException(AuthErrorCode.OTP_TOO_MANY_ATTEMPTS, "Too many failed attempts. Verification code has been invalidated. Please request a new code.");
                        }

                        log.warn("OTP mismatch in Valkey for [{}]. Attempts remaining: {}", normalizedContact, remaining);
                        throw new AuthException(AuthErrorCode.INVALID_OTP, "Invalid verification code. Attempts remaining: " + remaining);
                    }

                    // Success: Purge OTP immediately to prevent reuse (REQ-AUTH-07)
                    clearValkeyAndFallback(normalizedContact);
                    log.info("OTP verification succeeded via Valkey for [{}]", normalizedContact);
                    return;
                }
            } catch (AuthException ex) {
                throw ex;
            } catch (Exception ex) {
                log.warn("Valkey lookup failed for [{}], falling back to in-memory store: {}", normalizedContact, ex.getMessage());
            }
        }

        // 2. Fallback in-memory verification
        OtpRecord record = otpStore.get(normalizedContact);
        if (record == null) {
            log.warn("OTP verification failed: No active OTP record found for [{}]", normalizedContact);
            throw new AuthException(AuthErrorCode.INVALID_OTP, "No active verification code found for this contact");
        }

        if (Instant.now().isAfter(record.expiresAt())) {
            clearValkeyAndFallback(normalizedContact);
            log.warn("OTP verification rejected: Code for [{}] has expired", normalizedContact);
            throw new AuthException(AuthErrorCode.OTP_EXPIRED, "Verification code has expired. Please request a new code.");
        }

        if (record.attempts() >= maxAttempts) {
            clearValkeyAndFallback(normalizedContact);
            log.warn("OTP verification blocked: Max failed attempts reached for [{}]", normalizedContact);
            throw new AuthException(AuthErrorCode.OTP_TOO_MANY_ATTEMPTS, "Too many failed attempts. Verification code has been invalidated. Please request a new code.");
        }

        if (!record.code().equals(submittedOtp)) {
            int newAttempts = record.attempts() + 1;
            int remaining = maxAttempts - newAttempts;

            if (remaining <= 0) {
                clearValkeyAndFallback(normalizedContact);
                log.warn("OTP verification blocked: Max failed attempts reached for [{}]", normalizedContact);
                throw new AuthException(AuthErrorCode.OTP_TOO_MANY_ATTEMPTS, "Too many failed attempts. Verification code has been invalidated. Please request a new code.");
            }

            otpStore.put(normalizedContact, new OtpRecord(record.code(), record.expiresAt(), newAttempts));
            log.warn("OTP verification mismatch for [{}]. Attempts remaining: {}", normalizedContact, remaining);
            throw new AuthException(AuthErrorCode.INVALID_OTP, "Invalid verification code. Attempts remaining: " + remaining);
        }

        // Success: Immediately purge OTP
        clearValkeyAndFallback(normalizedContact);
        log.info("OTP verification succeeded via in-memory store for [{}]", normalizedContact);
    }

    /**
     * Checks if the 60-second cooldown is active for the given contact.
     */
    private void checkCooldown(String contact) {
        if (redisTemplate != null) {
            try {
                String cooldownKey = ValkeyKeyConstant.otpCooldownKey(contact);
                Long remainingTtl = redisTemplate.getExpire(cooldownKey, TimeUnit.SECONDS);
                if (remainingTtl != null && remainingTtl > 0) {
                    log.warn("OTP request rejected: Cooldown active for [{}]. Remaining seconds: {}", contact, remainingTtl);
                    throw new AuthException(
                            AuthErrorCode.OTP_COOLDOWN_ACTIVE,
                            "Please wait " + remainingTtl + " seconds before requesting a new verification code."
                    );
                }
            } catch (AuthException ex) {
                throw ex;
            } catch (Exception ex) {
                log.debug("Valkey cooldown check error for [{}]: {}", contact, ex.getMessage());
            }
        }

        Instant cooldownUntil = cooldownStore.get(contact);
        if (cooldownUntil != null && Instant.now().isBefore(cooldownUntil)) {
            long remaining = Duration.between(Instant.now(), cooldownUntil).toSeconds() + 1;
            log.warn("OTP request rejected: In-memory cooldown active for [{}]. Remaining seconds: {}", contact, remaining);
            throw new AuthException(
                    AuthErrorCode.OTP_COOLDOWN_ACTIVE,
                    "Please wait " + remaining + " seconds before requesting a new verification code."
            );
        }
    }

    /**
     * Checks if the daily limit of 5 requests within 24 hours has been exceeded.
     */
    private void checkDailyLimit(String contact) {
        if (redisTemplate != null) {
            try {
                String dailyKey = ValkeyKeyConstant.otpDailyKey(contact);
                String dailyCountStr = redisTemplate.opsForValue().get(dailyKey);
                if (dailyCountStr != null) {
                    int count = Integer.parseInt(dailyCountStr);
                    if (count >= maxDailyRequests) {
                        log.warn("OTP request rejected: Daily limit of {} exceeded for [{}]", maxDailyRequests, contact);
                        throw new AuthException(
                                AuthErrorCode.OTP_DAILY_LIMIT_EXCEEDED,
                                "Daily limit of " + maxDailyRequests + " verification codes reached for this contact. Please try again after 24 hours."
                        );
                    }
                }
            } catch (AuthException ex) {
                throw ex;
            } catch (Exception ex) {
                log.debug("Valkey daily limit check error for [{}]: {}", contact, ex.getMessage());
            }
        }

        DailyLimitRecord dailyRec = dailyStore.get(contact);
        if (dailyRec != null && Instant.now().isBefore(dailyRec.resetAt()) && dailyRec.count() >= maxDailyRequests) {
            log.warn("OTP request rejected: In-memory daily limit of {} exceeded for [{}]", maxDailyRequests, contact);
            throw new AuthException(
                    AuthErrorCode.OTP_DAILY_LIMIT_EXCEEDED,
                    "Daily limit of " + maxDailyRequests + " verification codes reached for this contact. Please try again after 24 hours."
            );
        }
    }

    private void incrementDailyFallback(String contact) {
        DailyLimitRecord dailyRec = dailyStore.get(contact);
        if (dailyRec == null || Instant.now().isAfter(dailyRec.resetAt())) {
            dailyStore.put(contact, new DailyLimitRecord(1, Instant.now().plus(Duration.ofHours(24))));
        } else {
            dailyStore.put(contact, new DailyLimitRecord(dailyRec.count() + 1, dailyRec.resetAt()));
        }
    }

    /**
     * Clears any active OTP code and attempt counters for the specified contact.
     */
    public void clearOtp(String contact) {
        if (contact != null) {
            clearValkeyAndFallback(contact.trim().toLowerCase());
        }
    }

    /**
     * Clears cooldowns and daily limit counters (primarily for unit tests and administrative resets).
     */
    public void clearCooldown(String contact) {
        if (contact != null) {
            String normalized = contact.trim().toLowerCase();
            cooldownStore.remove(normalized);
            if (redisTemplate != null) {
                try {
                    redisTemplate.delete(ValkeyKeyConstant.otpCooldownKey(normalized));
                } catch (Exception ignored) {
                }
            }
        }
    }

    /**
     * Resets all OTP states, cooldowns, and daily limits for a contact.
     */
    public void resetAllForContact(String contact) {
        if (contact != null) {
            String normalized = contact.trim().toLowerCase();
            clearValkeyAndFallback(normalized);
            cooldownStore.remove(normalized);
            dailyStore.remove(normalized);
            if (redisTemplate != null) {
                try {
                    redisTemplate.delete(List.of(
                            ValkeyKeyConstant.otpCodeKey(normalized),
                            ValkeyKeyConstant.otpAttemptsKey(normalized),
                            ValkeyKeyConstant.otpCooldownKey(normalized),
                            ValkeyKeyConstant.otpDailyKey(normalized)
                    ));
                } catch (Exception ignored) {
                }
            }
        }
    }

    private void clearValkeyAndFallback(String normalizedContact) {
        otpStore.remove(normalizedContact);
        if (redisTemplate != null) {
            try {
                redisTemplate.delete(List.of(
                        ValkeyKeyConstant.otpCodeKey(normalizedContact),
                        ValkeyKeyConstant.otpAttemptsKey(normalizedContact)
                ));
            } catch (Exception ex) {
                log.debug("Error clearing Valkey keys for [{}]: {}", normalizedContact, ex.getMessage());
            }
        }
    }

    public record OtpRecord(String code, Instant expiresAt, int attempts) {
    }

    public record DailyLimitRecord(int count, Instant resetAt) {
    }
}
