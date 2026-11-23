package com.nextturn.service.auth.impl;

import com.nextturn.dto.auth.AuthTokenResponse;
import com.nextturn.dto.auth.CustomerOtpResponse;
import com.nextturn.dto.auth.CustomerSendOtpRequest;
import com.nextturn.dto.auth.CustomerVerifyOtpRequest;
import com.nextturn.dto.auth.CustomerWidgetSsoRequest;
import com.nextturn.dto.auth.LogoutRequest;
import com.nextturn.entity.CentralAllowedDomain;
import com.nextturn.entity.CentralTenant;
import com.nextturn.entity.CentralUser;
import com.nextturn.enums.TenantStatus;
import com.nextturn.enums.UserStatus;
import com.nextturn.exception.auth.AuthErrorCode;
import com.nextturn.exception.auth.AuthException;
import com.nextturn.infrastructure.multitenancy.TenantContextHolder;
import com.nextturn.infrastructure.security.keycloak.KeycloakTokenService;
import com.nextturn.repository.auth.CentralAllowedDomainRepository;
import com.nextturn.repository.auth.CentralTenantRepository;
import com.nextturn.repository.auth.CentralUserRepository;
import com.nextturn.service.BaseService;
import com.nextturn.service.auth.CustomerAuthService;
import com.nextturn.service.auth.OtpService;
import com.nextturn.infrastructure.logging.LogContract;
import com.nextturn.infrastructure.logging.LogDomain;
import com.nextturn.infrastructure.logging.LogOutcome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Service managing passwordless authentication lifecycle for Public Customers (END_USER)
 * across standalone web applications and embedded booking/queue widgets (REQ-AUTH-06 & REQ-AUTH-07).
 * <p>
 * Implements:
 * 1. Embedded widget integration validation (public API key + whitelisted origin domain).
 * 2. Contact validation (email or phone) with 60-second cooldown and 24-hour daily quota (max 5).
 * 3. Asynchronous OTP generation and delivery (5 minutes TTL, max 3 attempts).
 * 4. Verification with idempotent database user lookup, login timestamp updates, and 30-day session tokens.
 */
@Service
public class CustomerAuthServiceImpl extends BaseService implements CustomerAuthService {

    private static final Logger log = LoggerFactory.getLogger(CustomerAuthServiceImpl.class);

    private final CentralUserRepository centralUserRepository;
    private final OtpService otpService;
    private final KeycloakTokenService keycloakTokenService;
    private final CentralTenantRepository centralTenantRepository;
    private final CentralAllowedDomainRepository centralAllowedDomainRepository;

    @Autowired
    public CustomerAuthServiceImpl(
            CentralUserRepository centralUserRepository,
            OtpService otpService,
            KeycloakTokenService keycloakTokenService,
            @Autowired(required = false) CentralTenantRepository centralTenantRepository,
            @Autowired(required = false) CentralAllowedDomainRepository centralAllowedDomainRepository,
            @Autowired(required = false) TransactionTemplate transactionTemplate
    ) {
        super(transactionTemplate);
        this.centralUserRepository = centralUserRepository;
        this.otpService = otpService;
        this.keycloakTokenService = keycloakTokenService;
        this.centralTenantRepository = centralTenantRepository;
        this.centralAllowedDomainRepository = centralAllowedDomainRepository;
    }

    public CustomerAuthServiceImpl(
            CentralUserRepository centralUserRepository,
            OtpService otpService,
            KeycloakTokenService keycloakTokenService
    ) {
        this(centralUserRepository, otpService, keycloakTokenService, null, null, null);
    }

    /**
     * Validates widget public integration key and website origin domain against facility whitelist (REQ-AUTH-06).
     *
     * @param widgetApiKey       the public integration key
     * @param originHeader       HTTP Origin header
     * @param refererHeader      HTTP Referer header
     * @param originDomainParam  explicit domain passed in payload
     * @throws AuthException if key is invalid, facility is inactive, or domain is not authorized
     */
    public void validateWidgetIntegration(
            String widgetApiKey,
            String originHeader,
            String refererHeader,
            String originDomainParam
    ) {
        if (widgetApiKey == null || widgetApiKey.isBlank()) {
            return; // Standalone web login, no widget restrictions
        }

        if (centralTenantRepository == null) {
            return;
        }

        CentralTenant tenant = centralTenantRepository.findByWidgetApiKeyAndIsDeletedFalse(widgetApiKey.trim())
                .orElseThrow(() -> new AuthException(AuthErrorCode.WIDGET_KEY_INVALID, "Invalid or unrecognized widget integration key"));

        if (tenant.getStatus() != TenantStatus.ACTIVE) {
            LogContract.warn(log, LogDomain.AUTH, "widget_access_rejected")
                    .outcome(LogOutcome.REJECTED)
                    .tenant(tenant.getTenantCode())
                    .field("status", tenant.getStatus())
                    .emit();
            throw new AuthException(AuthErrorCode.TENANT_INACTIVE, "Tenant facility is not active. Status: " + tenant.getStatus());
        }

        // Determine candidate domain from param, Origin header, or Referer header
        String candidateDomain = extractDomain(originDomainParam);
        if (candidateDomain == null || candidateDomain.isBlank()) {
            candidateDomain = extractDomain(originHeader);
        }
        if (candidateDomain == null || candidateDomain.isBlank()) {
            candidateDomain = extractDomain(refererHeader);
        }

        if (candidateDomain == null || candidateDomain.isBlank()) {
            LogContract.warn(log, LogDomain.AUTH, "widget_origin_missing")
                    .outcome(LogOutcome.REJECTED)
                    .tenant(tenant.getTenantCode())
                    .emit();
            throw new AuthException(AuthErrorCode.UNAUTHORIZED_ORIGIN_DOMAIN, "Origin domain is missing or unverified for widget authentication");
        }

        if (centralAllowedDomainRepository != null) {
            List<CentralAllowedDomain> allowedDomains = centralAllowedDomainRepository.findByTenantId(tenant.getId());
            if (allowedDomains == null || allowedDomains.isEmpty() || !isDomainWhitelisted(candidateDomain, allowedDomains)) {
                LogContract.warn(log, LogDomain.AUTH, "widget_origin_rejected")
                        .outcome(LogOutcome.REJECTED)
                        .tenant(tenant.getTenantCode())
                        .field("origin", candidateDomain)
                        .emit();
                throw new AuthException(
                        AuthErrorCode.UNAUTHORIZED_ORIGIN_DOMAIN,
                        "Website domain [" + candidateDomain + "] is not authorized to embed the widget for facility [" + tenant.getTenantCode() + "]"
                );
            }
        }

        LogContract.info(log, LogDomain.AUTH, "widget_verified")
                .outcome(LogOutcome.SUCCESS)
                .tenant(tenant.getTenantCode())
                .field("origin", candidateDomain)
                .emit();
    }

    /**
     * Extracts host domain name from URL string, stripping protocol, port, and query.
     */
    public static String extractDomain(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        try {
            URI uri = new URI(url.trim());
            String host = uri.getHost();
            if (host != null && !host.isBlank()) {
                return host.toLowerCase();
            }
        } catch (Exception ignored) {
        }

        String cleaned = url.trim().toLowerCase().replaceFirst("^[a-zA-Z]+://", "");
        int slashIdx = cleaned.indexOf('/');
        if (slashIdx >= 0) {
            cleaned = cleaned.substring(0, slashIdx);
        }
        int colonIdx = cleaned.indexOf(':');
        if (colonIdx >= 0) {
            cleaned = cleaned.substring(0, colonIdx);
        }
        return cleaned.isEmpty() ? null : cleaned;
    }

    /**
     * Checks if a candidate domain matches any registered whitelist entries (supports wildcards).
     */
    public static boolean isDomainWhitelisted(String candidateDomain, List<?> allowedDomains) {
        if (candidateDomain == null || candidateDomain.isBlank() || allowedDomains == null || allowedDomains.isEmpty()) {
            return false;
        }
        String host = candidateDomain.trim().toLowerCase();
        if (host.contains("://")) {
            host = host.substring(host.indexOf("://") + 3);
        }
        if (host.contains("/")) {
            host = host.substring(0, host.indexOf('/'));
        }
        String hostWithoutPort = host.contains(":") ? host.substring(0, host.indexOf(':')) : host;

        for (Object entry : allowedDomains) {
            if (entry == null) {
                continue;
            }
            String domainName;
            if (entry instanceof CentralAllowedDomain cad) {
                domainName = cad.getDomainName();
            } else if (entry instanceof String str) {
                domainName = str;
            } else {
                domainName = entry.toString();
            }
            if (domainName == null || domainName.isBlank()) {
                continue;
            }
            String allowed = domainName.trim().toLowerCase();
            String allowedWithoutPort = allowed.contains(":") ? allowed.substring(0, allowed.indexOf(':')) : allowed;

            if ("*".equals(allowed)) {
                return true;
            }
            if (allowed.equals(host) || allowedWithoutPort.equals(hostWithoutPort)) {
                return true;
            }
            // Check wildcard subdomain: *.example.com matches sub.example.com
            if (allowedWithoutPort.startsWith("*.")) {
                String baseDomain = allowedWithoutPort.substring(2);
                if (hostWithoutPort.endsWith("." + baseDomain) && hostWithoutPort.length() > baseDomain.length() + 1) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Dispatches an OTP verification code to customer's contact (email or phone) with widget integration validation (REQ-AUTH-06).
     *
     * @param request          dispatch payload
     * @param widgetKeyHeader  optional X-Widget-Key header
     * @param originHeader     optional Origin header
     * @param refererHeader    optional Referer header
     * @return {@link CustomerOtpResponse} with delivery, cooldown (60s), and expiration metadata (300s)
     */
    @Transactional(readOnly = true)
    public CustomerOtpResponse sendOtp(
            CustomerSendOtpRequest request,
            String widgetKeyHeader,
            String originHeader,
            String refererHeader
    ) {
        if (request == null) {
            throw new AuthException(AuthErrorCode.INVALID_CREDENTIALS, "Request body is required");
        }

        String effectiveWidgetKey = (request.getWidgetApiKey() != null && !request.getWidgetApiKey().isBlank())
                ? request.getWidgetApiKey().trim()
                : widgetKeyHeader;

        // 1. Validate widget integration key and origin domain if widget key is provided (REQ-AUTH-06)
        validateWidgetIntegration(effectiveWidgetKey, originHeader, refererHeader, request.getOriginDomain());

        String contact = request.resolveContact();
        if (contact == null || contact.isBlank()) {
            throw new AuthException(AuthErrorCode.INVALID_CREDENTIALS, "Email address or phone number must not be blank");
        }

        // 2. Check if an existing account exists and verify active status
        Optional<CentralUser> existingUserOpt = findUserByContact(contact);
        boolean isExistingUser = existingUserOpt.isPresent();

        if (isExistingUser) {
            CentralUser existingUser = existingUserOpt.get();
            if (existingUser.getStatus() != UserStatus.ACTIVE) {
                LogContract.warn(log, LogDomain.AUTH, "otp_dispatch_rejected")
                        .outcome(LogOutcome.REJECTED)
                        .field("contact", LogContract.maskEmail(contact))
                        .field("status", existingUser.getStatus())
                        .emit();
                throw new AuthException(AuthErrorCode.USER_BLOCKED, "Account is suspended. Status: " + existingUser.getStatus());
            }
        }

        // 3. Rate limiting (60s cooldown & 5 requests/24h) and OTP generation in OtpService
        return otpService.generateAndSendOtp(contact, isExistingUser);
    }

    @Transactional(readOnly = true)
    public CustomerOtpResponse sendOtp(CustomerSendOtpRequest request) {
        return sendOtp(request, null, null, null);
    }

    /**
     * Verifies the OTP, resolves or provisions central user, and issues 30-day session tokens (REQ-AUTH-07).
     *
     * @param request email/phone and OTP payload
     * @return {@link AuthTokenResponse} containing 30-day session tokens
     */
    public AuthTokenResponse verifyOtp(CustomerVerifyOtpRequest request) {
        if (request == null) {
            throw new AuthException(AuthErrorCode.INVALID_CREDENTIALS, "Request body is required");
        }

        String contact = request.resolveContact();
        if (contact == null || contact.isBlank()) {
            throw new AuthException(AuthErrorCode.INVALID_CREDENTIALS, "Email address or phone number must not be blank");
        }
        if (request.getOtp() == null || request.getOtp().isBlank()) {
            throw new AuthException(AuthErrorCode.INVALID_OTP, "Verification code must not be blank");
        }

        String submittedOtp = request.getOtp().trim();

        // 1. Verify OTP with OtpService (purges code on match, decrements attempts, max 3 tries)
        otpService.verifyOtp(contact, submittedOtp);

        // 2. Resolve existing user or auto-provision central record within fine-grained TransactionTemplate
        CentralUser user = executeInTransaction(status -> resolveOrCreateUser(contact));

        // 3. Synchronize identity with Keycloak IAM outside DB transaction
        String identityContact = user.getEmail() != null ? user.getEmail() : user.getPhoneNumber();
        keycloakTokenService.syncCustomerIdentity(identityContact, user.getFullName());

        // 4. Issue centralized Keycloak tokens with 30-day session lifetime (REQ-AUTH-07)
        return keycloakTokenService.issueCustomerTokens(identityContact, user.getId());
    }

    /**
     * Authenticates a customer directly from an enterprise host website into the embedded widget
     * by verifying an HMAC-SHA256 digital signature and 5-minute timestamp window, bypassing OTP (REQ-AUTH-08).
     *
     * @param request          widget SSO payload containing customer identity, timestamp, and signature
     * @param widgetKeyHeader  optional X-Widget-Key header
     * @param originHeader     optional Origin header
     * @param refererHeader    optional Referer header
     * @return {@link AuthTokenResponse} containing 30-day customer session tokens
     */
    @Override
    public AuthTokenResponse authenticateWidgetSso(
            CustomerWidgetSsoRequest request,
            String widgetKeyHeader,
            String originHeader,
            String refererHeader
    ) {
        if (request == null) {
            throw new AuthException(AuthErrorCode.INVALID_CREDENTIALS, "Request body is required");
        }

        String effectiveWidgetKey = (request.getWidgetApiKey() != null && !request.getWidgetApiKey().isBlank())
                ? request.getWidgetApiKey().trim()
                : (widgetKeyHeader != null ? widgetKeyHeader.trim() : null);

        if (effectiveWidgetKey == null || effectiveWidgetKey.isBlank()) {
            throw new AuthException(AuthErrorCode.WIDGET_KEY_INVALID, "Public widget integration key is required");
        }

        String email = (request.getEmail() != null && !request.getEmail().isBlank())
                ? request.getEmail().trim().toLowerCase()
                : null;
        String phone = (request.getPhoneNumber() != null && !request.getPhoneNumber().isBlank())
                ? request.getPhoneNumber().trim()
                : null;

        if (email == null && phone == null) {
            throw new AuthException(AuthErrorCode.INVALID_CREDENTIALS, "Customer email or phone number is required");
        }

        // 1. Validate widget integration key, active facility status, and origin domain whitelist
        if (centralTenantRepository == null) {
            throw new AuthException(AuthErrorCode.WIDGET_KEY_INVALID, "Tenant repository unavailable");
        }

        CentralTenant tenant = centralTenantRepository.findByWidgetApiKeyAndIsDeletedFalse(effectiveWidgetKey)
                .orElseThrow(() -> new AuthException(AuthErrorCode.WIDGET_KEY_INVALID, "Invalid or unrecognized widget integration key"));

        if (tenant.getStatus() != TenantStatus.ACTIVE) {
            throw new AuthException(AuthErrorCode.TENANT_INACTIVE, "Tenant facility is not active. Status: " + tenant.getStatus());
        }

        if (originHeader != null || refererHeader != null || (request.getOriginDomain() != null && !request.getOriginDomain().isBlank())) {
            validateWidgetIntegration(effectiveWidgetKey, originHeader, refererHeader, request.getOriginDomain());
        }

        // 2. Verify timestamp within 5-minute (300 seconds) window to prevent replay attacks (REQ-AUTH-08 Step 4)
        if (request.getTimestamp() == null) {
            throw new AuthException(AuthErrorCode.WIDGET_SIGNATURE_EXPIRED, "Timestamp is required for widget signature verification");
        }
        long rawTimestamp = request.getTimestamp();
        // Support both Unix epoch seconds and epoch milliseconds
        long requestEpochSeconds = (rawTimestamp > 100_000_000_000L) ? (rawTimestamp / 1000L) : rawTimestamp;
        long currentEpochSeconds = Instant.now().getEpochSecond();
        if (Math.abs(currentEpochSeconds - requestEpochSeconds) > 300L) {
            LogContract.warn(log, LogDomain.SECURITY, "widget_sso_expired")
                    .outcome(LogOutcome.REJECTED)
                    .tenant(tenant.getTenantCode())
                    .field("requestTs", requestEpochSeconds)
                    .field("serverTs", currentEpochSeconds)
                    .emit();
            throw new AuthException(
                    AuthErrorCode.WIDGET_SIGNATURE_EXPIRED,
                    "Phiên làm việc đã hết hạn. Vui lòng tải lại trang hoặc xác thực bằng mã OTP."
            );
        }

        // 3. Recompute HMAC-SHA256 signature using tenant's widget_secret_key and compare in constant time
        String secretKey = tenant.getWidgetSecretKey();
        if (secretKey == null || secretKey.isBlank() || request.getSignature() == null || request.getSignature().isBlank()) {
            throw new AuthException(
                    AuthErrorCode.WIDGET_SIGNATURE_INVALID,
                    "Chữ ký bảo mật không hợp lệ hoặc dữ liệu đã bị sửa đổi. Vui lòng xác thực bằng mã OTP."
            );
        }

        String cleanMemberId = request.getExternalMemberId() != null ? request.getExternalMemberId().trim() : "";
        String cleanFullName = request.getFullName() != null ? request.getFullName().trim() : "";
        String canonicalPayload = buildCanonicalWidgetPayload(
                effectiveWidgetKey,
                cleanMemberId,
                email != null ? email : "",
                phone != null ? phone : "",
                cleanFullName,
                rawTimestamp
        );

        if (!verifyHmacSha256Signature(canonicalPayload, secretKey.trim(), request.getSignature().trim())) {
            LogContract.warn(log, LogDomain.SECURITY, "widget_sso_signature_mismatch")
                    .outcome(LogOutcome.REJECTED)
                    .tenant(tenant.getTenantCode())
                    .field("memberId", cleanMemberId)
                    .emit();
            throw new AuthException(
                    AuthErrorCode.WIDGET_SIGNATURE_INVALID,
                    "Chữ ký bảo mật không hợp lệ hoặc dữ liệu đã bị sửa đổi. Vui lòng xác thực bằng mã OTP."
            );
        }

        // 4. Resolve or create central customer record and update profile attributes (REQ-AUTH-08 Step 5)
        CentralUser user = executeInTransaction(status ->
                resolveOrUpdateWidgetCustomer(email, phone, cleanFullName)
        );

        // 5. Synchronize with Keycloak and issue 30-day customer session tokens without OTP
        String identityContact = user.getEmail() != null ? user.getEmail() : user.getPhoneNumber();
        keycloakTokenService.syncCustomerIdentity(identityContact, user.getFullName());
        return keycloakTokenService.issueCustomerTokens(identityContact, user.getId());
    }

    @Override
    public AuthTokenResponse authenticateWidgetSso(CustomerWidgetSsoRequest request) {
        return authenticateWidgetSso(request, null, null, null);
    }

    public static String buildCanonicalWidgetPayload(
            String widgetApiKey,
            String externalMemberId,
            String email,
            String phoneNumber,
            String fullName,
            long timestamp
    ) {
        return (widgetApiKey != null ? widgetApiKey : "") + "|"
                + (externalMemberId != null ? externalMemberId : "") + "|"
                + (email != null ? email : "") + "|"
                + (phoneNumber != null ? phoneNumber : "") + "|"
                + (fullName != null ? fullName : "") + "|"
                + timestamp;
    }

    public static String computeHmacSha256Hex(String payload, String secretKey) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            SecretKeySpec keySpec = new SecretKeySpec(secretKey.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            mac.init(keySpec);
            byte[] rawHmac = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(rawHmac);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to compute HMAC-SHA256 signature", e);
        }
    }

    private boolean verifyHmacSha256Signature(String payload, String secretKey, String providedSignature) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            SecretKeySpec keySpec = new SecretKeySpec(secretKey.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            mac.init(keySpec);
            byte[] rawHmac = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));

            String expectedHex = HexFormat.of().formatHex(rawHmac);
            String expectedBase64 = Base64.getEncoder().encodeToString(rawHmac);

            byte[] providedBytes = providedSignature.toLowerCase().getBytes(StandardCharsets.UTF_8);
            byte[] expectedHexBytes = expectedHex.toLowerCase().getBytes(StandardCharsets.UTF_8);
            if (MessageDigest.isEqual(providedBytes, expectedHexBytes)) {
                return true;
            }
            return MessageDigest.isEqual(
                    providedSignature.getBytes(StandardCharsets.UTF_8),
                    expectedBase64.getBytes(StandardCharsets.UTF_8)
            );
        } catch (Exception e) {
            LogContract.error(log, LogDomain.SECURITY, "hmac_verification_error")
                    .outcome(LogOutcome.FAILED)
                    .causeWithStack(e)
                    .emit();
            return false;
        }
    }

    @Transactional
    protected CentralUser resolveOrUpdateWidgetCustomer(String email, String phone, String fullName) {
        Optional<CentralUser> existingOpt = Optional.empty();
        if (email != null && !email.isBlank()) {
            existingOpt = centralUserRepository.findByEmailIgnoreCaseAndIsDeletedFalse(email);
        }
        if (existingOpt.isEmpty() && phone != null && !phone.isBlank()) {
            existingOpt = centralUserRepository.findByPhoneNumberAndIsDeletedFalse(phone);
        }

        if (existingOpt.isPresent()) {
            CentralUser existing = existingOpt.get();
            if (existing.getStatus() != UserStatus.ACTIVE) {
                throw new AuthException(AuthErrorCode.USER_BLOCKED, "Account is suspended. Status: " + existing.getStatus());
            }
            if (fullName != null && !fullName.isBlank()) {
                existing.setFullName(fullName);
            }
            if (email != null && !email.isBlank() && (existing.getEmail() == null || existing.getEmail().isBlank())) {
                existing.setEmail(email);
            }
            if (phone != null && !phone.isBlank() && (existing.getPhoneNumber() == null || existing.getPhoneNumber().isBlank())) {
                existing.setPhoneNumber(phone);
            }
            existing.setUpdatedAt(LocalDateTime.now());
            return centralUserRepository.save(existing);
        }

        CentralUser newUser = CentralUser.builder()
                .id(UUID.randomUUID().toString())
                .email(email)
                .phoneNumber(phone)
                .fullName(fullName != null && !fullName.isBlank() ? fullName : (email != null ? extractDefaultName(email) : "Customer"))
                .status(UserStatus.ACTIVE)
                .isDeleted(false)
                .build();
        return centralUserRepository.save(newUser);
    }

    private Optional<CentralUser> findUserByContact(String contact) {
        if (contact.contains("@")) {
            return centralUserRepository.findByEmailIgnoreCaseAndIsDeletedFalse(contact);
        }
        return centralUserRepository.findByPhoneNumberAndIsDeletedFalse(contact);
    }

    @Transactional
    public CentralUser resolveOrCreateUser(String contact) {
        Optional<CentralUser> existingUserOpt = findUserByContact(contact);

        if (existingUserOpt.isPresent()) {
            CentralUser existing = existingUserOpt.get();
            if (existing.getStatus() != UserStatus.ACTIVE) {
                throw new AuthException(AuthErrorCode.USER_BLOCKED, "Account is suspended. Status: " + existing.getStatus());
            }
            existing.setUpdatedAt(LocalDateTime.now());
            LogContract.info(log, LogDomain.AUTH, "customer_recognized")
                    .outcome(LogOutcome.SUCCESS)
                    .entity("user", existing.getId())
                    .field("contact", LogContract.maskEmail(contact))
                    .emit();
            return existing;
        }

        // New customer: auto-provision central record
        boolean isEmail = contact.contains("@");
        String email = isEmail ? contact : null;
        String phone = isEmail ? null : contact;
        String derivedName = isEmail ? extractDefaultName(email) : "Customer " + (phone.length() >= 4 ? phone.substring(phone.length() - 4) : phone);

        CentralUser newUser = CentralUser.builder()
                .id(UUID.randomUUID().toString())
                .email(email)
                .phoneNumber(phone)
                .fullName(derivedName)
                .status(UserStatus.ACTIVE)
                .isDeleted(false)
                .build();

        CentralUser savedUser = centralUserRepository.save(newUser);
        LogContract.info(log, LogDomain.AUTH, "customer_provisioned")
                .outcome(LogOutcome.SUCCESS)
                .entity("user", savedUser.getId())
                .field("contact", LogContract.maskEmail(contact))
                .emit();
        return savedUser;
    }

    private String extractDefaultName(String email) {
        int atIndex = email.indexOf('@');
        if (atIndex > 0) {
            String prefix = email.substring(0, atIndex).replace('.', ' ').replace('_', ' ');
            return capitalize(prefix);
        }
        return "Customer";
    }

    private String capitalize(String text) {
        if (text == null || text.isBlank()) {
            return "Customer";
        }
        String[] parts = text.split("\\s+");
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (!part.isBlank()) {
                sb.append(Character.toUpperCase(part.charAt(0)))
                        .append(part.substring(1).toLowerCase())
                        .append(" ");
            }
        }
        return sb.toString().trim();
    }

    /**
     * Logs out the customer, revoking the active Keycloak refresh token and clearing SecurityContext.
     *
     * @param request optional {@link LogoutRequest} containing the refresh token
     */
    public void logout(LogoutRequest request) {
        if (request != null && request.refreshToken() != null && !request.refreshToken().isBlank()) {
            if (keycloakTokenService != null) {
                keycloakTokenService.logout(request.refreshToken());
            }
        }
        SecurityContextHolder.clearContext();
        TenantContextHolder.clear();
        LogContract.info(log, LogDomain.AUTH, "customer_logout")
                .outcome(LogOutcome.SUCCESS)
                .emit();
    }
}
