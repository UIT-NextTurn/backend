package com.nextturn.controller.tenant;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nextturn.config.CorsConfig;
import com.nextturn.config.ScalarConfig;
import com.nextturn.dto.tenant.TenantRegistrationRequest;
import com.nextturn.dto.tenant.TenantResponse;
import com.nextturn.enums.TenantStatus;
import com.nextturn.exception.GlobalExceptionHandler;
import com.nextturn.exception.tenant.TenantErrorCode;
import com.nextturn.exception.tenant.TenantException;
import com.nextturn.infrastructure.multitenancy.TenantContextFilter;
import com.nextturn.infrastructure.security.TestSecurityConfig;
import com.nextturn.infrastructure.security.config.SecurityConfig;
import com.nextturn.infrastructure.security.keycloak.KeycloakJwtAuthenticationConverter;
import com.nextturn.infrastructure.security.keycloak.KeycloakJwtGrantedAuthoritiesConverter;
import com.nextturn.infrastructure.security.principal.CurrentUserArgumentResolver;
import com.nextturn.infrastructure.security.service.SecurityContextHelper;
import com.nextturn.service.tenant.TenantService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(TenantPublicRegistrationController.class)
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
class TenantPublicRegistrationControllerTest {

    @Autowired
    private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @MockitoBean
    private TenantService tenantService;

    @Test
    @DisplayName("POST /api/v1/public/tenants/register should succeed with 201 Created without authentication")
    void registerTenant_whenValidRequest_returns201Created() throws Exception {
        TenantRegistrationRequest request = TenantRegistrationRequest.builder()
                .tenantCode("pk-an-phuoc")
                .tenantName("Phòng khám Đa khoa An Phước")
                .adminEmail("director@anphuoc.vn")
                .phoneNumber("0912345678")
                .initialPassword("Admin@Secure123")
                .build();

        TenantResponse response = TenantResponse.builder()
                .id("tenant-uuid-anphuoc")
                .tenantCode("pk-an-phuoc")
                .tenantName("Phòng khám Đa khoa An Phước")
                .status(TenantStatus.PENDING_APPROVAL)
                .adminEmail("director@anphuoc.vn")
                .phoneNumber("0912345678")
                .build();

        when(tenantService.registerTenant(any(TenantRegistrationRequest.class))).thenReturn(response);

        mockMvc.perform(post("/api/v1/public/tenants/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.tenantCode").value("pk-an-phuoc"))
                .andExpect(jsonPath("$.data.status").value("PENDING_APPROVAL"))
                .andExpect(jsonPath("$.data.adminEmail").value("director@anphuoc.vn"));

        verify(tenantService).registerTenant(any(TenantRegistrationRequest.class));
    }

    @Test
    @DisplayName("POST /api/v1/public/tenants/register should return 400 when email has '+' character")
    void registerTenant_whenEmailHasPlus_returns400BadRequest() throws Exception {
        TenantRegistrationRequest request = TenantRegistrationRequest.builder()
                .tenantCode("pk-an-phuoc")
                .tenantName("Phòng khám Đa khoa An Phước")
                .adminEmail("director+spam@anphuoc.vn")
                .phoneNumber("0912345678")
                .initialPassword("Admin@Secure123")
                .build();

        mockMvc.perform(post("/api/v1/public/tenants/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /api/v1/public/tenants/register should return 400 when missing mandatory fields")
    void registerTenant_whenMissingRequiredFields_returns400BadRequest() throws Exception {
        TenantRegistrationRequest request = TenantRegistrationRequest.builder()
                .tenantCode("")
                .tenantName("")
                .adminEmail("director@anphuoc.vn")
                .build();

        mockMvc.perform(post("/api/v1/public/tenants/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /api/v1/public/tenants/register should return 409 Conflict when code or email already exists")
    void registerTenant_whenTenantAlreadyExists_returns409Conflict() throws Exception {
        TenantRegistrationRequest request = TenantRegistrationRequest.builder()
                .tenantCode("pk-an-phuoc")
                .tenantName("Phòng khám Đa khoa An Phước")
                .adminEmail("director@anphuoc.vn")
                .phoneNumber("0912345678")
                .initialPassword("Admin@Secure123")
                .build();

        when(tenantService.registerTenant(any(TenantRegistrationRequest.class)))
                .thenThrow(new TenantException(TenantErrorCode.TENANT_OR_EMAIL_ALREADY_EXISTS));

        mockMvc.perform(post("/api/v1/public/tenants/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorCode").value("TENANT_010"));
    }
}
