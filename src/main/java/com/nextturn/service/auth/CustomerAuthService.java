package com.nextturn.service.auth;

import com.nextturn.dto.auth.AuthTokenResponse;
import com.nextturn.dto.auth.CustomerOtpResponse;
import com.nextturn.dto.auth.CustomerSendOtpRequest;
import com.nextturn.dto.auth.CustomerVerifyOtpRequest;
import com.nextturn.dto.auth.LogoutRequest;
import com.nextturn.entity.CentralUser;
import com.nextturn.enums.UserStatus;
import com.nextturn.exception.auth.AuthErrorCode;
import com.nextturn.exception.auth.AuthException;
import com.nextturn.infrastructure.multitenancy.TenantContextHolder;
import com.nextturn.infrastructure.security.keycloak.KeycloakTokenService;
import com.nextturn.repository.auth.CentralUserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/**
 * Service managing passwordless authentication lifecycle for Public Customers (END_USER).
 * <p>
 * Implements:
 * 1. Email OTP dispatch and verification.
 * 2. Idempotent account resolution: Reuses existing database account to preserve booking and ticket history,
 *    or auto-provisions a new record if the email hasn't appeared before.
 * 3. Centralized Keycloak authentication and token issuance.
 */
@Service
public class CustomerAuthService {

    private static final Logger log = LoggerFactory.getLogger(CustomerAuthService.class);

    private final CentralUserRepository centralUserRepository;
    private final OtpService otpService;
    private final KeycloakTokenService keycloakTokenService;

    public CustomerAuthService(
            CentralUserRepository centralUserRepository,
            OtpService otpService,
            KeycloakTokenService keycloakTokenService
    ) {
        this.centralUserRepository = centralUserRepository;
        this.otpService = otpService;
        this.keycloakTokenService = keycloakTokenService;
    }

    /**
     * Sends an OTP verification code to the customer's email.
     *
     * @param request email payload
     * @return {@link CustomerOtpResponse} with code delivery and expiration metadata
     * @throws AuthException if email is blocked
     */
    @Transactional(readOnly = true)
    public CustomerOtpResponse sendOtp(CustomerSendOtpRequest request) {
        if (request == null || request.getEmail() == null || request.getEmail().isBlank()) {
            throw new AuthException(AuthErrorCode.INVALID_CREDENTIALS, "Email address must not be blank");
        }

        String cleanEmail = request.getEmail().trim().toLowerCase();

        // Check if an existing account exists and verify active status
        Optional<CentralUser> existingUserOpt = centralUserRepository.findByEmailIgnoreCaseAndIsDeletedFalse(cleanEmail);
        boolean isExistingUser = existingUserOpt.isPresent();

        if (isExistingUser) {
            CentralUser existingUser = existingUserOpt.get();
            if (existingUser.getStatus() != UserStatus.ACTIVE) {
                log.warn("OTP dispatch rejected for blocked user [{}] with status [{}]", cleanEmail, existingUser.getStatus());
                throw new AuthException(AuthErrorCode.USER_BLOCKED, "Account is suspended. Status: " + existingUser.getStatus());
            }
        }

        return otpService.generateAndSendOtp(cleanEmail, isExistingUser);
    }

    /**
     * Verifies the OTP, resolves or creates the central user record, and issues Keycloak tokens.
     * <p>
     * Ensures existing users retain their persistent ID to preserve booking history.
     *
     * @param request email and OTP verification payload
     * @return {@link AuthTokenResponse} containing Keycloak access & refresh tokens
     * @throws AuthException if OTP is invalid, expired, or user is suspended
     */
    @Transactional
    public AuthTokenResponse verifyOtp(CustomerVerifyOtpRequest request) {
        if (request == null || request.getEmail() == null || request.getEmail().isBlank()) {
            throw new AuthException(AuthErrorCode.INVALID_CREDENTIALS, "Email address must not be blank");
        }
        if (request.getOtp() == null || request.getOtp().isBlank()) {
            throw new AuthException(AuthErrorCode.INVALID_OTP, "Verification code must not be blank");
        }

        String cleanEmail = request.getEmail().trim().toLowerCase();
        String submittedOtp = request.getOtp().trim();

        // 1. Verify OTP with OtpService (Valkey / in-memory rate-limiting)
        otpService.verifyOtp(cleanEmail, submittedOtp);

        // 2. Resolve or provision CentralUser record in master schema (preserves history)
        CentralUser user = resolveOrCreateUser(cleanEmail);

        // 3. Synchronize identity with Keycloak IAM (validates account lock & provisions Keycloak record)
        keycloakTokenService.syncCustomerIdentity(cleanEmail, user.getFullName());

        // 4. Issue centralized Keycloak tokens for END_USER persona
        return keycloakTokenService.issueCustomerTokens(cleanEmail, user.getId());
    }

    /**
     * Idempotently resolves existing user or auto-creates a new customer record.
     * Never creates a new account if the email already exists in the database.
     *
     * @param email normalized email address
     * @return active {@link CentralUser}
     */
    private CentralUser resolveOrCreateUser(String email) {
        Optional<CentralUser> existingUserOpt = centralUserRepository.findByEmailIgnoreCaseAndIsDeletedFalse(email);

        if (existingUserOpt.isPresent()) {
            CentralUser existing = existingUserOpt.get();
            if (existing.getStatus() != UserStatus.ACTIVE) {
                throw new AuthException(AuthErrorCode.USER_BLOCKED, "Account is suspended. Status: " + existing.getStatus());
            }
            log.info("Existing END_USER recognized for email [{}]. Reusing user ID [{}] to preserve history.", email, existing.getId());
            return existing;
        }

        // New customer: auto-provision central record
        String derivedName = extractDefaultName(email);
        CentralUser newUser = CentralUser.builder()
                .id(UUID.randomUUID().toString())
                .email(email)
                .fullName(derivedName)
                .status(UserStatus.ACTIVE)
                .isDeleted(false)
                .build();

        CentralUser savedUser = centralUserRepository.save(newUser);
        log.info("Auto-provisioned new central user account for [{}] with ID [{}]", email, savedUser.getId());
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
