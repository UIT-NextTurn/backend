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

/**
 * Service orchestrating OTP generation, storage, expiration tracking,
 * and delivery for passwordless customer authentication.
 * <p>
 * Utilizes Valkey/Redis for distributed, auto-expiring in-memory storage and atomic rate-limiting,
 * with a resilient thread-safe in-memory fallback.
 */
@Service
public class OtpService {

    private static final Logger log = LoggerFactory.getLogger(OtpService.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final StringRedisTemplate redisTemplate;
    private final EmailService emailService;
    private final long ttlSeconds;
    private final int maxAttempts;

    // Concurrent in-memory storage for OTP records (fallback and local testing)
    private final Map<String, OtpRecord> otpStore = new ConcurrentHashMap<>();

    @Autowired
    public OtpService(
            @Autowired(required = false) StringRedisTemplate redisTemplate,
            @Autowired(required = false) EmailService emailService,
            @Value("${app.auth.otp.ttl-seconds:300}") long ttlSeconds,
            @Value("${app.auth.otp.max-attempts:5}") int maxAttempts
    ) {
        this.redisTemplate = redisTemplate;
        this.emailService = emailService != null ? emailService : new EmailService(null, "", "", "no-reply@nextturn.com", "NextTurn");
        this.ttlSeconds = ttlSeconds;
        this.maxAttempts = maxAttempts;
    }

    public OtpService(StringRedisTemplate redisTemplate, long ttlSeconds, int maxAttempts) {
        this(redisTemplate, null, ttlSeconds, maxAttempts);
    }

    public OtpService(long ttlSeconds, int maxAttempts) {
        this(null, null, ttlSeconds, maxAttempts);
    }

    /**
     * Generates a cryptographically random 6-digit OTP, stores it in Valkey with TTL, and triggers delivery.
     *
     * @param email          customer's email address
     * @param isExistingUser whether this email corresponds to an existing customer record
     * @return {@link CustomerOtpResponse} with expiration details
     */
    public CustomerOtpResponse generateAndSendOtp(String email, boolean isExistingUser) {
        if (email == null || email.isBlank()) {
            throw new AuthException(AuthErrorCode.INVALID_CREDENTIALS, "Email must not be blank");
        }

        String normalizedEmail = email.trim().toLowerCase();
        String otpCode = String.format("%06d", RANDOM.nextInt(1_000_000));
        Instant expiresAt = Instant.now().plusSeconds(ttlSeconds);

        // Always update fallback map for resilience and testing
        otpStore.put(normalizedEmail, new OtpRecord(otpCode, expiresAt, 0));

        // Distributed Valkey / Redis caching with native TTL
        if (redisTemplate != null) {
            try {
                String codeKey = ValkeyKeyConstant.otpCodeKey(normalizedEmail);
                String attemptsKey = ValkeyKeyConstant.otpAttemptsKey(normalizedEmail);
                Duration ttl = Duration.ofSeconds(ttlSeconds);

                redisTemplate.opsForValue().set(codeKey, otpCode, ttl);
                redisTemplate.opsForValue().set(attemptsKey, ValkeyKeyConstant.INITIAL_ATTEMPTS_COUNT, ttl);
                log.info("Persisted OTP for [{}] to Valkey with TTL {}s", normalizedEmail, ttlSeconds);
            } catch (Exception ex) {
                log.warn("Failed to store OTP in Valkey for [{}], using fallback store: {}", normalizedEmail, ex.getMessage());
            }
        }

        // Delivery mechanism: Dispatches branded HTML email via SMTP if configured, with console fallback
        if (emailService != null) {
            emailService.sendOtpEmail(normalizedEmail, otpCode, ttlSeconds);
        } else {
            log.info("================================================================================");
            log.info("[OTP DISPATCH] Verification code for [{}] is: [{}]", normalizedEmail, otpCode);
            log.info("               Expires at: {} (in {} seconds)", expiresAt, ttlSeconds);
            log.info("================================================================================");
        }

        return CustomerOtpResponse.builder()
                .email(normalizedEmail)
                .expiresInSeconds(ttlSeconds)
                .message("Verification code has been dispatched to your email")
                .existingUser(isExistingUser)
                .build();
    }

    /**
     * Validates an incoming OTP code against Valkey records (or fallback store).
     *
     * @param email  customer's email address
     * @param rawOtp the submitted OTP code
     * @throws AuthException if OTP is absent, expired, exceeds attempts, or does not match
     */
    public void verifyOtp(String email, String rawOtp) {
        if (email == null || email.isBlank() || rawOtp == null || rawOtp.isBlank()) {
            throw new AuthException(AuthErrorCode.INVALID_OTP, "Email and OTP code are required");
        }

        String normalizedEmail = email.trim().toLowerCase();
        String submittedOtp = rawOtp.trim();

        // 1. Try Valkey / Redis first if template is configured
        if (redisTemplate != null) {
            try {
                String codeKey = ValkeyKeyConstant.otpCodeKey(normalizedEmail);
                String attemptsKey = ValkeyKeyConstant.otpAttemptsKey(normalizedEmail);

                String cachedCode = redisTemplate.opsForValue().get(codeKey);
                if (cachedCode != null) {
                    String attemptsStr = redisTemplate.opsForValue().get(attemptsKey);
                    int attempts = attemptsStr != null ? Integer.parseInt(attemptsStr) : 0;

                    if (attempts >= maxAttempts) {
                        clearValkeyAndFallback(normalizedEmail);
                        log.warn("OTP verification blocked in Valkey: Max failed attempts reached for [{}]", normalizedEmail);
                        throw new AuthException(AuthErrorCode.OTP_TOO_MANY_ATTEMPTS, "Too many failed attempts. Please request a new code.");
                    }

                    if (!cachedCode.equals(submittedOtp)) {
                        Long newAttempts = redisTemplate.opsForValue().increment(attemptsKey);
                        int currentAttempts = newAttempts != null ? newAttempts.intValue() : attempts + 1;
                        int remaining = maxAttempts - currentAttempts;

                        if (remaining <= 0) {
                            clearValkeyAndFallback(normalizedEmail);
                            log.warn("OTP verification blocked: Max failed attempts exceeded for [{}]", normalizedEmail);
                            throw new AuthException(AuthErrorCode.OTP_TOO_MANY_ATTEMPTS, "Too many failed attempts. Please request a new code.");
                        }

                        log.warn("OTP mismatch in Valkey for [{}]. Attempts remaining: {}", normalizedEmail, remaining);
                        throw new AuthException(AuthErrorCode.INVALID_OTP, "Invalid verification code. Attempts remaining: " + remaining);
                    }

                    // Success: Clear OTP immediately
                    clearValkeyAndFallback(normalizedEmail);
                    log.info("OTP verification succeeded via Valkey for [{}]", normalizedEmail);
                    return;
                }
            } catch (AuthException ex) {
                throw ex;
            } catch (Exception ex) {
                log.warn("Valkey lookup failed for [{}], falling back to in-memory store: {}", normalizedEmail, ex.getMessage());
            }
        }

        // 2. Fallback to in-memory verification
        OtpRecord record = otpStore.get(normalizedEmail);
        if (record == null) {
            log.warn("OTP verification failed: No active OTP record found for [{}]", normalizedEmail);
            throw new AuthException(AuthErrorCode.INVALID_OTP, "No active verification code found for this email");
        }

        if (Instant.now().isAfter(record.expiresAt())) {
            clearValkeyAndFallback(normalizedEmail);
            log.warn("OTP verification rejected: Code for [{}] has expired", normalizedEmail);
            throw new AuthException(AuthErrorCode.OTP_EXPIRED, "Verification code has expired. Please request a new code.");
        }

        if (record.attempts() >= maxAttempts) {
            clearValkeyAndFallback(normalizedEmail);
            log.warn("OTP verification blocked: Max failed attempts reached for [{}]", normalizedEmail);
            throw new AuthException(AuthErrorCode.OTP_TOO_MANY_ATTEMPTS, "Too many failed attempts. Please request a new code.");
        }

        if (!record.code().equals(submittedOtp)) {
            otpStore.put(normalizedEmail, new OtpRecord(record.code(), record.expiresAt(), record.attempts() + 1));
            int remaining = maxAttempts - (record.attempts() + 1);
            log.warn("OTP verification mismatch for [{}]. Attempts remaining: {}", normalizedEmail, remaining);
            throw new AuthException(AuthErrorCode.INVALID_OTP, "Invalid verification code. Attempts remaining: " + remaining);
        }

        clearValkeyAndFallback(normalizedEmail);
        log.info("OTP verification succeeded via in-memory store for [{}]", normalizedEmail);
    }

    /**
     * Clears any active OTP for the specified email in both Valkey and local fallback.
     */
    public void clearOtp(String email) {
        if (email != null) {
            clearValkeyAndFallback(email.trim().toLowerCase());
        }
    }

    private void clearValkeyAndFallback(String normalizedEmail) {
        otpStore.remove(normalizedEmail);
        if (redisTemplate != null) {
            try {
                redisTemplate.delete(List.of(
                        ValkeyKeyConstant.otpCodeKey(normalizedEmail),
                        ValkeyKeyConstant.otpAttemptsKey(normalizedEmail)
                ));
            } catch (Exception ex) {
                log.debug("Error clearing Valkey keys for [{}]: {}", normalizedEmail, ex.getMessage());
            }
        }
    }

    /**
     * Internal record representation of stored OTP.
     */
    public record OtpRecord(String code, Instant expiresAt, int attempts) {
    }
}
