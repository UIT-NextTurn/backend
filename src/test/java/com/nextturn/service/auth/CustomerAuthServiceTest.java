package com.nextturn.service.auth;

import com.nextturn.dto.auth.AuthTokenResponse;
import com.nextturn.dto.auth.CustomerOtpResponse;
import com.nextturn.dto.auth.CustomerSendOtpRequest;
import com.nextturn.dto.auth.CustomerVerifyOtpRequest;
import com.nextturn.entity.CentralUser;
import com.nextturn.enums.UserRole;
import com.nextturn.enums.UserStatus;
import com.nextturn.exception.auth.AuthErrorCode;
import com.nextturn.exception.auth.AuthException;
import com.nextturn.infrastructure.security.keycloak.KeycloakTokenService;
import com.nextturn.repository.auth.CentralUserRepository;
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

    @InjectMocks
    private CustomerAuthService customerAuthService;

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
    @DisplayName("Should verify OTP and REUSE existing user account without re-creating to preserve booking history")
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

        // Verify OTP was validated
        verify(otpService).verifyOtp("patient@clinic.com", "654321");

        // Verify that centralUserRepository.save was NEVER called because user already existed
        verify(centralUserRepository, never()).save(any());
        verify(keycloakTokenService).syncCustomerIdentity("patient@clinic.com", "Alex Nguyen");
        verify(keycloakTokenService).issueCustomerTokens("patient@clinic.com", "usr-patient-persistent-999");
    }

    @Test
    @DisplayName("Should verify OTP and AUTO-PROVISION new central user when email does not exist")
    void shouldVerifyOtpAndAutoProvisionNewUserWhenEmailDoesNotExist() {
        when(centralUserRepository.findByEmailIgnoreCaseAndIsDeletedFalse("firsttimer@domain.com"))
                .thenReturn(Optional.empty());

        when(centralUserRepository.save(any(CentralUser.class)))
                .thenAnswer(invocation -> {
                    CentralUser u = invocation.getArgument(0);
                    return u;
                });

        AuthTokenResponse mockKeycloakResponse = AuthTokenResponse.builder()
                .accessToken("mock-firsttimer-token")
                .userId("new-generated-uuid")
                .email("firsttimer@domain.com")
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
}
