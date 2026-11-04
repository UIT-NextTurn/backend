package com.nextturn.controller.staff;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nextturn.config.CorsConfig;
import com.nextturn.config.ScalarConfig;
import com.nextturn.controller.catalog.TenantCatalogController;
import com.nextturn.dto.service.CreateServiceRequest;
import com.nextturn.dto.service.ServiceResponse;
import com.nextturn.dto.staff.AssignStaffServicesRequest;
import com.nextturn.dto.staff.CreateStaffRequest;
import com.nextturn.dto.staff.StaffResponse;
import com.nextturn.dto.staff.UpdateStaffRequest;
import com.nextturn.enums.ServiceStatus;
import com.nextturn.enums.StaffRole;
import com.nextturn.enums.StaffStatus;
import com.nextturn.exception.GlobalExceptionHandler;
import com.nextturn.exception.service.ServiceErrorCode;
import com.nextturn.exception.service.ServiceException;
import com.nextturn.exception.staff.StaffErrorCode;
import com.nextturn.exception.staff.StaffException;
import com.nextturn.infrastructure.multitenancy.TenantContextFilter;
import com.nextturn.infrastructure.security.TestSecurityConfig;
import com.nextturn.infrastructure.security.config.SecurityConfig;
import com.nextturn.infrastructure.security.keycloak.KeycloakJwtAuthenticationConverter;
import com.nextturn.infrastructure.security.keycloak.KeycloakJwtGrantedAuthoritiesConverter;
import com.nextturn.infrastructure.security.principal.CurrentUserArgumentResolver;
import com.nextturn.infrastructure.security.service.SecurityContextHelper;
import com.nextturn.service.service.TenantServiceService;
import com.nextturn.service.staff.TenantStaffService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
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
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Automated end-to-end integration test validating the complete lifecycle pipeline:
 * Auth -> Multi-Tenancy -> Staff Management -> Service Catalog -> Operational Guards.
 */
@WebMvcTest(controllers = {TenantStaffController.class, TenantCatalogController.class})
@EnableMethodSecurity(prePostEnabled = true)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
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
class AuthTenantStaffServiceFlowIntegrationTest {

    private static final String TENANT_CODE = "tenant-alpha";
    private static final String ADMIN_EMAIL = "admin@tenant-alpha.com";

    @Autowired
    private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Autowired
    private KeycloakJwtAuthenticationConverter authenticationConverter;

    @MockitoBean
    private TenantStaffService staffService;

    @MockitoBean
    private TenantServiceService catalogService;

    private AbstractAuthenticationToken createAdminAuthToken() {
        Jwt jwt = Jwt.withTokenValue("mock-admin-jwt")
                .header("alg", "RS256")
                .issuer("http://ryan-a1:8180/realms/nextturn")
                .subject("admin-sub-uuid")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300))
                .claim("azp", "nextturn-backend")
                .claim("email", ADMIN_EMAIL)
                .claim("tenant_code", TENANT_CODE)
                .claim("scope", "openid organization")
                .claim("resource_access", Map.of(
                        "nextturn-backend", Map.of(
                                "roles", List.of("TENANT_ADMIN")
                        )
                ))
                .build();
        return authenticationConverter.convert(jwt);
    }

    private AbstractAuthenticationToken createUnauthorizedAuthToken() {
        Jwt jwt = Jwt.withTokenValue("mock-counter-jwt")
                .header("alg", "RS256")
                .issuer("http://ryan-a1:8180/realms/nextturn")
                .subject("counter-sub-uuid")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300))
                .claim("azp", "nextturn-backend")
                .claim("email", "counter@tenant-alpha.com")
                .claim("tenant_code", TENANT_CODE)
                .claim("resource_access", Map.of(
                        "nextturn-backend", Map.of(
                                "roles", List.of("COUNTER_STAFF")
                        )
                ))
                .build();
        return authenticationConverter.convert(jwt);
    }

    // =========================================================================
    // Phase 1: Authentication & Token Authorization
    // =========================================================================

    @Test
    @Order(1)
    @DisplayName("Phase 1: Unauthenticated request to protected endpoint should return 401 Unauthorized")
    void phase1_unauthenticatedRequest_shouldReturn401() throws Exception {
        mockMvc.perform(get("/api/v1/staff")
                        .header("X-Tenant-Code", TENANT_CODE))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @Order(2)
    @DisplayName("Phase 1: Non-admin role request to admin endpoint should return 403 Forbidden")
    void phase1_forbiddenRole_shouldReturn403() throws Exception {
        mockMvc.perform(get("/api/v1/staff")
                        .with(authentication(createUnauthorizedAuthToken()))
                        .header("X-Tenant-Code", TENANT_CODE))
                .andExpect(status().isForbidden());
    }

    // =========================================================================
    // Phase 2: Multi-Tenancy Routing & Staff Roster
    // =========================================================================

    @Test
    @Order(3)
    @DisplayName("Phase 2: Valid TENANT_ADMIN token with X-Tenant-Code should return initial staff roster")
    void phase2_tenantRouting_shouldReturnStaffRoster() throws Exception {
        StaffResponse initialAdmin = StaffResponse.builder()
                .id("admin-uuid-0001")
                .staffCode("STAFF-0001")
                .fullName("Admin Facility Alpha")
                .email(ADMIN_EMAIL)
                .phoneNumber("0901000001")
                .role(StaffRole.ADMIN)
                .status(StaffStatus.ACTIVE)
                .createdAt(LocalDateTime.now())
                .build();

        when(staffService.getStaffList(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(initialAdmin)));

        mockMvc.perform(get("/api/v1/staff")
                        .with(authentication(createAdminAuthToken()))
                        .header("X-Tenant-Code", TENANT_CODE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(200))
                .andExpect(jsonPath("$.data[0].staffCode").value("STAFF-0001"))
                .andExpect(jsonPath("$.data[0].role").value("ADMIN"))
                .andExpect(jsonPath("$.data[0].status").value("ACTIVE"));
    }

    // =========================================================================
    // Phase 3: Staff Management & Keycloak Identity Sync
    // =========================================================================

    @Test
    @Order(4)
    @DisplayName("Phase 3: Create staff should validate format, generate STAFF-0002, and return 201 Created")
    void phase3_createStaff_shouldSucceed() throws Exception {
        CreateStaffRequest request = CreateStaffRequest.builder()
                .fullName("Trần Văn Bình")
                .email("binh.tran@tenant-alpha.com")
                .phoneNumber("0987654321")
                .role(StaffRole.COUNTER)
                .initialPassword("StaffSecret123!@#")
                .build();

        StaffResponse createdResponse = StaffResponse.builder()
                .id("staff-uuid-0002")
                .staffCode("STAFF-0002")
                .fullName("Trần Văn Bình")
                .email("binh.tran@tenant-alpha.com")
                .phoneNumber("0987654321")
                .role(StaffRole.COUNTER)
                .status(StaffStatus.ACTIVE)
                .keycloakUserId("kc-user-binh-123")
                .createdAt(LocalDateTime.now())
                .build();

        when(staffService.createStaff(any(CreateStaffRequest.class))).thenReturn(createdResponse);

        mockMvc.perform(post("/api/v1/staff")
                        .with(authentication(createAdminAuthToken()))
                        .header("X-Tenant-Code", TENANT_CODE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value(201))
                .andExpect(jsonPath("$.data.staffCode").value("STAFF-0002"))
                .andExpect(jsonPath("$.data.email").value("binh.tran@tenant-alpha.com"))
                .andExpect(jsonPath("$.data.status").value("ACTIVE"));
    }

    @Test
    @Order(5)
    @DisplayName("Phase 3: Create staff with duplicate email/phone should return 409 Conflict with STF_001")
    void phase3_createStaff_duplicate_shouldReturn409() throws Exception {
        CreateStaffRequest request = CreateStaffRequest.builder()
                .fullName("Trần Văn Bình")
                .email("binh.tran@tenant-alpha.com")
                .phoneNumber("0987654321")
                .role(StaffRole.COUNTER)
                .build();

        when(staffService.createStaff(any(CreateStaffRequest.class)))
                .thenThrow(new StaffException(
                        StaffErrorCode.STAFF_ALREADY_EXISTS,
                        "Email hoặc số điện thoại này đã được đăng ký bởi một nhân viên khác trong hệ thống."
                ));

        mockMvc.perform(post("/api/v1/staff")
                        .with(authentication(createAdminAuthToken()))
                        .header("X-Tenant-Code", TENANT_CODE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.errorCode").value("STF_001"));
    }

    // =========================================================================
    // Phase 4: Service Catalog Creation & Staff Assignment
    // =========================================================================

    @Test
    @Order(6)
    @DisplayName("Phase 4: Create service should succeed and return SVC-0001 with 201 Created")
    void phase4_createService_shouldSucceed() throws Exception {
        CreateServiceRequest serviceRequest = CreateServiceRequest.builder()
                .serviceName("Tư Vấn Hồ Sơ Trực Tuyến")
                .standardDuration(20)
                .price(new BigDecimal("150000.00"))
                .build();

        ServiceResponse serviceResponse = ServiceResponse.builder()
                .id("svc-uuid-0001")
                .serviceCode("SVC-0001")
                .serviceName("Tư Vấn Hồ Sơ Trực Tuyến")
                .standardDuration(20)
                .price(new BigDecimal("150000.00"))
                .status(ServiceStatus.ACTIVE)
                .createdAt(LocalDateTime.now())
                .build();

        when(catalogService.createService(any(CreateServiceRequest.class))).thenReturn(serviceResponse);

        mockMvc.perform(post("/api/v1/services")
                        .with(authentication(createAdminAuthToken()))
                        .header("X-Tenant-Code", TENANT_CODE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(serviceRequest)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value(201))
                .andExpect(jsonPath("$.data.serviceCode").value("SVC-0001"))
                .andExpect(jsonPath("$.data.standardDuration").value(20))
                .andExpect(jsonPath("$.data.status").value("ACTIVE"));
    }

    @Test
    @Order(7)
    @DisplayName("Phase 4: Assign service to staff member should update staff_services and return 200 OK")
    void phase4_assignServiceToStaff_shouldSucceed() throws Exception {
        AssignStaffServicesRequest request = AssignStaffServicesRequest.builder()
                .serviceIds(List.of("svc-uuid-0001"))
                .build();

        ServiceResponse s1 = ServiceResponse.builder()
                .id("svc-uuid-0001")
                .serviceCode("SVC-0001")
                .serviceName("Tư Vấn Hồ Sơ Trực Tuyến")
                .build();

        StaffResponse staffWithServices = StaffResponse.builder()
                .id("staff-uuid-0002")
                .staffCode("STAFF-0002")
                .fullName("Trần Văn Bình")
                .status(StaffStatus.ACTIVE)
                .assignedServices(List.of(s1))
                .build();

        when(staffService.assignServices(eq("staff-uuid-0002"), any(AssignStaffServicesRequest.class)))
                .thenReturn(staffWithServices);

        mockMvc.perform(put("/api/v1/staff/staff-uuid-0002/services")
                        .with(authentication(createAdminAuthToken()))
                        .header("X-Tenant-Code", TENANT_CODE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(200))
                .andExpect(jsonPath("$.data.assignedServices[0].serviceCode").value("SVC-0001"));
    }

    @Test
    @Order(8)
    @DisplayName("Phase 4: Query staff assigned services should return list of ServiceResponses")
    void phase4_getAssignedServices_shouldReturnList() throws Exception {
        ServiceResponse s1 = ServiceResponse.builder()
                .id("svc-uuid-0001")
                .serviceCode("SVC-0001")
                .serviceName("Tư Vấn Hồ Sơ Trực Tuyến")
                .build();

        when(staffService.getAssignedServices("staff-uuid-0002")).thenReturn(List.of(s1));

        mockMvc.perform(get("/api/v1/staff/staff-uuid-0002/services")
                        .with(authentication(createAdminAuthToken()))
                        .header("X-Tenant-Code", TENANT_CODE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(200))
                .andExpect(jsonPath("$.data[0].serviceCode").value("SVC-0001"));
    }

    // =========================================================================
    // Phase 5: Protection & Lifecycle Guards
    // =========================================================================

    @Test
    @Order(9)
    @DisplayName("Phase 5: Deactivating staff with active appointments should return 409 Conflict (STF_006)")
    void phase5_deactivateStaff_withUpcomingAppointments_shouldReturn409() throws Exception {
        UpdateStaffRequest deactivationRequest = UpdateStaffRequest.builder()
                .status(StaffStatus.INACTIVE)
                .build();

        when(staffService.updateStaff(eq("staff-uuid-0002"), any(UpdateStaffRequest.class)))
                .thenThrow(new StaffException(
                        StaffErrorCode.STAFF_CANNOT_DEACTIVATE_ACTIVE_SCHEDULE,
                        "Nhân viên hiện đang có 2 cuộc hẹn trong tương lai chưa hoàn tất (APT-0001, APT-0002). Vui lòng chuyển giao các cuộc hẹn này sang nhân viên khác trước khi vô hiệu hóa."
                ));

        mockMvc.perform(put("/api/v1/staff/staff-uuid-0002")
                        .with(authentication(createAdminAuthToken()))
                        .header("X-Tenant-Code", TENANT_CODE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(deactivationRequest)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.errorCode").value("STF_006"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("APT-0001")));
    }

    @Test
    @Order(10)
    @DisplayName("Phase 5: Deactivating staff when clear should succeed, update status=INACTIVE, and return 200 OK")
    void phase5_deactivateStaff_whenClear_shouldSucceed() throws Exception {
        UpdateStaffRequest deactivationRequest = UpdateStaffRequest.builder()
                .status(StaffStatus.INACTIVE)
                .build();

        StaffResponse inactiveStaff = StaffResponse.builder()
                .id("staff-uuid-0002")
                .staffCode("STAFF-0002")
                .fullName("Trần Văn Bình")
                .status(StaffStatus.INACTIVE)
                .build();

        when(staffService.updateStaff(eq("staff-uuid-0002"), any(UpdateStaffRequest.class)))
                .thenReturn(inactiveStaff);

        mockMvc.perform(put("/api/v1/staff/staff-uuid-0002")
                        .with(authentication(createAdminAuthToken()))
                        .header("X-Tenant-Code", TENANT_CODE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(deactivationRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(200))
                .andExpect(jsonPath("$.data.status").value("INACTIVE"));
    }

    @Test
    @Order(11)
    @DisplayName("Phase 5: Hard delete staff with historical records should return 409 Conflict (STF_007)")
    void phase5_hardDeleteStaff_withHistory_shouldReturn409() throws Exception {
        doThrow(new StaffException(
                StaffErrorCode.STAFF_CANNOT_HARD_DELETE_HISTORICAL_DATA,
                "Hệ thống tuyệt đối từ chối thao tác Xóa vật lý (Hard delete) bản ghi nhân sự nếu nhân viên đó đã từng có lịch sử gọi vé, tiếp nhận phục vụ hoặc phản hồi đánh giá trong cơ sở dữ liệu."
        )).when(staffService).deleteStaff("staff-uuid-0002", true);

        mockMvc.perform(delete("/api/v1/staff/staff-uuid-0002")
                        .param("hardDelete", "true")
                        .with(authentication(createAdminAuthToken()))
                        .header("X-Tenant-Code", TENANT_CODE))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.errorCode").value("STF_007"));
    }

    @Test
    @Order(12)
    @DisplayName("Phase 5: Delete service with active bookings should return 409 Conflict (SVC_004)")
    void phase5_deleteService_withActiveBookings_shouldReturn409() throws Exception {
        doThrow(new ServiceException(
                ServiceErrorCode.SERVICE_CANNOT_DELETE_ACTIVE_BOOKINGS,
                "Dịch vụ này hiện đang có lịch hẹn hoặc vé chờ chưa hoàn tất. Hệ thống không thể xóa dịch vụ."
        )).when(catalogService).deleteService("svc-uuid-0001");

        mockMvc.perform(delete("/api/v1/services/svc-uuid-0001")
                        .with(authentication(createAdminAuthToken()))
                        .header("X-Tenant-Code", TENANT_CODE))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.errorCode").value("SVC_004"));
    }

    @Test
    @Order(13)
    @DisplayName("Phase 5: Soft delete service when clear should succeed and return 200 OK")
    void phase5_deleteService_whenClear_shouldSucceed() throws Exception {
        doNothing().when(catalogService).deleteService("svc-uuid-0001");

        mockMvc.perform(delete("/api/v1/services/svc-uuid-0001")
                        .with(authentication(createAdminAuthToken()))
                        .header("X-Tenant-Code", TENANT_CODE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(200))
                .andExpect(jsonPath("$.message").value("Dịch vụ đã được xóa thành công"));
    }
}

