package com.nextturn.service.auth;

import com.nextturn.dto.auth.CustomerOtpResponse;
import com.nextturn.exception.auth.AuthErrorCode;
import com.nextturn.exception.auth.AuthException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OtpServiceTest {

    private OtpService otpService;

    @BeforeEach
    void setUp() {
        // TTL = 300 seconds, max attempts = 3
        otpService = new OtpService(300, 3);
    }

    @Test
    @DisplayName("Should generate 6-digit OTP and return proper metadata")
    void shouldGenerateAndSendOtpSuccessfully() {
        CustomerOtpResponse response = otpService.generateAndSendOtp("customer@example.com", true);

        assertThat(response).isNotNull();
        assertThat(response.getEmail()).isEqualTo("customer@example.com");
        assertThat(response.getExpiresInSeconds()).isEqualTo(300);
        assertThat(response.isExistingUser()).isTrue();
    }

    @Test
    @DisplayName("Should throw AuthException when email is blank")
    void shouldThrowWhenEmailIsBlank() {
        assertThatThrownBy(() -> otpService.generateAndSendOtp("   ", false))
                .isInstanceOf(AuthException.class)
                .satisfies(ex -> assertThat(((AuthException) ex).getCode()).isEqualTo(AuthErrorCode.INVALID_CREDENTIALS.code()));
    }

    @Test
    @DisplayName("Should verify valid OTP successfully and clear it after use")
    void shouldVerifyValidOtpSuccessfullyAndClearAfterUse() {
        otpService.generateAndSendOtp("test@example.com", false);

        // Fetch generated code from internal record for test assertion
        OtpService.OtpRecord record = getRecord("test@example.com");
        assertThat(record).isNotNull();
        String validCode = record.code();

        // Verification should succeed
        otpService.verifyOtp("test@example.com", validCode);

        // Subsequent verification must fail because code was cleared
        assertThatThrownBy(() -> otpService.verifyOtp("test@example.com", validCode))
                .isInstanceOf(AuthException.class)
                .satisfies(ex -> assertThat(((AuthException) ex).getCode()).isEqualTo(AuthErrorCode.INVALID_OTP.code()));
    }

    @Test
    @DisplayName("Should throw INVALID_OTP on code mismatch and track remaining attempts")
    void shouldThrowOnOtpMismatchAndTrackAttempts() {
        otpService.generateAndSendOtp("test@example.com", false);

        assertThatThrownBy(() -> otpService.verifyOtp("test@example.com", "000000"))
                .isInstanceOf(AuthException.class)
                .satisfies(ex -> {
                    AuthException authEx = (AuthException) ex;
                    assertThat(authEx.getCode()).isEqualTo(AuthErrorCode.INVALID_OTP.code());
                    assertThat(authEx.getMessage()).contains("Attempts remaining: 2");
                });
    }

    @Test
    @DisplayName("Should lock and throw OTP_TOO_MANY_ATTEMPTS when max attempts exceeded")
    void shouldLockWhenMaxAttemptsExceeded() {
        otpService.generateAndSendOtp("test@example.com", false);

        // Fail 1
        assertThatThrownBy(() -> otpService.verifyOtp("test@example.com", "111111")).isInstanceOf(AuthException.class);
        // Fail 2
        assertThatThrownBy(() -> otpService.verifyOtp("test@example.com", "222222")).isInstanceOf(AuthException.class);
        // Fail 3
        assertThatThrownBy(() -> otpService.verifyOtp("test@example.com", "333333")).isInstanceOf(AuthException.class);

        // 4th attempt exceeds max attempts
        assertThatThrownBy(() -> otpService.verifyOtp("test@example.com", "444444"))
                .isInstanceOf(AuthException.class)
                .satisfies(ex -> assertThat(((AuthException) ex).getCode()).isEqualTo(AuthErrorCode.OTP_TOO_MANY_ATTEMPTS.code()));
    }

    @Test
    @DisplayName("Should persist and verify OTP via StringRedisTemplate when Valkey is available")
    @SuppressWarnings("unchecked")
    void shouldPersistAndVerifyOtpViaValkey() {
        org.springframework.data.redis.core.StringRedisTemplate mockRedis = org.mockito.Mockito.mock(org.springframework.data.redis.core.StringRedisTemplate.class);
        org.springframework.data.redis.core.ValueOperations<String, String> mockOps = org.mockito.Mockito.mock(org.springframework.data.redis.core.ValueOperations.class);
        org.mockito.Mockito.when(mockRedis.opsForValue()).thenReturn(mockOps);

        OtpService valkeyOtpService = new OtpService(mockRedis, 300, 3);
        valkeyOtpService.generateAndSendOtp("valkey@example.com", false);

        org.mockito.Mockito.verify(mockOps).set(
                org.mockito.ArgumentMatchers.eq(com.nextturn.constant.ValkeyKeyConstant.otpCodeKey("valkey@example.com")),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(java.time.Duration.class)
        );

        // Mock successful lookup
        org.mockito.Mockito.when(mockOps.get(com.nextturn.constant.ValkeyKeyConstant.otpCodeKey("valkey@example.com"))).thenReturn("654321");
        org.mockito.Mockito.when(mockOps.get(com.nextturn.constant.ValkeyKeyConstant.otpAttemptsKey("valkey@example.com"))).thenReturn("0");

        valkeyOtpService.verifyOtp("valkey@example.com", "654321");

        org.mockito.Mockito.verify(mockRedis).delete(org.mockito.ArgumentMatchers.anyList());
    }

    private OtpService.OtpRecord getRecord(String email) {
        try {
            var field = OtpService.class.getDeclaredField("otpStore");
            field.setAccessible(true);
            @SuppressWarnings("unchecked")
            var map = (java.util.Map<String, OtpService.OtpRecord>) field.get(otpService);
            return map.get(email.toLowerCase());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
