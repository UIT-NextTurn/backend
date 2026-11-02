package com.nextturn.controller.tenant;

import com.nextturn.config.CorsConfig;
import com.nextturn.config.ScalarConfig;
import com.nextturn.dto.tenant.CreateTenantRequest;
import com.nextturn.dto.tenant.RejectTenantRequest;
import com.nextturn.dto.tenant.TenantMonitoringResponse;
import com.nextturn.dto.tenant.TenantQuotaDto;
import com.nextturn.dto.tenant.TenantQuotaResponse;
import com.nextturn.dto.tenant.TenantResponse;
import com.nextturn.dto.tenant.UpdateTenantRequest;
import com.nextturn.dto.tenant.UpdateTenantStatusRequest;
import com.nextturn.enums.TenantStatus;
import com.nextturn.exception.GlobalExceptionHandler;
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
import org.springframework.data.domain.PageImpl;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(TenantManagementController.class)
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
class TenantManagementControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private KeycloakJwtAuthenticationConverter authenticationConverter;

    @MockitoBean
    private TenantService tenantService;

    @Test
    @DisplayName("POST /api/v1/central/tenants should allow SUPERADMIN and return 201 Created (REQ-TNT-01)")
    void shouldCreateTenantWhenSuperAdmin() throws Exception {
        TenantResponse response = TenantResponse.builder()
                .id("tenant-uuid-1")
                .tenantCode("bv-tam-duc")
                .tenantName("Bệnh viện Đa khoa Tâm Đức")
                .adminEmail("admin@tamduc.vn")
                .phoneNumber("0987654321")
                .status(TenantStatus.ACTIVE)
                .organisationId("org-tam-duc")
                .widgetApiKey("nt_pub_123456")
                .quotas(TenantQuotaResponse.builder()
                        .maxStaffs(10)
                        .dailyTicketQuota(300)
                        .maxServices(20)
                        .maxAppointments(500)
                        .currentActiveStaff(1L)
                        .build())
                .createdAt(LocalDateTime.now())
                .build();

        when(tenantService.createTenant(any(CreateTenantRequest.class))).thenReturn(response);

        AbstractAuthenticationToken superAdminAuth = authenticationConverter.convert(
                createMockJwt("SUPERADMIN", "sa@nextturn.internal", null)
        );

        String requestJson = """
                {
                    "tenantName": "Bệnh viện Đa khoa Tâm Đức",
                    "tenantCode": "bv-tam-duc",
                    "adminEmail": "admin@tamduc.vn",
                    "adminFullName": "Nguyễn Văn Quản Trị",
                    "phoneNumber": "0987654321",
                    "initialPassword": "AdminPassword123!@#",
                    "quotas": {
                        "maxStaffs": 10,
                        "dailyTicketQuota": 300,
                        "maxServices": 20,
                        "maxAppointments": 500
                    }
                }
                """;

        mockMvc.perform(post("/api/v1/central/tenants")
                        .with(authentication(superAdminAuth))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.status").value(201))
                .andExpect(jsonPath("$.data.tenantCode").value("bv-tam-duc"))
                .andExpect(jsonPath("$.data.tenantName").value("Bệnh viện Đa khoa Tâm Đức"))
                .andExpect(jsonPath("$.data.quotas.maxStaffs").value(10));
    }

    @Test
    @DisplayName("POST /api/v1/central/tenants should reject unauthenticated requests with 401")
    void shouldRejectUnauthenticatedTenantCreation() throws Exception {
        String requestJson = """
                {
                    "tenantName": "Bệnh viện Đa khoa Tâm Đức",
                    "tenantCode": "bv-tam-duc",
                    "adminEmail": "admin@tamduc.vn",
                    "phoneNumber": "0987654321"
                }
                """;

        mockMvc.perform(post("/api/v1/central/tenants")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("POST /api/v1/central/tenants should deny non-Superadmin roles with 403")
    void shouldDenyTenantCreationForTenantAdmin() throws Exception {
        AbstractAuthenticationToken tenantAdminAuth = authenticationConverter.convert(
                createMockJwt("TENANT_ADMIN", "admin@clinic.vn", "bv-tam-duc")
        );

        String requestJson = """
                {
                    "tenantName": "Bệnh viện Đa khoa Tâm Đức",
                    "tenantCode": "bv-tam-duc",
                    "adminEmail": "admin@tamduc.vn",
                    "phoneNumber": "0987654321"
                }
                """;

        mockMvc.perform(post("/api/v1/central/tenants")
                        .with(authentication(tenantAdminAuth))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("POST /api/v1/central/tenants should return 400 when validation constraints fail")
    void shouldReturnBadRequestOnInvalidTenantCreationInputs() throws Exception {
        AbstractAuthenticationToken superAdminAuth = authenticationConverter.convert(
                createMockJwt("SUPERADMIN", "sa@nextturn.internal", null)
        );

        // Invalid: uppercase in tenantCode, invalid email format, phone not 10 digits
        String invalidJson = """
                {
                    "tenantName": "<script>alert('hack')</script>",
                    "tenantCode": "INVALID_CODE",
                    "adminEmail": "not-an-email",
                    "phoneNumber": "123"
                }
                """;

        mockMvc.perform(post("/api/v1/central/tenants")
                        .with(authentication(superAdminAuth))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidJson))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    @DisplayName("POST /api/v1/central/tenants should reject email with '+' or consecutive dots '..'")
    void shouldRejectEmailWithPlusOrConsecutiveDots() throws Exception {
        AbstractAuthenticationToken superAdminAuth = authenticationConverter.convert(
                createMockJwt("SUPERADMIN", "sa@nextturn.internal", null)
        );

        String emailWithPlusJson = """
                {
                    "tenantName": "Bệnh viện Đa khoa Tâm Đức",
                    "tenantCode": "bv-tam-duc",
                    "adminEmail": "admin+subaddress@tamduc.vn",
                    "phoneNumber": "0987654321"
                }
                """;

        mockMvc.perform(post("/api/v1/central/tenants")
                        .with(authentication(superAdminAuth))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(emailWithPlusJson))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));

        String emailWithConsecutiveDotsJson = """
                {
                    "tenantName": "Bệnh viện Đa khoa Tâm Đức",
                    "tenantCode": "bv-tam-duc",
                    "adminEmail": "admin..test@tamduc.vn",
                    "phoneNumber": "0987654321"
                }
                """;

        mockMvc.perform(post("/api/v1/central/tenants")
                        .with(authentication(superAdminAuth))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(emailWithConsecutiveDotsJson))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    @DisplayName("PUT /api/v1/central/tenants/{tenantId} should allow SUPERADMIN and return 200 OK (REQ-TNT-02)")
    void shouldUpdateTenantWhenSuperAdmin() throws Exception {
        TenantResponse response = TenantResponse.builder()
                .id("tenant-uuid-1")
                .tenantCode("bv-tam-duc")
                .tenantName("Bệnh viện Đa khoa Tâm Đức Mở Rộng")
                .adminEmail("admin@tamduc.vn")
                .phoneNumber("0912345678")
                .status(TenantStatus.ACTIVE)
                .quotas(TenantQuotaResponse.builder()
                        .maxStaffs(15)
                        .dailyTicketQuota(1000)
                        .maxServices(50)
                        .maxAppointments(-1)
                        .currentActiveStaff(4L)
                        .build())
                .build();

        when(tenantService.updateTenant(eq("tenant-uuid-1"), any(UpdateTenantRequest.class))).thenReturn(response);

        AbstractAuthenticationToken superAdminAuth = authenticationConverter.convert(
                createMockJwt("SUPERADMIN", "sa@nextturn.internal", null)
        );

        String requestJson = """
                {
                    "tenantName": "Bệnh viện Đa khoa Tâm Đức Mở Rộng",
                    "phoneNumber": "0912345678",
                    "maxStaffs": 15,
                    "dailyTicketQuota": 1000,
                    "maxServices": 50,
                    "maxAppointments": -1
                }
                """;

        mockMvc.perform(put("/api/v1/central/tenants/tenant-uuid-1")
                        .with(authentication(superAdminAuth))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.tenantName").value("Bệnh viện Đa khoa Tâm Đức Mở Rộng"))
                .andExpect(jsonPath("$.data.quotas.maxStaffs").value(15))
                .andExpect(jsonPath("$.data.quotas.maxAppointments").value(-1));
    }

    @Test
    @DisplayName("PUT /api/v1/central/tenants/{tenantId} should deny TENANT_ADMIN and only accept SUPERADMIN")
    void shouldDenyTenantUpdateForTenantAdmin() throws Exception {
        AbstractAuthenticationToken tenantAdminAuth = authenticationConverter.convert(
                createMockJwt("TENANT_ADMIN", "admin@clinic-a.vn", "clinic-a")
        );

        String requestJson = """
                {
                    "tenantName": "New Name",
                    "maxStaffs": 10
                }
                """;

        mockMvc.perform(put("/api/v1/central/tenants/clinic-a")
                        .with(authentication(tenantAdminAuth))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("GET /api/v1/central/tenants/{tenantId} should deny TENANT_ADMIN and only accept SUPERADMIN")
    void shouldDenyGetTenantForTenantAdmin() throws Exception {
        AbstractAuthenticationToken tenantAdminAuth = authenticationConverter.convert(
                createMockJwt("TENANT_ADMIN", "admin@clinic-a.vn", "clinic-a")
        );

        mockMvc.perform(get("/api/v1/central/tenants/clinic-a")
                        .with(authentication(tenantAdminAuth)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("GET /api/v1/central/tenants/{tenantId} should return tenant details for SUPERADMIN")
    void shouldGetTenantWhenSuperAdmin() throws Exception {
        TenantResponse response = TenantResponse.builder()
                .id("tenant-uuid-1")
                .tenantCode("bv-tam-duc")
                .tenantName("Bệnh viện Đa khoa Tâm Đức")
                .status(TenantStatus.ACTIVE)
                .build();

        when(tenantService.getTenant("tenant-uuid-1")).thenReturn(response);

        AbstractAuthenticationToken superAdminAuth = authenticationConverter.convert(
                createMockJwt("SUPERADMIN", "sa@nextturn.internal", null)
        );

        mockMvc.perform(get("/api/v1/central/tenants/tenant-uuid-1")
                        .with(authentication(superAdminAuth)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.tenantCode").value("bv-tam-duc"));
    }

    @Test
    @DisplayName("GET /api/v1/central/tenants/{tenantId}/quotas should return quotas")
    void shouldGetTenantQuota() throws Exception {
        TenantQuotaResponse response = TenantQuotaResponse.builder()
                .tenantId("tenant-uuid-1")
                .maxStaffs(10)
                .dailyTicketQuota(300)
                .currentActiveStaff(2L)
                .build();

        when(tenantService.getTenantQuota("tenant-uuid-1")).thenReturn(response);

        AbstractAuthenticationToken superAdminAuth = authenticationConverter.convert(
                createMockJwt("SUPERADMIN", "sa@nextturn.internal", null)
        );

        mockMvc.perform(get("/api/v1/central/tenants/tenant-uuid-1/quotas")
                        .with(authentication(superAdminAuth)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.maxStaffs").value(10))
                .andExpect(jsonPath("$.data.currentActiveStaff").value(2));
    }

    @Test
    @DisplayName("POST /api/v1/central/tenants/{tenantId}/suspend should suspend tenant when SUPERADMIN (REQ-TNT-03)")
    void shouldSuspendTenantWhenSuperAdmin() throws Exception {
        TenantResponse response = TenantResponse.builder()
                .id("tenant-uuid-1")
                .tenantCode("bv-tam-duc")
                .status(TenantStatus.SUSPENDED)
                .build();

        when(tenantService.suspendTenant("tenant-uuid-1")).thenReturn(response);

        AbstractAuthenticationToken superAdminAuth = authenticationConverter.convert(
                createMockJwt("SUPERADMIN", "sa@nextturn.internal", null)
        );

        mockMvc.perform(post("/api/v1/central/tenants/tenant-uuid-1/suspend")
                        .with(authentication(superAdminAuth)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.status").value("SUSPENDED"));
    }

    @Test
    @DisplayName("POST /api/v1/central/tenants/{tenantId}/activate should reactivate tenant when SUPERADMIN (REQ-TNT-03)")
    void shouldActivateTenantWhenSuperAdmin() throws Exception {
        TenantResponse response = TenantResponse.builder()
                .id("tenant-uuid-1")
                .tenantCode("bv-tam-duc")
                .status(TenantStatus.ACTIVE)
                .build();

        when(tenantService.activateTenant("tenant-uuid-1")).thenReturn(response);

        AbstractAuthenticationToken superAdminAuth = authenticationConverter.convert(
                createMockJwt("SUPERADMIN", "sa@nextturn.internal", null)
        );

        mockMvc.perform(post("/api/v1/central/tenants/tenant-uuid-1/activate")
                        .with(authentication(superAdminAuth)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.status").value("ACTIVE"));
    }

    @Test
    @DisplayName("PATCH /api/v1/central/tenants/{tenantId}/status should update status when SUPERADMIN (REQ-TNT-03)")
    void shouldUpdateStatusWhenSuperAdmin() throws Exception {
        TenantResponse response = TenantResponse.builder()
                .id("tenant-uuid-1")
                .tenantCode("bv-tam-duc")
                .status(TenantStatus.SUSPENDED)
                .build();

        when(tenantService.updateTenantStatus(eq("tenant-uuid-1"), any(UpdateTenantStatusRequest.class)))
                .thenReturn(response);

        AbstractAuthenticationToken superAdminAuth = authenticationConverter.convert(
                createMockJwt("SUPERADMIN", "sa@nextturn.internal", null)
        );

        mockMvc.perform(patch("/api/v1/central/tenants/tenant-uuid-1/status")
                        .with(authentication(superAdminAuth))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"SUSPENDED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.status").value("SUSPENDED"));
    }

    @Test
    @DisplayName("DELETE /api/v1/central/tenants/{tenantId} should invoke service delete (REQ-TNT-03)")
    void shouldDeleteTenantWhenSuperAdmin() throws Exception {
        AbstractAuthenticationToken superAdminAuth = authenticationConverter.convert(
                createMockJwt("SUPERADMIN", "sa@nextturn.internal", null)
        );

        mockMvc.perform(delete("/api/v1/central/tenants/tenant-uuid-1")
                        .with(authentication(superAdminAuth)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        verify(tenantService).deleteTenant("tenant-uuid-1");
    }

    @Test
    @DisplayName("GET /api/v1/central/tenants should search with pagination and metrics (REQ-TNT-04)")
    void shouldSearchTenantsWhenSuperAdmin() throws Exception {
        TenantMonitoringResponse item = TenantMonitoringResponse.builder()
                .id("tenant-uuid-1")
                .tenantCode("bv-tam-duc")
                .tenantName("Bệnh viện Đa khoa Tâm Đức")
                .status(TenantStatus.ACTIVE)
                .activeStaffCount(8L)
                .todayTicketsCount(25L)
                .build();

        when(tenantService.searchTenants(any(), any(), anyInt(), anyInt(), anyString(), anyString()))
                .thenReturn(new PageImpl<>(List.of(item)));

        AbstractAuthenticationToken superAdminAuth = authenticationConverter.convert(
                createMockJwt("SUPERADMIN", "sa@nextturn.internal", null)
        );

        mockMvc.perform(get("/api/v1/central/tenants?keyword=tam-duc&status=ACTIVE&page=0&limit=10")
                        .with(authentication(superAdminAuth)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data[0].tenantCode").value("bv-tam-duc"))
                .andExpect(jsonPath("$.data[0].activeStaffCount").value(8))
                .andExpect(jsonPath("$.data[0].todayTicketsCount").value(25))
                .andExpect(jsonPath("$.pagination.totalElements").value(1));
    }

    @Test
    @DisplayName("POST /api/v1/central/tenants/{id}/approve should approve pending tenant (REQ-TNT-06)")
    void shouldApproveTenantWhenSuperAdmin() throws Exception {
        TenantResponse approvedTenant = TenantResponse.builder()
                .id("tenant-uuid-1")
                .tenantCode("bv-tam-duc")
                .tenantName("Bệnh viện Đa khoa Tâm Đức")
                .status(TenantStatus.ACTIVE)
                .build();

        when(tenantService.approveTenantRegistration("tenant-uuid-1")).thenReturn(approvedTenant);

        AbstractAuthenticationToken superAdminAuth = authenticationConverter.convert(
                createMockJwt("SUPERADMIN", "sa@nextturn.internal", null)
        );

        mockMvc.perform(post("/api/v1/central/tenants/tenant-uuid-1/approve")
                        .with(authentication(superAdminAuth)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.tenantCode").value("bv-tam-duc"))
                .andExpect(jsonPath("$.data.status").value("ACTIVE"));

        verify(tenantService).approveTenantRegistration("tenant-uuid-1");
    }

    @Test
    @DisplayName("POST /api/v1/central/tenants/{id}/approve should deny non-superadmin (REQ-TNT-06)")
    void shouldDenyApproveTenantWhenNotSuperAdmin() throws Exception {
        AbstractAuthenticationToken staffAuth = authenticationConverter.convert(
                createMockJwt("TENANT_ADMIN", "admin@tamduc.vn", "bv-tam-duc")
        );

        mockMvc.perform(post("/api/v1/central/tenants/tenant-uuid-1/approve")
                        .with(authentication(staffAuth)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("POST /api/v1/central/tenants/{id}/reject should reject pending tenant with mandatory reason (REQ-TNT-06)")
    void shouldRejectTenantWhenSuperAdmin() throws Exception {
        TenantResponse rejectedTenant = TenantResponse.builder()
                .id("tenant-uuid-1")
                .tenantCode("bv-tam-duc")
                .tenantName("Bệnh viện Đa khoa Tâm Đức")
                .status(TenantStatus.REJECTED)
                .rejectionReason("Thông tin giấy phép kinh doanh không khớp")
                .build();

        when(tenantService.rejectTenantRegistration(eq("tenant-uuid-1"), any(RejectTenantRequest.class)))
                .thenReturn(rejectedTenant);

        AbstractAuthenticationToken superAdminAuth = authenticationConverter.convert(
                createMockJwt("SUPERADMIN", "sa@nextturn.internal", null)
        );

        mockMvc.perform(post("/api/v1/central/tenants/tenant-uuid-1/reject")
                        .with(authentication(superAdminAuth))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\": \"Thông tin giấy phép kinh doanh không khớp\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.tenantCode").value("bv-tam-duc"))
                .andExpect(jsonPath("$.data.status").value("REJECTED"))
                .andExpect(jsonPath("$.data.rejectionReason").value("Thông tin giấy phép kinh doanh không khớp"));

        verify(tenantService).rejectTenantRegistration(eq("tenant-uuid-1"), any(RejectTenantRequest.class));
    }

    @Test
    @DisplayName("POST /api/v1/central/tenants/{id}/reject should return 400 when rejectionReason is blank (REQ-TNT-06)")
    void shouldFailRejectTenantWhenReasonIsBlank() throws Exception {
        AbstractAuthenticationToken superAdminAuth = authenticationConverter.convert(
                createMockJwt("SUPERADMIN", "sa@nextturn.internal", null)
        );

        mockMvc.perform(post("/api/v1/central/tenants/tenant-uuid-1/reject")
                        .with(authentication(superAdminAuth))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\": \"   \"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /api/v1/central/tenants/{id}/reject should deny non-superadmin (REQ-TNT-06)")
    void shouldDenyRejectTenantWhenNotSuperAdmin() throws Exception {
        AbstractAuthenticationToken staffAuth = authenticationConverter.convert(
                createMockJwt("TENANT_ADMIN", "admin@tamduc.vn", "bv-tam-duc")
        );

        mockMvc.perform(post("/api/v1/central/tenants/tenant-uuid-1/reject")
                        .with(authentication(staffAuth))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\": \"Lý do bất kỳ\"}"))
                .andExpect(status().isForbidden());
    }

    private Jwt createMockJwt(String role, String email, String tenantCode) {
        var jwtBuilder = Jwt.withTokenValue("mock-jwt-token")
                .header("alg", "RS256")
                .issuer("http://ryan-a1:8180/realms/nextturn")
                .subject("user-uuid-xyz")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300))
                .claim("azp", "nextturn-backend")
                .claim("email", email)
                .claim("scope", "openid organization")
                .claim("resource_access", Map.of(
                        "nextturn-backend", Map.of(
                                "roles", List.of(role)
                        )
                ));

        if (tenantCode != null) {
            jwtBuilder.claim("tenant_code", tenantCode);
        }

        return jwtBuilder.build();
    }
}

