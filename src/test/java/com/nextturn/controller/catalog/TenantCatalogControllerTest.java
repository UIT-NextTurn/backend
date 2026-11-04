package com.nextturn.controller.catalog;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nextturn.config.CorsConfig;
import com.nextturn.config.ScalarConfig;
import com.nextturn.dto.service.CreateServiceRequest;
import com.nextturn.dto.service.ServiceResponse;
import com.nextturn.dto.service.UpdateServiceRequest;
import com.nextturn.enums.ServiceStatus;
import com.nextturn.exception.GlobalExceptionHandler;
import com.nextturn.exception.service.ServiceErrorCode;
import com.nextturn.exception.service.ServiceException;
import com.nextturn.infrastructure.multitenancy.TenantContextFilter;
import com.nextturn.infrastructure.security.TestSecurityConfig;
import com.nextturn.infrastructure.security.config.SecurityConfig;
import com.nextturn.infrastructure.security.keycloak.KeycloakJwtAuthenticationConverter;
import com.nextturn.infrastructure.security.keycloak.KeycloakJwtGrantedAuthoritiesConverter;
import com.nextturn.infrastructure.security.principal.CurrentUserArgumentResolver;
import com.nextturn.infrastructure.security.service.SecurityContextHelper;
import com.nextturn.service.service.TenantServiceService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(TenantCatalogController.class)
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
class TenantCatalogControllerTest {

    @Autowired
    private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Autowired
    private KeycloakJwtAuthenticationConverter authenticationConverter;

    @MockitoBean
    private TenantServiceService catalogService;

    @Test
    @DisplayName("POST /api/v1/services: should create service and return 201 Created for TENANT_ADMIN")
    void shouldCreateServiceWhenTenantAdmin() throws Exception {
        CreateServiceRequest request = CreateServiceRequest.builder()
                .serviceName("Tư Vấn Trực Tiếp")
                .standardDuration(20)
                .price(new BigDecimal("250000.00"))
                .build();

        ServiceResponse response = ServiceResponse.builder()
                .id("svc-uuid-001")
                .serviceCode("SVC-0001")
                .serviceName("Tư Vấn Trực Tiếp")
                .standardDuration(20)
                .price(new BigDecimal("250000.00"))
                .status(ServiceStatus.ACTIVE)
                .createdAt(LocalDateTime.now())
                .build();

        when(catalogService.createService(any(CreateServiceRequest.class))).thenReturn(response);

        AbstractAuthenticationToken adminAuth = authenticationConverter.convert(
                createMockJwt("TENANT_ADMIN", "admin@tenant.com", "tenant-alpha")
        );

        mockMvc.perform(post("/api/v1/services")
                        .with(authentication(adminAuth))
                        .header("X-Tenant-Code", "tenant-alpha")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value(201))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.serviceCode").value("SVC-0001"))
                .andExpect(jsonPath("$.data.serviceName").value("Tư Vấn Trực Tiếp"))
                .andExpect(jsonPath("$.data.standardDuration").value(20));
    }

    @Test
    @DisplayName("POST /api/v1/services: should return 400 Bad Request when service name contains dangerous characters (XSS/SQLi)")
    void shouldReturnBadRequest_whenDangerousCharactersInName() throws Exception {
        CreateServiceRequest xssRequest = CreateServiceRequest.builder()
                .serviceName("<script>alert('xss')</script>")
                .standardDuration(15)
                .price(new BigDecimal("100000.00"))
                .build();

        AbstractAuthenticationToken adminAuth = authenticationConverter.convert(
                createMockJwt("TENANT_ADMIN", "admin@tenant.com", "tenant-alpha")
        );

        mockMvc.perform(post("/api/v1/services")
                        .with(authentication(adminAuth))
                        .header("X-Tenant-Code", "tenant-alpha")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(xssRequest)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorCode").value("SYS_002"));
    }

    @Test
    @DisplayName("POST /api/v1/services: should return 400 Bad Request when standard duration is 0 or negative")
    void shouldReturnBadRequest_whenDurationIsZeroOrNegative() throws Exception {
        CreateServiceRequest invalidDurationRequest = CreateServiceRequest.builder()
                .serviceName("Hỗ Trợ Kỹ Thuật")
                .standardDuration(0)
                .price(new BigDecimal("100000.00"))
                .build();

        AbstractAuthenticationToken adminAuth = authenticationConverter.convert(
                createMockJwt("TENANT_ADMIN", "admin@tenant.com", "tenant-alpha")
        );

        mockMvc.perform(post("/api/v1/services")
                        .with(authentication(adminAuth))
                        .header("X-Tenant-Code", "tenant-alpha")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalidDurationRequest)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    @DisplayName("POST /api/v1/services: should return 409 Conflict when service name already exists in tenant catalog")
    void shouldReturnConflict_whenServiceNameAlreadyExists() throws Exception {
        CreateServiceRequest request = CreateServiceRequest.builder()
                .serviceName("Tư Vấn Khách Hàng")
                .standardDuration(15)
                .price(new BigDecimal("150000.00"))
                .build();

        when(catalogService.createService(any(CreateServiceRequest.class)))
                .thenThrow(new ServiceException(
                        ServiceErrorCode.SERVICE_NAME_ALREADY_EXISTS,
                        "Tên dịch vụ đã tồn tại trong danh mục này. Vui lòng nhập tên khác."
                ));

        AbstractAuthenticationToken adminAuth = authenticationConverter.convert(
                createMockJwt("TENANT_ADMIN", "admin@tenant.com", "tenant-alpha")
        );

        mockMvc.perform(post("/api/v1/services")
                        .with(authentication(adminAuth))
                        .header("X-Tenant-Code", "tenant-alpha")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.errorCode").value("SVC_001"))
                .andExpect(jsonPath("$.message").value("Tên dịch vụ đã tồn tại trong danh mục này. Vui lòng nhập tên khác."));
    }

    @Test
    @DisplayName("PUT /api/v1/services/{serviceId}: should update service details and status, returning 200 OK")
    void shouldUpdateServiceWhenTenantAdmin() throws Exception {
        UpdateServiceRequest request = UpdateServiceRequest.builder()
                .serviceName("Tư Vấn Nâng Cao")
                .standardDuration(30)
                .price(new BigDecimal("500000.00"))
                .status(ServiceStatus.INACTIVE)
                .build();

        ServiceResponse response = ServiceResponse.builder()
                .id("svc-uuid-001")
                .serviceCode("SVC-0001")
                .serviceName("Tư Vấn Nâng Cao")
                .standardDuration(30)
                .price(new BigDecimal("500000.00"))
                .status(ServiceStatus.INACTIVE)
                .build();

        when(catalogService.updateService(eq("svc-uuid-001"), any(UpdateServiceRequest.class)))
                .thenReturn(response);

        AbstractAuthenticationToken adminAuth = authenticationConverter.convert(
                createMockJwt("TENANT_ADMIN", "admin@tenant.com", "tenant-alpha")
        );

        mockMvc.perform(put("/api/v1/services/svc-uuid-001")
                        .with(authentication(adminAuth))
                        .header("X-Tenant-Code", "tenant-alpha")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(200))
                .andExpect(jsonPath("$.data.standardDuration").value(30))
                .andExpect(jsonPath("$.data.status").value("INACTIVE"));
    }

    @Test
    @DisplayName("GET /api/v1/services/{serviceId}: should return service details")
    void shouldGetServiceById() throws Exception {
        ServiceResponse response = ServiceResponse.builder()
                .id("svc-uuid-001")
                .serviceCode("SVC-0001")
                .serviceName("Tư Vấn Trực Tiếp")
                .standardDuration(20)
                .price(new BigDecimal("250000.00"))
                .status(ServiceStatus.ACTIVE)
                .build();

        when(catalogService.getServiceById("svc-uuid-001")).thenReturn(response);

        AbstractAuthenticationToken adminAuth = authenticationConverter.convert(
                createMockJwt("TENANT_ADMIN", "admin@tenant.com", "tenant-alpha")
        );

        mockMvc.perform(get("/api/v1/services/svc-uuid-001")
                        .with(authentication(adminAuth))
                        .header("X-Tenant-Code", "tenant-alpha"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.serviceCode").value("SVC-0001"));
    }

    @Test
    @DisplayName("GET /api/v1/services: should return paginated service list with keyword and status")
    void shouldSearchServiceList() throws Exception {
        ServiceResponse response = ServiceResponse.builder()
                .id("svc-uuid-001")
                .serviceCode("SVC-0001")
                .serviceName("Tư Vấn Trực Tiếp")
                .standardDuration(20)
                .price(new BigDecimal("250000.00"))
                .status(ServiceStatus.ACTIVE)
                .build();

        when(catalogService.searchServices(eq("tư vấn"), eq(ServiceStatus.ACTIVE), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(response)));

        AbstractAuthenticationToken adminAuth = authenticationConverter.convert(
                createMockJwt("TENANT_ADMIN", "admin@tenant.com", "tenant-alpha")
        );

        mockMvc.perform(get("/api/v1/services")
                        .with(authentication(adminAuth))
                        .header("X-Tenant-Code", "tenant-alpha")
                        .param("keyword", "tư vấn")
                        .param("status", "ACTIVE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].serviceCode").value("SVC-0001"))
                .andExpect(jsonPath("$.pagination.totalElements").value(1));
    }

    @Test
    @DisplayName("DELETE /api/v1/services/{serviceId}: should delete service when no active bookings exist")
    void shouldDeleteServiceWhenNoActiveBookings() throws Exception {
        AbstractAuthenticationToken adminAuth = authenticationConverter.convert(
                createMockJwt("TENANT_ADMIN", "admin@tenant.com", "tenant-alpha")
        );

        mockMvc.perform(delete("/api/v1/services/svc-uuid-001")
                        .with(authentication(adminAuth))
                        .header("X-Tenant-Code", "tenant-alpha"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Dịch vụ đã được xóa thành công"));
    }

    @Test
    @DisplayName("DELETE /api/v1/services/{serviceId}: should return 409 Conflict when service has active bookings")
    void shouldReturnConflict_whenDeletingServiceWithActiveBookings() throws Exception {
        doThrow(new ServiceException(
                ServiceErrorCode.SERVICE_CANNOT_DELETE_ACTIVE_BOOKINGS,
                "Dịch vụ này hiện đang có lịch hẹn hoặc vé chờ chưa hoàn tất. Hệ thống không thể xóa dịch vụ. Quản trị viên vui lòng chuyển sang trạng thái “Tạm ngưng hoạt động” (Inactive) để dừng tiếp nhận lượt mới."
        )).when(catalogService).deleteService("svc-active-001");

        AbstractAuthenticationToken adminAuth = authenticationConverter.convert(
                createMockJwt("TENANT_ADMIN", "admin@tenant.com", "tenant-alpha")
        );

        mockMvc.perform(delete("/api/v1/services/svc-active-001")
                        .with(authentication(adminAuth))
                        .header("X-Tenant-Code", "tenant-alpha"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.errorCode").value("SVC_004"))
                .andExpect(jsonPath("$.message").value("Dịch vụ này hiện đang có lịch hẹn hoặc vé chờ chưa hoàn tất. Hệ thống không thể xóa dịch vụ. Quản trị viên vui lòng chuyển sang trạng thái “Tạm ngưng hoạt động” (Inactive) để dừng tiếp nhận lượt mới."));
    }

    @Test
    @DisplayName("POST /api/v1/services: should return 403 Forbidden when caller is not TENANT_ADMIN")
    void shouldReturnForbidden_whenNotTenantAdmin() throws Exception {
        CreateServiceRequest request = CreateServiceRequest.builder()
                .serviceName("Tiếp Nhận Hồ Sơ")
                .standardDuration(15)
                .price(new BigDecimal("100000.00"))
                .build();

        AbstractAuthenticationToken counterAuth = authenticationConverter.convert(
                createMockJwt("COUNTER_STAFF", "teller@tenant.com", "tenant-alpha")
        );

        mockMvc.perform(post("/api/v1/services")
                        .with(authentication(counterAuth))
                        .header("X-Tenant-Code", "tenant-alpha")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
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
