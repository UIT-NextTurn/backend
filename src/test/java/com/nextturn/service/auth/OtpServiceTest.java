package com.nextturn.service.auth;

import com.nextturn.constant.ValkeyKeyConstant;
import com.nextturn.dto.auth.CustomerOtpResponse;
import com.nextturn.exception.auth.AuthErrorCode;
import com.nextturn.exception.auth.AuthException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OtpServiceTest {

    private OtpService otpService;

    @BeforeEach
    void setUp() {
        // TTL = 300 seconds (5 min), max attempts = 3, cooldown = 60s, max daily = 5
        otpService = new OtpService(null, null, 300, 3, 60, 5);
    }

    @Test
    @DisplayName("Should generate 6-digit OTP and return proper metadata with 60s cooldown (REQ-AUTH-06)")
    void shouldGenerateAndSendOtpSuccessfully() {
        CustomerOtpResponse response = otpService.generateAndSendOtp("customer@example.com", true);

        assertThat(response).isNotNull();
        assertThat(response.getEmail()).isEqualTo("customer@example.com");
        assertThat(response.getExpiresInSeconds()).isEqualTo(300);
        assertThat(response.getResendCooldownSeconds()).isEqualTo(60);
        assertThat(response.isExistingUser()).isTrue();
    }

    @Test
    @DisplayName("Should enforce 60-second cooldown between consecutive requests (REQ-AUTH-06 Exception 1)")
    void shouldEnforceCooldownBetweenConsecutiveRequests() {
        otpService.generateAndSendOtp("cooldown@example.com", false);

        // Immediate second request must be rejected with 429 OTP_COOLDOWN_ACTIVE
        assertThatThrownBy(() -> otpService.generateAndSendOtp("cooldown@example.com", false))
                .isInstanceOf(AuthException.class)
                .satisfies(ex -> {
                    AuthException authEx = (AuthException) ex;
                    assertThat(authEx.getCode()).isEqualTo(AuthErrorCode.OTP_COOLDOWN_ACTIVE.code());
                    assertThat(authEx.getMessage()).contains("Please wait");
                    assertThat(authEx.getMessage()).contains("seconds before requesting a new verification code.");
                });
    }

    @Test
    @DisplayName("Should enforce 5 requests per 24 hours daily limit (REQ-AUTH-06 Exception 2)")
    void shouldEnforceDailyLimitOf5Requests() {
        String contact = "daily@example.com";

        for (int i = 1; i <= 5; i++) {
            otpService.clearCooldown(contact); // bypass cooldown to simulate 5 requests over the day
            CustomerOtpResponse resp = otpService.generateAndSendOtp(contact, false);
            assertThat(resp).isNotNull();
        }

        // 6th request within 24h must be rejected with OTP_DAILY_LIMIT_EXCEEDED
        otpService.clearCooldown(contact);
        assertThatThrownBy(() -> otpService.generateAndSendOtp(contact, false))
                .isInstanceOf(AuthException.class)
                .satisfies(ex -> {
                    AuthException authEx = (AuthException) ex;
                    assertThat(authEx.getCode()).isEqualTo(AuthErrorCode.OTP_DAILY_LIMIT_EXCEEDED.code());
                    assertThat(authEx.getMessage()).contains("Daily limit of 5 verification codes reached");
                });
    }

    @Test
    @DisplayName("Should throw AuthException when email is blank")
    void shouldThrowWhenEmailIsBlank() {
        assertThatThrownBy(() -> otpService.generateAndSendOtp("   ", false))
                .isInstanceOf(AuthException.class)
                .satisfies(ex -> assertThat(((AuthException) ex).getCode()).isEqualTo(AuthErrorCode.INVALID_CREDENTIALS.code()));
    }

    @Test
    @DisplayName("Should verify valid OTP successfully and clear it after use (REQ-AUTH-07)")
    void shouldVerifyValidOtpSuccessfullyAndClearAfterUse() {
        otpService.generateAndSendOtp("test@example.com", false);

        // Fetch generated code from internal record for test assertion
        OtpService.OtpRecord record = getRecord("test@example.com");
        assertThat(record).isNotNull();
        String validCode = record.code();

        // Verification should succeed
        otpService.verifyOtp("test@example.com", validCode);

        // Subsequent verification must fail because code was cleared immediately
        assertThatThrownBy(() -> otpService.verifyOtp("test@example.com", validCode))
                .isInstanceOf(AuthException.class)
                .satisfies(ex -> assertThat(((AuthException) ex).getCode()).isEqualTo(AuthErrorCode.INVALID_OTP.code()));
    }

    @Test
    @DisplayName("Should throw INVALID_OTP on code mismatch and track remaining attempts (REQ-AUTH-07 Exception 1)")
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
    @DisplayName("Should lock and throw OTP_TOO_MANY_ATTEMPTS on 3rd failed attempt (REQ-AUTH-07 Exception 1)")
    void shouldLockWhenMaxAttemptsExceeded() {
        otpService.generateAndSendOtp("test@example.com", false);

        // Fail 1: 2 attempts remaining
        assertThatThrownBy(() -> otpService.verifyOtp("test@example.com", "111111"))
                .isInstanceOf(AuthException.class)
                .hasMessageContaining("Attempts remaining: 2");

        // Fail 2: 1 attempt remaining
        assertThatThrownBy(() -> otpService.verifyOtp("test@example.com", "222222"))
                .isInstanceOf(AuthException.class)
                .hasMessageContaining("Attempts remaining: 1");

        // Fail 3: Max 3 attempts reached -> immediately destroyed and locked
        assertThatThrownBy(() -> otpService.verifyOtp("test@example.com", "333333"))
                .isInstanceOf(AuthException.class)
                .satisfies(ex -> assertThat(((AuthException) ex).getCode()).isEqualTo(AuthErrorCode.OTP_TOO_MANY_ATTEMPTS.code()));
    }

    @Test
    @DisplayName("Should persist and verify OTP via StringRedisTemplate when Valkey is available")
    @SuppressWarnings("unchecked")
    void shouldPersistAndVerifyOtpViaValkey() {
        StringRedisTemplate mockRedis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> mockOps = mock(ValueOperations.class);
        when(mockRedis.opsForValue()).thenReturn(mockOps);

        OtpService valkeyOtpService = new OtpService(mockRedis, null, 300, 3, 60, 5);
        valkeyOtpService.generateAndSendOtp("valkey@example.com", false);

        verify(mockOps).set(
                eq(ValkeyKeyConstant.otpCodeKey("valkey@example.com")),
                anyString(),
                any(Duration.class)
        );

        // Mock successful lookup
        when(mockOps.get(ValkeyKeyConstant.otpCodeKey("valkey@example.com"))).thenReturn("654321");
        when(mockOps.get(ValkeyKeyConstant.otpAttemptsKey("valkey@example.com"))).thenReturn("0");

        valkeyOtpService.verifyOtp("valkey@example.com", "654321");

        verify(mockRedis).delete(anyList());
    }

    private OtpService.OtpRecord getRecord(String email) {
        try {
            var field = OtpService.class.getDeclaredField("otpStore");
            field.setAccessible(true);
            @SuppressWarnings("unchecked")
            var map = (Map<String, OtpService.OtpRecord>) field.get(otpService);
            return map.get(email.toLowerCase());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
