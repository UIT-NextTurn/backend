package com.nextturn.service.auth;

import com.nextturn.dto.auth.AuthTokenResponse;
import com.nextturn.dto.auth.CustomerOtpResponse;
import com.nextturn.dto.auth.CustomerSendOtpRequest;
import com.nextturn.dto.auth.CustomerVerifyOtpRequest;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

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
public class CustomerAuthService {

    private static final Logger log = LoggerFactory.getLogger(CustomerAuthService.class);

    private final CentralUserRepository centralUserRepository;
    private final OtpService otpService;
    private final KeycloakTokenService keycloakTokenService;
    private final CentralTenantRepository centralTenantRepository;
    private final CentralAllowedDomainRepository centralAllowedDomainRepository;

    @Autowired
    public CustomerAuthService(
            CentralUserRepository centralUserRepository,
            OtpService otpService,
            KeycloakTokenService keycloakTokenService,
            @Autowired(required = false) CentralTenantRepository centralTenantRepository,
            @Autowired(required = false) CentralAllowedDomainRepository centralAllowedDomainRepository
    ) {
        this.centralUserRepository = centralUserRepository;
        this.otpService = otpService;
        this.keycloakTokenService = keycloakTokenService;
        this.centralTenantRepository = centralTenantRepository;
        this.centralAllowedDomainRepository = centralAllowedDomainRepository;
    }

    public CustomerAuthService(
            CentralUserRepository centralUserRepository,
            OtpService otpService,
            KeycloakTokenService keycloakTokenService
    ) {
        this(centralUserRepository, otpService, keycloakTokenService, null, null);
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
            log.warn("Widget access rejected: Tenant facility [{}] is not active (status: {})", tenant.getTenantCode(), tenant.getStatus());
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
            log.warn("Widget access rejected for tenant [{}]: Origin domain could not be determined", tenant.getTenantCode());
            throw new AuthException(AuthErrorCode.UNAUTHORIZED_ORIGIN_DOMAIN, "Origin domain is missing or unverified for widget authentication");
        }

        if (centralAllowedDomainRepository != null) {
            List<CentralAllowedDomain> allowedDomains = centralAllowedDomainRepository.findByTenantId(tenant.getId());
            if (allowedDomains == null || allowedDomains.isEmpty() || !isDomainWhitelisted(candidateDomain, allowedDomains)) {
                log.warn("Widget access rejected for tenant [{}] from unauthorized origin [{}]", tenant.getTenantCode(), candidateDomain);
                throw new AuthException(
                        AuthErrorCode.UNAUTHORIZED_ORIGIN_DOMAIN,
                        "Website domain [" + candidateDomain + "] is not authorized to embed the widget for facility [" + tenant.getTenantCode() + "]"
                );
            }
        }

        log.info("Widget integration verified successfully for tenant [{}] from domain [{}]", tenant.getTenantCode(), candidateDomain);
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
    public static boolean isDomainWhitelisted(String candidateDomain, List<CentralAllowedDomain> allowedDomains) {
        if (candidateDomain == null || candidateDomain.isBlank() || allowedDomains == null || allowedDomains.isEmpty()) {
            return false;
        }
        String host = candidateDomain.trim().toLowerCase();
        String hostWithoutPort = host.contains(":") ? host.substring(0, host.indexOf(':')) : host;

        for (CentralAllowedDomain entry : allowedDomains) {
            if (entry.getDomainName() == null) {
                continue;
            }
            String allowed = entry.getDomainName().trim().toLowerCase();
            String allowedWithoutPort = allowed.contains(":") ? allowed.substring(0, allowed.indexOf(':')) : allowed;

            if ("*".equals(allowed)) {
                return true;
            }
            if (allowed.equals(host) || allowedWithoutPort.equals(hostWithoutPort)) {
                return true;
            }
            // Check wildcard subdomain: *.example.com matches sub.example.com
            if (allowed.startsWith("*.")) {
                String baseDomain = allowed.substring(2);
                if (hostWithoutPort.endsWith(baseDomain) && hostWithoutPort.length() > baseDomain.length()) {
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
                log.warn("OTP dispatch rejected for blocked user [{}] with status [{}]", contact, existingUser.getStatus());
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
    @Transactional
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

        // 2. Resolve existing user or auto-provision central record (updates login timestamp if existing)
        CentralUser user = resolveOrCreateUser(contact);

        // 3. Synchronize identity with Keycloak IAM
        String identityContact = user.getEmail() != null ? user.getEmail() : user.getPhoneNumber();
        keycloakTokenService.syncCustomerIdentity(identityContact, user.getFullName());

        // 4. Issue centralized Keycloak tokens with 30-day session lifetime (REQ-AUTH-07)
        return keycloakTokenService.issueCustomerTokens(identityContact, user.getId());
    }

    private Optional<CentralUser> findUserByContact(String contact) {
        if (contact.contains("@")) {
            return centralUserRepository.findByEmailIgnoreCaseAndIsDeletedFalse(contact);
        }
        return centralUserRepository.findByPhoneNumberAndIsDeletedFalse(contact);
    }

    private CentralUser resolveOrCreateUser(String contact) {
        Optional<CentralUser> existingUserOpt = findUserByContact(contact);

        if (existingUserOpt.isPresent()) {
            CentralUser existing = existingUserOpt.get();
            if (existing.getStatus() != UserStatus.ACTIVE) {
                throw new AuthException(AuthErrorCode.USER_BLOCKED, "Account is suspended. Status: " + existing.getStatus());
            }
            existing.setUpdatedAt(LocalDateTime.now());
            log.info("Existing END_USER recognized for contact [{}]. Reusing user ID [{}] to preserve history.", contact, existing.getId());
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
        log.info("Auto-provisioned new central user account for [{}] with ID [{}]", contact, savedUser.getId());
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
        log.info("Cleared security context and processed customer logout request");
    }
}
