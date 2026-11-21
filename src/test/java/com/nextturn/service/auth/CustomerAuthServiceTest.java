package com.nextturn.service.auth;

import com.nextturn.dto.auth.AuthTokenResponse;
import com.nextturn.dto.auth.CustomerOtpResponse;
import com.nextturn.dto.auth.CustomerSendOtpRequest;
import com.nextturn.dto.auth.CustomerVerifyOtpRequest;
import com.nextturn.entity.CentralAllowedDomain;
import com.nextturn.entity.CentralTenant;
import com.nextturn.entity.CentralUser;
import com.nextturn.enums.TenantStatus;
import com.nextturn.enums.UserRole;
import com.nextturn.enums.UserStatus;
import com.nextturn.exception.auth.AuthErrorCode;
import com.nextturn.exception.auth.AuthException;
import com.nextturn.infrastructure.security.keycloak.KeycloakTokenService;
import com.nextturn.repository.auth.CentralAllowedDomainRepository;
import com.nextturn.repository.auth.CentralTenantRepository;
import com.nextturn.repository.auth.CentralUserRepository;
import com.nextturn.service.auth.impl.CustomerAuthServiceImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CustomerAuthServiceTest {

    @Mock
    private CentralUserRepository centralUserRepository;

    @Mock
    private OtpService otpService;

    @Mock
    private KeycloakTokenService keycloakTokenService;

    @Mock
    private CentralTenantRepository centralTenantRepository;

    @Mock
    private CentralAllowedDomainRepository centralAllowedDomainRepository;

    @InjectMocks
    private CustomerAuthServiceImpl customerAuthService;

    @Test
    @DisplayName("Should detect existing active user and dispatch OTP with existingUser=true")
    void shouldSendOtpForExistingUser() {
        CentralUser existing = CentralUser.builder()
                .id("usr-existing-001")
                .email("jane@example.com")
                .fullName("Jane Doe")
                .status(UserStatus.ACTIVE)
                .build();

        when(centralUserRepository.findByEmailIgnoreCaseAndIsDeletedFalse("jane@example.com"))
                .thenReturn(Optional.of(existing));

        CustomerOtpResponse mockOtpResponse = CustomerOtpResponse.builder()
                .email("jane@example.com")
                .expiresInSeconds(300)
                .resendCooldownSeconds(60)
                .message("Verification code has been dispatched")
                .existingUser(true)
                .build();

        when(otpService.generateAndSendOtp("jane@example.com", true))
                .thenReturn(mockOtpResponse);

        CustomerSendOtpRequest request = CustomerSendOtpRequest.builder()
                .email("jane@example.com")
                .build();

        CustomerOtpResponse response = customerAuthService.sendOtp(request);

        assertThat(response).isNotNull();
        assertThat(response.isExistingUser()).isTrue();
        verify(otpService).generateAndSendOtp("jane@example.com", true);
    }

    @Test
    @DisplayName("Should detect new user and dispatch OTP with existingUser=false")
    void shouldSendOtpForNewUser() {
        when(centralUserRepository.findByEmailIgnoreCaseAndIsDeletedFalse("new@example.com"))
                .thenReturn(Optional.empty());

        CustomerOtpResponse mockOtpResponse = CustomerOtpResponse.builder()
                .email("new@example.com")
                .expiresInSeconds(300)
                .resendCooldownSeconds(60)
                .message("Verification code has been dispatched")
                .existingUser(false)
                .build();

        when(otpService.generateAndSendOtp("new@example.com", false))
                .thenReturn(mockOtpResponse);

        CustomerSendOtpRequest request = CustomerSendOtpRequest.builder()
                .email("new@example.com")
                .build();

        CustomerOtpResponse response = customerAuthService.sendOtp(request);

        assertThat(response).isNotNull();
        assertThat(response.isExistingUser()).isFalse();
        verify(otpService).generateAndSendOtp("new@example.com", false);
    }

    @Test
    @DisplayName("Should reject OTP dispatch when user account is BLOCKED")
    void shouldThrowWhenSendingOtpToBlockedUser() {
        CentralUser blockedUser = CentralUser.builder()
                .id("usr-blocked-001")
                .email("badactor@example.com")
                .status(UserStatus.BLOCKED)
                .build();

        when(centralUserRepository.findByEmailIgnoreCaseAndIsDeletedFalse("badactor@example.com"))
                .thenReturn(Optional.of(blockedUser));

        CustomerSendOtpRequest request = CustomerSendOtpRequest.builder()
                .email("badactor@example.com")
                .build();

        assertThatThrownBy(() -> customerAuthService.sendOtp(request))
                .isInstanceOf(AuthException.class)
                .satisfies(ex -> assertThat(((AuthException) ex).getCode()).isEqualTo(AuthErrorCode.USER_BLOCKED.code()));

        verify(otpService, never()).generateAndSendOtp(any(), any(Boolean.class));
    }

    @Test
    @DisplayName("Should validate widget integration successfully when API key and domain match whitelist (REQ-AUTH-06)")
    void shouldValidateWidgetIntegrationSuccessfully() {
        CentralTenant tenant = CentralTenant.builder()
                .id("t-001")
                .tenantCode("clinic_alpha")
                .status(TenantStatus.ACTIVE)
                .widgetApiKey("wtk_live_valid123")
                .build();

        when(centralTenantRepository.findByWidgetApiKeyAndIsDeletedFalse("wtk_live_valid123"))
                .thenReturn(Optional.of(tenant));

        when(centralAllowedDomainRepository.findByTenantId("t-001"))
                .thenReturn(List.of(
                        CentralAllowedDomain.builder().id("ad-1").tenantId("t-001").domainName("spa-alpha.vn").build()
                ));

        CustomerSendOtpRequest request = CustomerSendOtpRequest.builder()
                .email("patient@spa.vn")
                .widgetApiKey("wtk_live_valid123")
                .originDomain("https://spa-alpha.vn:3000/booking")
                .build();

        CustomerOtpResponse mockOtpResponse = CustomerOtpResponse.builder()
                .email("patient@spa.vn")
                .expiresInSeconds(300)
                .resendCooldownSeconds(60)
                .build();

        when(otpService.generateAndSendOtp("patient@spa.vn", false)).thenReturn(mockOtpResponse);

        CustomerOtpResponse response = customerAuthService.sendOtp(request);
        assertThat(response).isNotNull();
    }

    @Test
    @DisplayName("Should reject widget dispatch when widgetApiKey is invalid (REQ-AUTH-06 Exception 3)")
    void shouldRejectWidgetWhenApiKeyIsInvalid() {
        when(centralTenantRepository.findByWidgetApiKeyAndIsDeletedFalse("wtk_unknown"))
                .thenReturn(Optional.empty());

        CustomerSendOtpRequest request = CustomerSendOtpRequest.builder()
                .email("test@example.com")
                .widgetApiKey("wtk_unknown")
                .originDomain("example.com")
                .build();

        assertThatThrownBy(() -> customerAuthService.sendOtp(request))
                .isInstanceOf(AuthException.class)
                .satisfies(ex -> assertThat(((AuthException) ex).getCode()).isEqualTo(AuthErrorCode.WIDGET_KEY_INVALID.code()));
    }

    @Test
    @DisplayName("Should reject widget dispatch when origin domain is unauthorized (REQ-AUTH-06 Exception 3)")
    void shouldRejectWidgetWhenDomainIsUnauthorized() {
        CentralTenant tenant = CentralTenant.builder()
                .id("t-001")
                .tenantCode("clinic_alpha")
                .status(TenantStatus.ACTIVE)
                .widgetApiKey("wtk_live_valid123")
                .build();

        when(centralTenantRepository.findByWidgetApiKeyAndIsDeletedFalse("wtk_live_valid123"))
                .thenReturn(Optional.of(tenant));

        when(centralAllowedDomainRepository.findByTenantId("t-001"))
                .thenReturn(List.of(
                        CentralAllowedDomain.builder().id("ad-1").tenantId("t-001").domainName("trusted-site.com").build()
                ));

        CustomerSendOtpRequest request = CustomerSendOtpRequest.builder()
                .email("test@example.com")
                .widgetApiKey("wtk_live_valid123")
                .originDomain("hacker-site.org")
                .build();

        assertThatThrownBy(() -> customerAuthService.sendOtp(request))
                .isInstanceOf(AuthException.class)
                .satisfies(ex -> assertThat(((AuthException) ex).getCode()).isEqualTo(AuthErrorCode.UNAUTHORIZED_ORIGIN_DOMAIN.code()));
    }

    @Test
    @DisplayName("Should reject widget dispatch when tenant facility is inactive")
    void shouldRejectWidgetWhenTenantIsInactive() {
        CentralTenant tenant = CentralTenant.builder()
                .id("t-002")
                .tenantCode("clinic_inactive")
                .status(TenantStatus.SUSPENDED)
                .widgetApiKey("wtk_live_blocked")
                .build();

        when(centralTenantRepository.findByWidgetApiKeyAndIsDeletedFalse("wtk_live_blocked"))
                .thenReturn(Optional.of(tenant));

        CustomerSendOtpRequest request = CustomerSendOtpRequest.builder()
                .email("test@example.com")
                .widgetApiKey("wtk_live_blocked")
                .originDomain("clinic.vn")
                .build();

        assertThatThrownBy(() -> customerAuthService.sendOtp(request))
                .isInstanceOf(AuthException.class)
                .satisfies(ex -> assertThat(((AuthException) ex).getCode()).isEqualTo(AuthErrorCode.TENANT_INACTIVE.code()));
    }

    @Test
    @DisplayName("Should verify OTP and REUSE existing user account without re-creating to preserve booking history (REQ-AUTH-07)")
    void shouldVerifyOtpAndReuseExistingUserPreservingHistory() {
        CentralUser existingUser = CentralUser.builder()
                .id("usr-patient-persistent-999")
                .email("patient@clinic.com")
                .fullName("Alex Nguyen")
                .status(UserStatus.ACTIVE)
                .build();

        when(centralUserRepository.findByEmailIgnoreCaseAndIsDeletedFalse("patient@clinic.com"))
                .thenReturn(Optional.of(existingUser));

        AuthTokenResponse mockKeycloakResponse = AuthTokenResponse.builder()
                .accessToken("mock-jwt-customer-token")
                .refreshToken("mock-jwt-refresh-token")
                .refreshExpiresIn(2592000L) // 30-day session
                .userId("usr-patient-persistent-999")
                .email("patient@clinic.com")
                .roles(List.of(UserRole.END_USER_VALUE))
                .primaryRole(UserRole.END_USER_VALUE)
                .targetDashboardUrl("/customer/home")
                .build();

        when(keycloakTokenService.issueCustomerTokens("patient@clinic.com", "usr-patient-persistent-999"))
                .thenReturn(mockKeycloakResponse);

        CustomerVerifyOtpRequest request = CustomerVerifyOtpRequest.builder()
                .email("patient@clinic.com")
                .otp("654321")
                .build();

        AuthTokenResponse response = customerAuthService.verifyOtp(request);

        assertThat(response).isNotNull();
        assertThat(response.getUserId()).isEqualTo("usr-patient-persistent-999");
        assertThat(response.getPrimaryRole()).isEqualTo(UserRole.END_USER_VALUE);
        assertThat(response.getRefreshExpiresIn()).isEqualTo(2592000L);

        // Verify OTP was validated
        verify(otpService).verifyOtp("patient@clinic.com", "654321");

        // Verify that centralUserRepository.save was NEVER called because user already existed
        verify(centralUserRepository, never()).save(any());
        verify(keycloakTokenService).syncCustomerIdentity("patient@clinic.com", "Alex Nguyen");
        verify(keycloakTokenService).issueCustomerTokens("patient@clinic.com", "usr-patient-persistent-999");
    }

    @Test
    @DisplayName("Should verify OTP and AUTO-PROVISION new central user when email does not exist (REQ-AUTH-07)")
    void shouldVerifyOtpAndAutoProvisionNewUserWhenEmailDoesNotExist() {
        when(centralUserRepository.findByEmailIgnoreCaseAndIsDeletedFalse("firsttimer@domain.com"))
                .thenReturn(Optional.empty());

        when(centralUserRepository.save(any(CentralUser.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        AuthTokenResponse mockKeycloakResponse = AuthTokenResponse.builder()
                .accessToken("mock-firsttimer-token")
                .userId("new-generated-uuid")
                .email("firsttimer@domain.com")
                .refreshExpiresIn(2592000L)
                .roles(List.of(UserRole.END_USER_VALUE))
                .primaryRole(UserRole.END_USER_VALUE)
                .build();

        when(keycloakTokenService.issueCustomerTokens(eq("firsttimer@domain.com"), any()))
                .thenReturn(mockKeycloakResponse);

        CustomerVerifyOtpRequest request = CustomerVerifyOtpRequest.builder()
                .email("firsttimer@domain.com")
                .otp("112233")
                .build();

        AuthTokenResponse response = customerAuthService.verifyOtp(request);

        assertThat(response).isNotNull();
        verify(otpService).verifyOtp("firsttimer@domain.com", "112233");

        ArgumentCaptor<CentralUser> userCaptor = ArgumentCaptor.forClass(CentralUser.class);
        verify(centralUserRepository).save(userCaptor.capture());
        CentralUser savedUser = userCaptor.getValue();
        assertThat(savedUser.getEmail()).isEqualTo("firsttimer@domain.com");
        assertThat(savedUser.getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(savedUser.getFullName()).isEqualTo("Firsttimer");
        verify(keycloakTokenService).syncCustomerIdentity("firsttimer@domain.com", "Firsttimer");
    }

    @Test
    @DisplayName("Should support passwordless phone OTP and auto-provision user with phoneNumber (REQ-AUTH-06 & REQ-AUTH-07)")
    void shouldSupportPhoneOtpVerification() {
        when(centralUserRepository.findByPhoneNumberAndIsDeletedFalse("+84987654321"))
                .thenReturn(Optional.empty());

        when(centralUserRepository.save(any(CentralUser.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        AuthTokenResponse mockKeycloakResponse = AuthTokenResponse.builder()
                .accessToken("mock-phone-token")
                .userId("phone-user-uuid")
                .refreshExpiresIn(2592000L)
                .roles(List.of(UserRole.END_USER_VALUE))
                .primaryRole(UserRole.END_USER_VALUE)
                .build();

        when(keycloakTokenService.issueCustomerTokens(eq("+84987654321"), any()))
                .thenReturn(mockKeycloakResponse);

        CustomerVerifyOtpRequest request = CustomerVerifyOtpRequest.builder()
                .phoneNumber("+84987654321")
                .otp("889900")
                .build();

        AuthTokenResponse response = customerAuthService.verifyOtp(request);

        assertThat(response).isNotNull();
        verify(otpService).verifyOtp("+84987654321", "889900");

        ArgumentCaptor<CentralUser> userCaptor = ArgumentCaptor.forClass(CentralUser.class);
        verify(centralUserRepository).save(userCaptor.capture());
        CentralUser saved = userCaptor.getValue();
        assertThat(saved.getPhoneNumber()).isEqualTo("+84987654321");
        assertThat(saved.getEmail()).isNull();
    }

    @Test
    @DisplayName("Should reject verification when user identity is blocked in Keycloak")
    void shouldRejectVerificationWhenUserBlockedInKeycloak() {
        CentralUser existingUser = CentralUser.builder()
                .id("usr-blocked-kc-123")
                .email("kcblocked@domain.com")
                .fullName("KC Blocked")
                .status(UserStatus.ACTIVE)
                .build();

        when(centralUserRepository.findByEmailIgnoreCaseAndIsDeletedFalse("kcblocked@domain.com"))
                .thenReturn(Optional.of(existingUser));

        when(keycloakTokenService.syncCustomerIdentity("kcblocked@domain.com", "KC Blocked"))
                .thenThrow(new AuthException(AuthErrorCode.USER_BLOCKED, "Customer account is disabled in Keycloak"));

        CustomerVerifyOtpRequest request = CustomerVerifyOtpRequest.builder()
                .email("kcblocked@domain.com")
                .otp("112233")
                .build();

        assertThatThrownBy(() -> customerAuthService.verifyOtp(request))
                .isInstanceOf(AuthException.class)
                .satisfies(ex -> assertThat(((AuthException) ex).getCode()).isEqualTo(AuthErrorCode.USER_BLOCKED.code()));

        verify(keycloakTokenService, never()).issueCustomerTokens(any(), any());
    }

    @Test
    @DisplayName("isDomainWhitelisted should reject evil suffix attacks like evilpkalpha.vn matching *.pkalpha.vn (DF-AUTH-02 / U15 UTCID11)")
    void isDomainWhitelistedShouldRejectEvilSuffixAttack() {
        List<CentralAllowedDomain> allowed = List.of(
                CentralAllowedDomain.builder().id("ad-1").tenantId("t-1").domainName("*.pkalpha.vn").build()
        );

        // evilpkalpha.vn must NOT match *.pkalpha.vn
        assertThat(CustomerAuthService.isDomainWhitelisted("evilpkalpha.vn", allowed)).isFalse();
        // Also test with protocol and port
        assertThat(CustomerAuthService.isDomainWhitelisted("https://evilpkalpha.vn", allowed)).isFalse();
        assertThat(CustomerAuthService.isDomainWhitelisted("evilpkalpha.vn:8080", allowed)).isFalse();

        // Also test with List of Strings
        assertThat(CustomerAuthService.isDomainWhitelisted("evilpkalpha.vn", List.of("*.pkalpha.vn"))).isFalse();
    }

    @Test
    @DisplayName("isDomainWhitelisted should accept valid subdomains for wildcard whitelist")
    void isDomainWhitelistedShouldAcceptValidSubdomains() {
        List<CentralAllowedDomain> allowed = List.of(
                CentralAllowedDomain.builder().id("ad-1").tenantId("t-1").domainName("*.pkalpha.vn").build()
        );

        assertThat(CustomerAuthService.isDomainWhitelisted("sub.pkalpha.vn", allowed)).isTrue();
        assertThat(CustomerAuthService.isDomainWhitelisted("app.pkalpha.vn", allowed)).isTrue();
        assertThat(CustomerAuthService.isDomainWhitelisted("deep.sub.pkalpha.vn", allowed)).isTrue();
        assertThat(CustomerAuthService.isDomainWhitelisted("sub.pkalpha.vn:3000", allowed)).isTrue();
        assertThat(CustomerAuthService.isDomainWhitelisted("https://sub.pkalpha.vn/booking", allowed)).isTrue();

        // With String list
        assertThat(CustomerAuthService.isDomainWhitelisted("sub.pkalpha.vn", List.of("*.pkalpha.vn"))).isTrue();
    }

    @Test
    @DisplayName("isDomainWhitelisted should handle exact match, apex domain, global wildcard, and edge cases")
    void isDomainWhitelistedShouldHandleEdgeCases() {
        List<CentralAllowedDomain> allowedExact = List.of(
                CentralAllowedDomain.builder().id("ad-1").tenantId("t-1").domainName("pkalpha.vn").build()
        );
        assertThat(CustomerAuthService.isDomainWhitelisted("pkalpha.vn", allowedExact)).isTrue();
        assertThat(CustomerAuthService.isDomainWhitelisted("pkalpha.vn:8080", allowedExact)).isTrue();
        assertThat(CustomerAuthService.isDomainWhitelisted("sub.pkalpha.vn", allowedExact)).isFalse();

        List<CentralAllowedDomain> allowedGlobal = List.of(
                CentralAllowedDomain.builder().id("ad-2").tenantId("t-1").domainName("*").build()
        );
        assertThat(CustomerAuthService.isDomainWhitelisted("random.org", allowedGlobal)).isTrue();

        // Null and blank checks
        assertThat(CustomerAuthService.isDomainWhitelisted(null, allowedExact)).isFalse();
        assertThat(CustomerAuthService.isDomainWhitelisted("   ", allowedExact)).isFalse();
        assertThat(CustomerAuthService.isDomainWhitelisted("pkalpha.vn", (List<?>) null)).isFalse();
        assertThat(CustomerAuthService.isDomainWhitelisted("pkalpha.vn", List.of())).isFalse();
    }

    @Test
    @DisplayName("Should authenticate customer via Widget HMAC-SHA256 SSO without OTP when signature and timestamp are valid (REQ-AUTH-08)")
    void shouldAuthenticateWidgetSsoWithValidSignature() {
        String widgetKey = "nt_pub_clinic123";
        String widgetSecret = "nt_sec_supersecret999";
        long nowSeconds = java.time.Instant.now().getEpochSecond();

        CentralTenant tenant = CentralTenant.builder()
                .id("t-sso-01")
                .tenantCode("clinic_alpha")
                .status(TenantStatus.ACTIVE)
                .widgetApiKey(widgetKey)
                .widgetSecretKey(widgetSecret)
                .build();

        when(centralTenantRepository.findByWidgetApiKeyAndIsDeletedFalse(widgetKey))
                .thenReturn(Optional.of(tenant));
        when(centralUserRepository.findByEmailIgnoreCaseAndIsDeletedFalse("member@enterprise.vn"))
                .thenReturn(Optional.empty());
        when(centralUserRepository.save(any(CentralUser.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        AuthTokenResponse mockToken = AuthTokenResponse.builder()
                .accessToken("jwt-widget-sso-token")
                .refreshToken("jwt-widget-sso-refresh")
                .refreshExpiresIn(2592000L)
                .email("member@enterprise.vn")
                .primaryRole(UserRole.END_USER_VALUE)
                .build();
        when(keycloakTokenService.issueCustomerTokens(eq("member@enterprise.vn"), any()))
                .thenReturn(mockToken);

        String payload = com.nextturn.service.auth.impl.CustomerAuthServiceImpl.buildCanonicalWidgetPayload(
                widgetKey, "MEM-001", "member@enterprise.vn", "0909123456", "Nguyễn Văn Thành Viên", nowSeconds
        );
        String signature = com.nextturn.service.auth.impl.CustomerAuthServiceImpl.computeHmacSha256Hex(payload, widgetSecret);

        com.nextturn.dto.auth.CustomerWidgetSsoRequest request = com.nextturn.dto.auth.CustomerWidgetSsoRequest.builder()
                .widgetApiKey(widgetKey)
                .externalMemberId("MEM-001")
                .fullName("Nguyễn Văn Thành Viên")
                .email("member@enterprise.vn")
                .phoneNumber("0909123456")
                .timestamp(nowSeconds)
                .signature(signature)
                .build();

        AuthTokenResponse response = customerAuthService.authenticateWidgetSso(request);

        assertThat(response).isNotNull();
        assertThat(response.getAccessToken()).isEqualTo("jwt-widget-sso-token");
        verify(otpService, never()).verifyOtp(any(), any());
        verify(keycloakTokenService).syncCustomerIdentity("member@enterprise.vn", "Nguyễn Văn Thành Viên");
    }

    @Test
    @DisplayName("Should reject Widget SSO when HMAC signature is invalid or payload is tampered (REQ-AUTH-08 Exception 1)")
    void shouldRejectWidgetSsoWhenSignatureIsInvalid() {
        String widgetKey = "nt_pub_clinic123";
        long nowSeconds = java.time.Instant.now().getEpochSecond();

        CentralTenant tenant = CentralTenant.builder()
                .id("t-sso-01")
                .tenantCode("clinic_alpha")
                .status(TenantStatus.ACTIVE)
                .widgetApiKey(widgetKey)
                .widgetSecretKey("nt_sec_supersecret999")
                .build();

        when(centralTenantRepository.findByWidgetApiKeyAndIsDeletedFalse(widgetKey))
                .thenReturn(Optional.of(tenant));

        com.nextturn.dto.auth.CustomerWidgetSsoRequest request = com.nextturn.dto.auth.CustomerWidgetSsoRequest.builder()
                .widgetApiKey(widgetKey)
                .externalMemberId("MEM-001")
                .fullName("Tampered Name")
                .email("member@enterprise.vn")
                .phoneNumber("0909123456")
                .timestamp(nowSeconds)
                .signature("deadbeef0000invalidhmacsignature")
                .build();

        assertThatThrownBy(() -> customerAuthService.authenticateWidgetSso(request))
                .isInstanceOf(AuthException.class)
                .satisfies(ex -> assertThat(((AuthException) ex).getCode()).isEqualTo(AuthErrorCode.WIDGET_SIGNATURE_INVALID.code()));
    }

    @Test
    @DisplayName("Should reject Widget SSO when timestamp differs by more than 5 minutes (REQ-AUTH-08 Exception 2)")
    void shouldRejectWidgetSsoWhenTimestampIsExpired() {
        String widgetKey = "nt_pub_clinic123";
        long expiredTimestamp = java.time.Instant.now().getEpochSecond() - 360L; // 6 minutes ago

        CentralTenant tenant = CentralTenant.builder()
                .id("t-sso-01")
                .tenantCode("clinic_alpha")
                .status(TenantStatus.ACTIVE)
                .widgetApiKey(widgetKey)
                .widgetSecretKey("nt_sec_supersecret999")
                .build();

        when(centralTenantRepository.findByWidgetApiKeyAndIsDeletedFalse(widgetKey))
                .thenReturn(Optional.of(tenant));

        com.nextturn.dto.auth.CustomerWidgetSsoRequest request = com.nextturn.dto.auth.CustomerWidgetSsoRequest.builder()
                .widgetApiKey(widgetKey)
                .externalMemberId("MEM-001")
                .fullName("Nguyễn Văn Thành Viên")
                .email("member@enterprise.vn")
                .phoneNumber("0909123456")
                .timestamp(expiredTimestamp)
                .signature("any-signature")
                .build();

        assertThatThrownBy(() -> customerAuthService.authenticateWidgetSso(request))
                .isInstanceOf(AuthException.class)
                .satisfies(ex -> assertThat(((AuthException) ex).getCode()).isEqualTo(AuthErrorCode.WIDGET_SIGNATURE_EXPIRED.code()));
    }
}
