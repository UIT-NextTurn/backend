package com.nextturn.controller.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nextturn.config.CorsConfig;
import com.nextturn.config.RestClientConfig;
import com.nextturn.config.ScalarConfig;
import com.nextturn.dto.auth.AuthTokenResponse;
import com.nextturn.dto.auth.CustomerOtpResponse;
import com.nextturn.dto.auth.CustomerSendOtpRequest;
import com.nextturn.dto.auth.CustomerVerifyOtpRequest;
import com.nextturn.enums.UserRole;
import com.nextturn.exception.GlobalExceptionHandler;
import com.nextturn.exception.auth.AuthErrorCode;
import com.nextturn.exception.auth.AuthException;
import com.nextturn.infrastructure.multitenancy.TenantContextFilter;
import com.nextturn.infrastructure.security.TestSecurityConfig;
import com.nextturn.infrastructure.security.config.SecurityConfig;
import com.nextturn.infrastructure.security.keycloak.KeycloakJwtAuthenticationConverter;
import com.nextturn.infrastructure.security.keycloak.KeycloakJwtGrantedAuthoritiesConverter;
import com.nextturn.infrastructure.security.principal.CurrentUserArgumentResolver;
import com.nextturn.infrastructure.security.service.SecurityContextHelper;
import com.nextturn.service.auth.CustomerAuthService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(CustomerAuthController.class)
@EnableMethodSecurity(prePostEnabled = true)
@Import({
        ScalarConfig.class,
        RestClientConfig.class,
        SecurityConfig.class,
        TestSecurityConfig.class,
        CorsConfig.class,
        TenantContextFilter.class,
        KeycloakJwtAuthenticationConverter.class,
        KeycloakJwtGrantedAuthoritiesConverter.class,
        CurrentUserArgumentResolver.class,
        SecurityContextHelper.class,
        GlobalExceptionHandler.class
})
class CustomerAuthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @MockitoBean
    private CustomerAuthService customerAuthService;

    @Test
    @DisplayName("POST /api/v1/auth/customer/otp/send - Should return 200 and dispatch OTP")
    void shouldSendOtpSuccessfully() throws Exception {
        CustomerOtpResponse mockResponse = CustomerOtpResponse.builder()
                .email("patient@example.com")
                .expiresInSeconds(300)
                .message("Verification code has been dispatched")
                .existingUser(true)
                .build();

        when(customerAuthService.sendOtp(any())).thenReturn(mockResponse);

        CustomerSendOtpRequest request = CustomerSendOtpRequest.builder()
                .email("patient@example.com")
                .build();

        mockMvc.perform(post("/api/v1/auth/customer/otp/send")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(200))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.email").value("patient@example.com"))
                .andExpect(jsonPath("$.data.expiresInSeconds").value(300))
                .andExpect(jsonPath("$.data.existingUser").value(true));
    }

    @Test
    @DisplayName("POST /api/v1/auth/customer/otp/send - Should return 400 when email format is invalid")
    void shouldReturn400WhenEmailIsInvalid() throws Exception {
        CustomerSendOtpRequest request = CustomerSendOtpRequest.builder()
                .email("not-an-email")
                .build();

        mockMvc.perform(post("/api/v1/auth/customer/otp/send")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /api/v1/auth/customer/otp/verify - Should return 200 and return END_USER tokens")
    void shouldVerifyOtpSuccessfully() throws Exception {
        AuthTokenResponse mockResponse = AuthTokenResponse.builder()
                .accessToken("mock-customer-access-token")
                .refreshToken("mock-customer-refresh-token")
                .userId("usr-12345")
                .email("patient@example.com")
                .roles(List.of(UserRole.END_USER_VALUE))
                .primaryRole(UserRole.END_USER_VALUE)
                .targetDashboardUrl("/customer/home")
                .build();

        when(customerAuthService.verifyOtp(any())).thenReturn(mockResponse);

        CustomerVerifyOtpRequest request = CustomerVerifyOtpRequest.builder()
                .email("patient@example.com")
                .otp("123456")
                .build();

        mockMvc.perform(post("/api/v1/auth/customer/otp/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(200))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.accessToken").value("mock-customer-access-token"))
                .andExpect(jsonPath("$.data.userId").value("usr-12345"))
                .andExpect(jsonPath("$.data.primaryRole").value("END_USER"));
    }

    @Test
    @DisplayName("POST /api/v1/auth/customer/otp/verify - Should return 400 when OTP is invalid")
    void shouldReturn400WhenOtpIsInvalid() throws Exception {
        when(customerAuthService.verifyOtp(any()))
                .thenThrow(new AuthException(AuthErrorCode.INVALID_OTP, "Invalid verification code"));

        CustomerVerifyOtpRequest request = CustomerVerifyOtpRequest.builder()
                .email("patient@example.com")
                .otp("999999")
                .build();

        mockMvc.perform(post("/api/v1/auth/customer/otp/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("AUTH_013"));
    }
}
