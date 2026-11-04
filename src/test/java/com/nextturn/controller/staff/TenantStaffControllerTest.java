package com.nextturn.controller.staff;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nextturn.config.CorsConfig;
import com.nextturn.config.ScalarConfig;
import com.nextturn.dto.service.ServiceResponse;
import com.nextturn.dto.staff.AssignStaffServicesRequest;
import com.nextturn.dto.staff.CreateStaffRequest;
import com.nextturn.dto.staff.StaffResponse;
import com.nextturn.dto.staff.UpdateStaffRequest;
import com.nextturn.enums.StaffRole;
import com.nextturn.enums.StaffStatus;
import com.nextturn.exception.GlobalExceptionHandler;
import com.nextturn.exception.staff.StaffErrorCode;
import com.nextturn.exception.staff.StaffException;
import com.nextturn.infrastructure.multitenancy.TenantContextFilter;
import com.nextturn.infrastructure.security.TestSecurityConfig;
import com.nextturn.infrastructure.security.config.SecurityConfig;
import com.nextturn.infrastructure.security.keycloak.KeycloakJwtAuthenticationConverter;
import com.nextturn.infrastructure.security.keycloak.KeycloakJwtGrantedAuthoritiesConverter;
import com.nextturn.infrastructure.security.principal.CurrentUserArgumentResolver;
import com.nextturn.infrastructure.security.service.SecurityContextHelper;
import com.nextturn.service.staff.TenantStaffService;
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

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(TenantStaffController.class)
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
class TenantStaffControllerTest {

    @Autowired
    private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Autowired
    private KeycloakJwtAuthenticationConverter authenticationConverter;

    @MockitoBean
    private TenantStaffService staffService;

    @Test
    @DisplayName("POST /api/v1/staff: should create staff and return 201 Created for TENANT_ADMIN")
    void shouldCreateStaffWhenTenantAdmin() throws Exception {
        CreateStaffRequest request = CreateStaffRequest.builder()
                .fullName("Nguyễn Văn A")
                .email("staff.a@tenant.com")
                .phoneNumber("0912345678")
                .role(StaffRole.COUNTER)
                .initialPassword("StaffPass123!@#")
                .build();

        StaffResponse response = StaffResponse.builder()
                .id("staff-uuid-1")
                .staffCode("STAFF-0001")
                .fullName("Nguyễn Văn A")
                .email("staff.a@tenant.com")
                .phoneNumber("0912345678")
                .role(StaffRole.COUNTER)
                .status(StaffStatus.ACTIVE)
                .createdAt(LocalDateTime.now())
                .build();

        when(staffService.createStaff(any(CreateStaffRequest.class))).thenReturn(response);

        AbstractAuthenticationToken adminAuth = authenticationConverter.convert(
                createMockJwt("TENANT_ADMIN", "admin@tenant.com", "tenant-alpha")
        );

        mockMvc.perform(post("/api/v1/staff")
                        .with(authentication(adminAuth))
                        .header("X-Tenant-Code", "tenant-alpha")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value(201))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.staffCode").value("STAFF-0001"))
                .andExpect(jsonPath("$.data.fullName").value("Nguyễn Văn A"));
    }

    @Test
    @DisplayName("POST /api/v1/staff: should return 400 Bad Request when fullName contains numbers or symbols")
    void shouldReturnBadRequest_whenFullNameInvalid() throws Exception {
        CreateStaffRequest request = CreateStaffRequest.builder()
                .fullName("Nguyen Van 123 <script>")
                .email("staff.invalid@tenant.com")
                .phoneNumber("0912345678")
                .role(StaffRole.COUNTER)
                .build();

        AbstractAuthenticationToken adminAuth = authenticationConverter.convert(
                createMockJwt("TENANT_ADMIN", "admin@tenant.com", "tenant-alpha")
        );

        mockMvc.perform(post("/api/v1/staff")
                        .with(authentication(adminAuth))
                        .header("X-Tenant-Code", "tenant-alpha")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.errorCode").value("SYS_002"));
    }

    @Test
    @DisplayName("POST /api/v1/staff: should return 400 Bad Request when phone number format is invalid")
    void shouldReturnBadRequest_whenPhoneNumberInvalid() throws Exception {
        CreateStaffRequest request = CreateStaffRequest.builder()
                .fullName("Nguyễn Văn A")
                .email("staff.invalid@tenant.com")
                .phoneNumber("123456")
                .role(StaffRole.COUNTER)
                .build();

        AbstractAuthenticationToken adminAuth = authenticationConverter.convert(
                createMockJwt("TENANT_ADMIN", "admin@tenant.com", "tenant-alpha")
        );

        mockMvc.perform(post("/api/v1/staff")
                        .with(authentication(adminAuth))
                        .header("X-Tenant-Code", "tenant-alpha")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    @DisplayName("POST /api/v1/staff: should return 409 Conflict when email or phone already exists")
    void shouldReturnConflict_whenDuplicateEmailOrPhone() throws Exception {
        CreateStaffRequest request = CreateStaffRequest.builder()
                .fullName("Nguyễn Văn A")
                .email("duplicate@tenant.com")
                .phoneNumber("0912345678")
                .role(StaffRole.COUNTER)
                .build();

        when(staffService.createStaff(any(CreateStaffRequest.class)))
                .thenThrow(new StaffException(
                        StaffErrorCode.STAFF_ALREADY_EXISTS,
                        "Email hoặc số điện thoại này đã được đăng ký bởi một nhân viên khác trong hệ thống."
                ));

        AbstractAuthenticationToken adminAuth = authenticationConverter.convert(
                createMockJwt("TENANT_ADMIN", "admin@tenant.com", "tenant-alpha")
        );

        mockMvc.perform(post("/api/v1/staff")
                        .with(authentication(adminAuth))
                        .header("X-Tenant-Code", "tenant-alpha")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.errorCode").value("STF_001"))
                .andExpect(jsonPath("$.message").value("Email hoặc số điện thoại này đã được đăng ký bởi một nhân viên khác trong hệ thống."));
    }

    @Test
    @DisplayName("GET /api/v1/staff: should return paginated staff list")
    void shouldReturnPaginatedStaffList() throws Exception {
        StaffResponse s1 = StaffResponse.builder()
                .id("staff-uuid-1")
                .staffCode("STAFF-0001")
                .fullName("Nguyễn Văn A")
                .status(StaffStatus.ACTIVE)
                .build();

        when(staffService.getStaffList(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(s1)));

        AbstractAuthenticationToken adminAuth = authenticationConverter.convert(
                createMockJwt("TENANT_ADMIN", "admin@tenant.com", "tenant-alpha")
        );

        mockMvc.perform(get("/api/v1/staff")
                        .with(authentication(adminAuth))
                        .header("X-Tenant-Code", "tenant-alpha"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(200))
                .andExpect(jsonPath("$.data[0].staffCode").value("STAFF-0001"));
    }

    @Test
    @DisplayName("GET /api/v1/staff: should search staff with keyword, role, and status")
    void shouldSearchStaffWithFilters() throws Exception {
        StaffResponse s1 = StaffResponse.builder()
                .id("staff-uuid-2")
                .staffCode("STAFF-0002")
                .fullName("Trần Văn Bình")
                .role(StaffRole.COUNTER)
                .status(StaffStatus.ACTIVE)
                .build();

        when(staffService.searchStaff(eq("Bình"), eq(StaffRole.COUNTER), eq(StaffStatus.ACTIVE), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(s1)));

        AbstractAuthenticationToken adminAuth = authenticationConverter.convert(
                createMockJwt("TENANT_ADMIN", "admin@tenant.com", "tenant-alpha")
        );

        mockMvc.perform(get("/api/v1/staff")
                        .param("keyword", "Bình")
                        .param("role", "COUNTER")
                        .param("status", "ACTIVE")
                        .with(authentication(adminAuth))
                        .header("X-Tenant-Code", "tenant-alpha"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(200))
                .andExpect(jsonPath("$.data[0].staffCode").value("STAFF-0002"))
                .andExpect(jsonPath("$.data[0].fullName").value("Trần Văn Bình"));
    }

    @Test
    @DisplayName("PUT /api/v1/staff/{staffId}/services: should assign services to staff")
    void shouldAssignServicesToStaff() throws Exception {
        AssignStaffServicesRequest request = AssignStaffServicesRequest.builder()
                .serviceIds(List.of("svc-1", "svc-2"))
                .build();

        ServiceResponse s1 = ServiceResponse.builder()
                .id("svc-1")
                .serviceCode("SVC-0001")
                .serviceName("Tư Vấn Khách Hàng")
                .build();

        StaffResponse response = StaffResponse.builder()
                .id("staff-uuid-1")
                .staffCode("STAFF-0001")
                .fullName("Nguyễn Văn A")
                .status(StaffStatus.ACTIVE)
                .assignedServices(List.of(s1))
                .build();

        when(staffService.assignServices(eq("staff-uuid-1"), any(AssignStaffServicesRequest.class)))
                .thenReturn(response);

        AbstractAuthenticationToken adminAuth = authenticationConverter.convert(
                createMockJwt("TENANT_ADMIN", "admin@tenant.com", "tenant-alpha")
        );

        mockMvc.perform(put("/api/v1/staff/staff-uuid-1/services")
                        .with(authentication(adminAuth))
                        .header("X-Tenant-Code", "tenant-alpha")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(200))
                .andExpect(jsonPath("$.data.assignedServices[0].serviceCode").value("SVC-0001"));
    }

    @Test
    @DisplayName("GET /api/v1/staff/{staffId}/services: should return assigned services")
    void shouldGetAssignedServices() throws Exception {
        ServiceResponse s1 = ServiceResponse.builder()
                .id("svc-1")
                .serviceCode("SVC-0001")
                .serviceName("Tư Vấn Khách Hàng")
                .build();

        when(staffService.getAssignedServices("staff-uuid-1")).thenReturn(List.of(s1));

        AbstractAuthenticationToken adminAuth = authenticationConverter.convert(
                createMockJwt("TENANT_ADMIN", "admin@tenant.com", "tenant-alpha")
        );

        mockMvc.perform(get("/api/v1/staff/staff-uuid-1/services")
                        .with(authentication(adminAuth))
                        .header("X-Tenant-Code", "tenant-alpha"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].serviceCode").value("SVC-0001"));
    }

    @Test
    @DisplayName("POST /api/v1/auth/staff: should support backward-compatible endpoint path")
    void shouldSupportBackwardCompatibleEndpointPath() throws Exception {
        CreateStaffRequest request = CreateStaffRequest.builder()
                .fullName("Nguyễn Văn A")
                .email("staff.a@tenant.com")
                .phoneNumber("0912345678")
                .role(StaffRole.COUNTER)
                .build();

        StaffResponse response = StaffResponse.builder()
                .id("staff-uuid-1")
                .staffCode("STAFF-0001")
                .fullName("Nguyễn Văn A")
                .build();

        when(staffService.createStaff(any(CreateStaffRequest.class))).thenReturn(response);

        AbstractAuthenticationToken adminAuth = authenticationConverter.convert(
                createMockJwt("TENANT_ADMIN", "admin@tenant.com", "tenant-alpha")
        );

        mockMvc.perform(post("/api/v1/auth/staff")
                        .with(authentication(adminAuth))
                        .header("X-Tenant-Code", "tenant-alpha")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.staffCode").value("STAFF-0001"));
    }

    @Test
    @DisplayName("PUT /api/v1/staff/{staffId}: should return 409 Conflict when deactivating staff with upcoming appointments")
    void shouldReturn409ConflictWhenDeactivatingStaffWithUpcomingAppointments() throws Exception {
        UpdateStaffRequest request = UpdateStaffRequest.builder()
                .status(StaffStatus.INACTIVE)
                .build();

        when(staffService.updateStaff(eq("staff-uuid-1"), any(UpdateStaffRequest.class)))
                .thenThrow(new StaffException(
                        StaffErrorCode.STAFF_CANNOT_DEACTIVATE_ACTIVE_SCHEDULE,
                        "Nhân viên hiện đang có 2 cuộc hẹn trong tương lai chưa hoàn tất. Vui lòng chuyển giao các cuộc hẹn này sang nhân viên khác trước khi vô hiệu hóa."
                ));

        AbstractAuthenticationToken adminAuth = authenticationConverter.convert(
                createMockJwt("TENANT_ADMIN", "admin@tenant.com", "tenant-alpha")
        );

        mockMvc.perform(put("/api/v1/staff/staff-uuid-1")
                        .with(authentication(adminAuth))
                        .header("X-Tenant-Code", "tenant-alpha")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.errorCode").value("STF_006"));
    }

    @Test
    @DisplayName("DELETE /api/v1/staff/{staffId}: should soft-delete staff and return 200 OK")
    void shouldSoftDeleteStaffWhenTenantAdmin() throws Exception {
        AbstractAuthenticationToken adminAuth = authenticationConverter.convert(
                createMockJwt("TENANT_ADMIN", "admin@tenant.com", "tenant-alpha")
        );

        mockMvc.perform(delete("/api/v1/staff/staff-uuid-1")
                        .with(authentication(adminAuth))
                        .header("X-Tenant-Code", "tenant-alpha"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(200))
                .andExpect(jsonPath("$.message").value("Vô hiệu hóa và xóa nhân viên thành công"));
    }

    @Test
    @DisplayName("DELETE /api/v1/staff/{staffId}?hardDelete=true: should physically delete staff when no historical records")
    void shouldHardDeleteStaffWhenTenantAdminAndNoHistory() throws Exception {
        AbstractAuthenticationToken adminAuth = authenticationConverter.convert(
                createMockJwt("TENANT_ADMIN", "admin@tenant.com", "tenant-alpha")
        );

        mockMvc.perform(delete("/api/v1/staff/staff-uuid-1")
                        .param("hardDelete", "true")
                        .with(authentication(adminAuth))
                        .header("X-Tenant-Code", "tenant-alpha"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(200))
                .andExpect(jsonPath("$.message").value("Xóa vật lý nhân viên thành công"));
    }

    @Test
    @DisplayName("DELETE /api/v1/staff/{staffId}?hardDelete=true: should return 409 Conflict when historical activity exists")
    void shouldReturn409ConflictWhenHardDeletingStaffWithHistory() throws Exception {
        org.mockito.Mockito.doThrow(new StaffException(
                StaffErrorCode.STAFF_CANNOT_HARD_DELETE_HISTORICAL_DATA,
                "Hệ thống tuyệt đối từ chối thao tác Xóa vật lý (Hard delete) bản ghi nhân sự nếu nhân viên đó đã từng có lịch sử gọi vé, tiếp nhận phục vụ hoặc phản hồi đánh giá trong cơ sở dữ liệu."
        )).when(staffService).deleteStaff("staff-uuid-1", true);

        AbstractAuthenticationToken adminAuth = authenticationConverter.convert(
                createMockJwt("TENANT_ADMIN", "admin@tenant.com", "tenant-alpha")
        );

        mockMvc.perform(delete("/api/v1/staff/staff-uuid-1")
                        .param("hardDelete", "true")
                        .with(authentication(adminAuth))
                        .header("X-Tenant-Code", "tenant-alpha"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.errorCode").value("STF_007"));
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

