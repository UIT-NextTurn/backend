package com.nextturn.security;

import com.nextturn.security.annotation.RequireCounterStaff;
import com.nextturn.security.annotation.RequireEndUser;
import com.nextturn.security.annotation.RequireSuperAdmin;
import com.nextturn.security.annotation.RequireTenantAdmin;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import({TestSecurityConfig.class, PreAuthorizeRoleIntegrationTest.TestSecuredController.class})
class PreAuthorizeRoleIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private KeycloakJwtAuthenticationConverter authenticationConverter;

    @Test
    @DisplayName("SUPERADMIN should be permitted to access all secured endpoints")
    void superAdminShouldAccessAllEndpoints() throws Exception {
        AbstractAuthenticationToken auth = authenticationConverter.convert(createMockJwt("SUPERADMIN", "sa@nextturn.com"));

        mockMvc.perform(get("/api/v1/test-roles/superadmin").with(authentication(auth)))
                .andExpect(status().isOk())
                .andExpect(content().string("superadmin-ok"));

        mockMvc.perform(get("/api/v1/test-roles/tenant-admin").with(authentication(auth)))
                .andExpect(status().isOk())
                .andExpect(content().string("tenant-admin-ok"));

        mockMvc.perform(get("/api/v1/test-roles/counter-staff").with(authentication(auth)))
                .andExpect(status().isOk())
                .andExpect(content().string("counter-staff-ok"));

        mockMvc.perform(get("/api/v1/test-roles/end-user").with(authentication(auth)))
                .andExpect(status().isOk())
                .andExpect(content().string("end-user-ok"));

        mockMvc.perform(get("/api/v1/test-roles/spel-superadmin").with(authentication(auth)))
                .andExpect(status().isOk())
                .andExpect(content().string("spel-superadmin-ok"));
    }

    @Test
    @DisplayName("TENANT_ADMIN cannot access SUPERADMIN endpoint but can access tenant and staff endpoints")
    void tenantAdminAccessControl() throws Exception {
        AbstractAuthenticationToken auth = authenticationConverter.convert(createMockJwt("TENANT_ADMIN", "admin@tenant1.com"));

        // Forbidden on superadmin
        mockMvc.perform(get("/api/v1/test-roles/superadmin").with(authentication(auth)))
                .andExpect(status().isForbidden());

        // OK on tenant-admin, counter-staff, end-user
        mockMvc.perform(get("/api/v1/test-roles/tenant-admin").with(authentication(auth)))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/test-roles/counter-staff").with(authentication(auth)))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/test-roles/end-user").with(authentication(auth)))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("COUNTER_STAFF cannot access tenant-admin or superadmin endpoints")
    void counterStaffAccessControl() throws Exception {
        AbstractAuthenticationToken auth = authenticationConverter.convert(createMockJwt("COUNTER_STAFF", "staff@clinic.com"));

        mockMvc.perform(get("/api/v1/test-roles/superadmin").with(authentication(auth)))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/v1/test-roles/tenant-admin").with(authentication(auth)))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/v1/test-roles/counter-staff").with(authentication(auth)))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("END_USER cannot access counter staff or admin endpoints")
    void endUserAccessControl() throws Exception {
        AbstractAuthenticationToken auth = authenticationConverter.convert(createMockJwt("END_USER", "guest@gmail.com"));

        mockMvc.perform(get("/api/v1/test-roles/counter-staff").with(authentication(auth)))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/v1/test-roles/end-user").with(authentication(auth)))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("Unauthenticated request should return 401 Unauthorized")
    void unauthenticatedRequestShouldFail() throws Exception {
        mockMvc.perform(get("/api/v1/test-roles/superadmin"))
                .andExpect(status().isUnauthorized());
    }

    private Jwt createMockJwt(String role, String email) {
        return Jwt.withTokenValue("mock-jwt-token")
                .header("alg", "RS256")
                .issuer("http://ryan-a1:8180/realms/nextturn")
                .subject("1eebb091-3264-4c21-92d5-40eba7ea15ae")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300))
                .claim("azp", "nextturn-backend")
                .claim("email", email)
                .claim("scope", "openid organization")
                .claim("resource_access", Map.of(
                        "nextturn-backend", Map.of(
                                "roles", List.of(role)
                        )
                ))
                .build();
    }

    @RestController
    @RequestMapping("/api/v1/test-roles")
    static class TestSecuredController {

        @GetMapping("/superadmin")
        @RequireSuperAdmin
        public ResponseEntity<String> superAdminOnly() {
            return ResponseEntity.ok("superadmin-ok");
        }

        @GetMapping("/tenant-admin")
        @RequireTenantAdmin
        public ResponseEntity<String> tenantAdminEndpoint() {
            return ResponseEntity.ok("tenant-admin-ok");
        }

        @GetMapping("/counter-staff")
        @RequireCounterStaff
        public ResponseEntity<String> counterStaffEndpoint() {
            return ResponseEntity.ok("counter-staff-ok");
        }

        @GetMapping("/end-user")
        @RequireEndUser
        public ResponseEntity<String> endUserEndpoint() {
            return ResponseEntity.ok("end-user-ok");
        }

        @GetMapping("/spel-superadmin")
        @PreAuthorize("@authz.isSuperAdmin()")
        public ResponseEntity<String> spelSuperAdmin() {
            return ResponseEntity.ok("spel-superadmin-ok");
        }
    }
}
