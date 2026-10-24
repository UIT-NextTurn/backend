package com.nextturn.controller;

import com.nextturn.config.CorsConfig;
import com.nextturn.dto.TenantAuthRequest;
import com.nextturn.dto.TenantAuthResponse;
import com.nextturn.exception.AppException;
import com.nextturn.exception.GlobalExceptionHandler;
import com.nextturn.exception.auth.AuthErrorCode;
import com.nextturn.exception.auth.AuthException;
import com.nextturn.exception.system.SystemErrorCode;
import com.nextturn.infrastructure.multitenancy.TenantContextFilter;
import com.nextturn.infrastructure.security.TestSecurityConfig;
import com.nextturn.infrastructure.security.config.SecurityConfig;
import com.nextturn.infrastructure.security.keycloak.KeycloakJwtAuthenticationConverter;
import com.nextturn.infrastructure.security.keycloak.KeycloakJwtGrantedAuthoritiesConverter;
import com.nextturn.infrastructure.security.principal.CurrentUserArgumentResolver;
import com.nextturn.infrastructure.security.service.SecurityContextHelper;
import com.nextturn.service.TenantAuthService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import com.nextturn.config.ScalarConfig;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(TenantAuthController.class)
@EnableMethodSecurity(prePostEnabled = true)
@Import({
        ScalarConfig.class,
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
class TenantAuthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private TenantAuthService tenantAuthService;

    @Test
    @DisplayName("POST /api/v1/auth/tenant/verify should return 200 with staff routing details on valid tenant code")
    void shouldVerifyTenantSuccessfullyOnPost() throws Exception {
        TenantAuthResponse response = TenantAuthResponse.builder()
                .tenantId("tenant-uuid-123")
                .tenantCode("clinic_sai_gon")
                .tenantName("Phòng khám Đa khoa Sài Gòn")
                .status("ACTIVE")
                .organisationId("kc-org-001")
                .loginUrl("http://localhost:8180/realms/nextturn/protocol/openid-connect/auth?client_id=nextturn-backend&organization=kc-org-001")
                .allowedRoles(List.of("TENANT_ADMIN", "COUNTER_STAFF"))
                .build();

        when(tenantAuthService.authenticateTenant(any(TenantAuthRequest.class))).thenReturn(response);

        String requestJson = """
                {
                    "tenantCode": "clinic_sai_gon"
                }
                """;

        mockMvc.perform(post("/api/v1/auth/tenant/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.tenantId").value("tenant-uuid-123"))
                .andExpect(jsonPath("$.data.tenantCode").value("clinic_sai_gon"))
                .andExpect(jsonPath("$.data.tenantName").value("Phòng khám Đa khoa Sài Gòn"))
                .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.organisationId").value("kc-org-001"))
                .andExpect(jsonPath("$.data.allowedRoles[0]").value("TENANT_ADMIN"))
                .andExpect(jsonPath("$.data.allowedRoles[1]").value("COUNTER_STAFF"))
                .andExpect(jsonPath("$.data.loginUrl").exists());
    }

    @Test
    @DisplayName("GET /api/v1/auth/tenant/{tenantCode} should return 200 with staff routing details")
    void shouldVerifyTenantSuccessfullyOnGet() throws Exception {
        TenantAuthResponse response = TenantAuthResponse.builder()
                .tenantId("tenant-uuid-456")
                .tenantCode("spa_da_nang")
                .tenantName("Spa Đà Nẵng")
                .status("ACTIVE")
                .loginUrl("http://localhost:8180/realms/nextturn/protocol/openid-connect/auth")
                .allowedRoles(List.of("TENANT_ADMIN", "COUNTER_STAFF"))
                .build();

        when(tenantAuthService.authenticateTenant(any(TenantAuthRequest.class))).thenReturn(response);

        mockMvc.perform(get("/api/v1/auth/tenant/spa_da_nang"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.tenantCode").value("spa_da_nang"))
                .andExpect(jsonPath("$.data.tenantName").value("Spa Đà Nẵng"));
    }

    @Test
    @DisplayName("POST /api/v1/auth/tenant/verify should return 400 when tenantCode is blank")
    void shouldReturn400WhenTenantCodeIsBlank() throws Exception {
        String requestJson = """
                {
                    "tenantCode": "   "
                }
                """;

        mockMvc.perform(post("/api/v1/auth/tenant/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorCode").value(SystemErrorCode.VALIDATION_ERROR.code()))
                .andExpect(jsonPath("$.data.tenantCode").exists());
    }

    @Test
    @DisplayName("POST /api/v1/auth/tenant/verify should return 400 when tenantCode contains invalid characters")
    void shouldReturn400WhenTenantCodeHasInvalidCharacters() throws Exception {
        String requestJson = """
                {
                    "tenantCode": "clinic@sai_gon!#"
                }
                """;

        mockMvc.perform(post("/api/v1/auth/tenant/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorCode").value(SystemErrorCode.VALIDATION_ERROR.code()));
    }

    @Test
    @DisplayName("POST /api/v1/auth/tenant/verify should return 404 with AUTH_003 when tenant does not exist")
    void shouldReturn404WhenTenantNotFound() throws Exception {
        when(tenantAuthService.authenticateTenant(any(TenantAuthRequest.class)))
                .thenThrow(new AuthException(AuthErrorCode.TENANT_NOT_FOUND, "Tenant not found with code: non_existing"));

        String requestJson = """
                {
                    "tenantCode": "non_existing"
                }
                """;

        mockMvc.perform(post("/api/v1/auth/tenant/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorCode").value("AUTH_003"))
                .andExpect(jsonPath("$.message").value("Tenant not found with code: non_existing"));
    }

    @Test
    @DisplayName("POST /api/v1/auth/tenant/verify should return 403 with AUTH_004 when tenant is blocked")
    void shouldReturn403WhenTenantIsBlocked() throws Exception {
        when(tenantAuthService.authenticateTenant(any(TenantAuthRequest.class)))
                .thenThrow(new AuthException(AuthErrorCode.TENANT_INACTIVE, "Tenant facility is not active. Current status: BLOCKED"));

        String requestJson = """
                {
                    "tenantCode": "blocked_hospital"
                }
                """;

        mockMvc.perform(post("/api/v1/auth/tenant/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorCode").value("AUTH_004"))
                .andExpect(jsonPath("$.message").value("Tenant facility is not active. Current status: BLOCKED"));
    }

    @Test
    @DisplayName("GET /docs should be accessible and forward to /scalar.html")
    void shouldPermitDocsAndForwardToScalar() throws Exception {
        mockMvc.perform(get("/docs"))
                .andExpect(status().isOk())
                .andExpect(forwardedUrl("/scalar.html"));
    }

    @Test
    @DisplayName("GET /scalar should be accessible and forward to /scalar.html")
    void shouldPermitScalarAndForwardToScalar() throws Exception {
        mockMvc.perform(get("/scalar"))
                .andExpect(status().isOk())
                .andExpect(forwardedUrl("/scalar.html"));
    }

    @Test
    @DisplayName("GET /api/v1/auth/superadmin/login-url should return 200 with Super Admin login URL")
    void shouldReturnSuperAdminLoginUrl() throws Exception {
        TenantAuthResponse response = TenantAuthResponse.builder()
                .loginUrl("http://localhost:8180/realms/nextturn/protocol/openid-connect/auth?client_id=nextturn-backend&response_type=code")
                .allowedRoles(List.of("SUPERADMIN"))
                .status("ACTIVE")
                .build();

        when(tenantAuthService.resolveSuperAdminLogin()).thenReturn(response);

        mockMvc.perform(get("/api/v1/auth/superadmin/login-url"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.allowedRoles[0]").value("SUPERADMIN"))
                .andExpect(jsonPath("$.data.loginUrl").exists());
    }

    @Test
    @DisplayName("POST /api/v1/auth/token should exchange code and return 200 with AuthTokenResponse")
    void shouldExchangeCodeSuccessfully() throws Exception {
        com.nextturn.dto.AuthTokenResponse tokenResponse = com.nextturn.dto.AuthTokenResponse.builder()
                .accessToken("mock-access-token")
                .refreshToken("mock-refresh-token")
                .primaryRole("TENANT_ADMIN")
                .roles(List.of("TENANT_ADMIN"))
                .tenantCode("clinic_sai_gon")
                .targetDashboardUrl("/tenant/dashboard")
                .build();

        when(tenantAuthService.exchangeAuthorizationCode(any(com.nextturn.dto.TokenExchangeRequest.class)))
                .thenReturn(tokenResponse);

        String requestJson = """
                {
                    "code": "test-auth-code-123"
                }
                """;

        mockMvc.perform(post("/api/v1/auth/token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.accessToken").value("mock-access-token"))
                .andExpect(jsonPath("$.data.primaryRole").value("TENANT_ADMIN"))
                .andExpect(jsonPath("$.data.targetDashboardUrl").value("/tenant/dashboard"));
    }
}
