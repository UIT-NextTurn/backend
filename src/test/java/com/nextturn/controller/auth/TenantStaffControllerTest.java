package com.nextturn.controller.auth;

import com.nextturn.config.CorsConfig;
import com.nextturn.config.ScalarConfig;
import com.nextturn.dto.auth.CreateStaffRequest;
import com.nextturn.dto.auth.StaffResponse;
import com.nextturn.dto.auth.UpdateStaffRequest;
import com.nextturn.enums.StaffRole;
import com.nextturn.enums.StaffStatus;
import com.nextturn.exception.GlobalExceptionHandler;
import com.nextturn.infrastructure.multitenancy.TenantContextFilter;
import com.nextturn.infrastructure.security.TestSecurityConfig;
import com.nextturn.infrastructure.security.config.SecurityConfig;
import com.nextturn.infrastructure.security.keycloak.KeycloakJwtAuthenticationConverter;
import com.nextturn.infrastructure.security.keycloak.KeycloakJwtGrantedAuthoritiesConverter;
import com.nextturn.infrastructure.security.principal.CurrentUserArgumentResolver;
import com.nextturn.infrastructure.security.service.SecurityContextHelper;
import com.nextturn.service.auth.TenantStaffService;
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
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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

    @Autowired
    private KeycloakJwtAuthenticationConverter authenticationConverter;

    @MockitoBean
    private TenantStaffService staffService;

    @Test
    @DisplayName("POST /api/v1/auth/staff should allow TENANT_ADMIN and return 201 Created")
    void shouldCreateStaffWhenTenantAdmin() throws Exception {
        StaffResponse response = StaffResponse.builder()
                .id("staff-uuid-1")
                .staffCode("STF-000001")
                .keycloakUserId("kc-user-uuid-1")
                .fullName("Dr. Tran Van B")
                .email("dr.tran@clinic.vn")
                .phoneNumber("+84901234567")
                .role(StaffRole.COUNTER)
                .status(StaffStatus.ACTIVE)
                .createdAt(LocalDateTime.now())
                .build();

        when(staffService.createStaff(any(CreateStaffRequest.class))).thenReturn(response);

        AbstractAuthenticationToken tenantAdminAuth = authenticationConverter.convert(
                createMockJwt("TENANT_ADMIN", "admin@clinic.vn", "clinic_sai_gon")
        );

        String requestJson = """
                {
                    "fullName": "Dr. Tran Van B",
                    "email": "dr.tran@clinic.vn",
                    "phoneNumber": "+84901234567",
                    "role": "COUNTER"
                }
                """;

        mockMvc.perform(post("/api/v1/auth/staff")
                        .with(authentication(tenantAdminAuth))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.staffCode").value("STF-000001"))
                .andExpect(jsonPath("$.data.fullName").value("Dr. Tran Van B"))
                .andExpect(jsonPath("$.data.role").value("COUNTER"))
                .andExpect(jsonPath("$.data.keycloakUserId").value("kc-user-uuid-1"));
    }

    @Test
    @DisplayName("POST /api/v1/auth/staff should forbid COUNTER_STAFF role (403)")
    void shouldForbidCounterStaffFromCreatingStaff() throws Exception {
        AbstractAuthenticationToken counterStaffAuth = authenticationConverter.convert(
                createMockJwt("COUNTER_STAFF", "staff@clinic.vn", "clinic_sai_gon")
        );

        String requestJson = """
                {
                    "fullName": "Dr. Hack",
                    "email": "dr.hack@clinic.vn",
                    "phoneNumber": "+84901234567"
                }
                """;

        mockMvc.perform(post("/api/v1/auth/staff")
                        .with(authentication(counterStaffAuth))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("GET /api/v1/auth/staff should return paginated staff list")
    void shouldGetStaffList() throws Exception {
        StaffResponse response = StaffResponse.builder()
                .id("staff-uuid-1")
                .staffCode("STF-000001")
                .fullName("Dr. Tran Van B")
                .email("dr.tran@clinic.vn")
                .role(StaffRole.COUNTER)
                .status(StaffStatus.ACTIVE)
                .build();

        when(staffService.getStaffList(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(response)));

        AbstractAuthenticationToken tenantAdminAuth = authenticationConverter.convert(
                createMockJwt("TENANT_ADMIN", "admin@clinic.vn", "clinic_sai_gon")
        );

        mockMvc.perform(get("/api/v1/auth/staff")
                        .with(authentication(tenantAdminAuth)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data[0].staffCode").value("STF-000001"))
                .andExpect(jsonPath("$.pagination.page").value(0))
                .andExpect(jsonPath("$.pagination.totalElements").value(1))
                .andExpect(jsonPath("$.pagination.totalPages").value(1));
    }

    @Test
    @DisplayName("DELETE /api/v1/auth/staff/{id} should return 200 OK")
    void shouldDeleteStaff() throws Exception {
        AbstractAuthenticationToken tenantAdminAuth = authenticationConverter.convert(
                createMockJwt("TENANT_ADMIN", "admin@clinic.vn", "clinic_sai_gon")
        );

        mockMvc.perform(delete("/api/v1/auth/staff/staff-123")
                        .with(authentication(tenantAdminAuth)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
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
