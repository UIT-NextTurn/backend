package com.nextturn.security;

import com.nextturn.security.annotation.CurrentUser;
import com.nextturn.security.annotation.RequireCounterStaff;
import com.nextturn.security.annotation.RequireEndUser;
import com.nextturn.security.annotation.RequireStaff;
import com.nextturn.security.annotation.RequireSuperAdmin;
import com.nextturn.security.annotation.RequireTenantAdmin;
import com.nextturn.security.keycloak.KeycloakJwtAuthenticationConverter;
import com.nextturn.security.principal.AuthenticatedUser;
import com.nextturn.config.CorsConfig;
import com.nextturn.security.config.SecurityConfig;
import com.nextturn.security.keycloak.KeycloakJwtGrantedAuthoritiesConverter;
import com.nextturn.security.keycloak.KeycloakProperties;
import com.nextturn.security.principal.CurrentUserArgumentResolver;
import com.nextturn.security.service.SecurityContextHelper;
import com.nextturn.security.service.SecurityService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import com.nextturn.multitenancy.TenantContextFilter;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = PreAuthorizeRoleIntegrationTest.TestSecuredController.class)
@EnableMethodSecurity(prePostEnabled = true)
@Import({
        SecurityConfig.class,
        TestSecurityConfig.class,
        CorsConfig.class,
        TenantContextFilter.class,
        KeycloakJwtAuthenticationConverter.class,
        KeycloakJwtGrantedAuthoritiesConverter.class,
        SecurityService.class,
        SecurityContextHelper.class,
        CurrentUserArgumentResolver.class,
        PreAuthorizeRoleIntegrationTest.TestSecuredController.class
})
class PreAuthorizeRoleIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private KeycloakJwtAuthenticationConverter authenticationConverter;

    @Test
    @DisplayName("SUPERADMIN should only access SUPERADMIN endpoints")
    void superAdminAccessControl() throws Exception {
        AbstractAuthenticationToken auth = authenticationConverter.convert(createMockJwt("SUPERADMIN", "sa@nextturn.com"));

        mockMvc.perform(get("/api/v1/test-roles/superadmin").with(authentication(auth)))
                .andExpect(status().isOk())
                .andExpect(content().string("superadmin-ok"));

        mockMvc.perform(get("/api/v1/test-roles/spel-superadmin").with(authentication(auth)))
                .andExpect(status().isOk())
                .andExpect(content().string("spel-superadmin-ok"));

        // Isolated business: SUPERADMIN cannot access tenant-admin, counter-staff, or end-user endpoints
        mockMvc.perform(get("/api/v1/test-roles/tenant-admin").with(authentication(auth)))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/v1/test-roles/counter-staff").with(authentication(auth)))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/v1/test-roles/end-user").with(authentication(auth)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("TENANT_ADMIN should only access TENANT_ADMIN endpoint")
    void tenantAdminAccessControl() throws Exception {
        AbstractAuthenticationToken auth = authenticationConverter.convert(createMockJwt("TENANT_ADMIN", "admin@tenant1.com"));

        // OK on tenant-admin
        mockMvc.perform(get("/api/v1/test-roles/tenant-admin").with(authentication(auth)))
                .andExpect(status().isOk())
                .andExpect(content().string("tenant-admin-ok"));

        // Forbidden on superadmin, counter-staff, end-user
        mockMvc.perform(get("/api/v1/test-roles/superadmin").with(authentication(auth)))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/v1/test-roles/counter-staff").with(authentication(auth)))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/v1/test-roles/end-user").with(authentication(auth)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("COUNTER_STAFF should only access COUNTER_STAFF endpoint")
    void counterStaffAccessControl() throws Exception {
        AbstractAuthenticationToken auth = authenticationConverter.convert(createMockJwt("COUNTER_STAFF", "staff@clinic.com"));

        // OK on counter-staff
        mockMvc.perform(get("/api/v1/test-roles/counter-staff").with(authentication(auth)))
                .andExpect(status().isOk())
                .andExpect(content().string("counter-staff-ok"));

        // Forbidden on superadmin, tenant-admin, end-user
        mockMvc.perform(get("/api/v1/test-roles/superadmin").with(authentication(auth)))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/v1/test-roles/tenant-admin").with(authentication(auth)))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/v1/test-roles/end-user").with(authentication(auth)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("END_USER should only access END_USER endpoint")
    void endUserAccessControl() throws Exception {
        AbstractAuthenticationToken auth = authenticationConverter.convert(createMockJwt("END_USER", "guest@gmail.com"));

        // OK on end-user
        mockMvc.perform(get("/api/v1/test-roles/end-user").with(authentication(auth)))
                .andExpect(status().isOk())
                .andExpect(content().string("end-user-ok"));

        // Forbidden on superadmin, tenant-admin, counter-staff
        mockMvc.perform(get("/api/v1/test-roles/superadmin").with(authentication(auth)))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/v1/test-roles/tenant-admin").with(authentication(auth)))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/v1/test-roles/counter-staff").with(authentication(auth)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Unauthenticated request should return 401 Unauthorized")
    void unauthenticatedRequestShouldFail() throws Exception {
        mockMvc.perform(get("/api/v1/test-roles/superadmin"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Should inject @CurrentUser AuthenticatedUser into controller parameter")
    void currentUserArgumentResolverShouldInjectPrincipal() throws Exception {
        AbstractAuthenticationToken auth = authenticationConverter.convert(createMockJwt("TENANT_ADMIN", "admin@tenant1.com"));

        mockMvc.perform(get("/api/v1/test-roles/current-user").with(authentication(auth)))
                .andExpect(status().isOk())
                .andExpect(content().string("user:admin@tenant1.com"));
    }

    @Test
    @DisplayName("@RequireStaff should allow TENANT_ADMIN and COUNTER_STAFF, but forbid SUPERADMIN and END_USER")
    void staffEndpointAccessControl() throws Exception {
        AbstractAuthenticationToken tenantAdminAuth = authenticationConverter.convert(createMockJwt("TENANT_ADMIN", "admin@tenant1.com"));
        AbstractAuthenticationToken counterStaffAuth = authenticationConverter.convert(createMockJwt("COUNTER_STAFF", "staff@clinic.com"));
        AbstractAuthenticationToken superAdminAuth = authenticationConverter.convert(createMockJwt("SUPERADMIN", "sa@nextturn.com"));
        AbstractAuthenticationToken endUserAuth = authenticationConverter.convert(createMockJwt("END_USER", "guest@gmail.com"));

        // Both TENANT_ADMIN and COUNTER_STAFF can access @RequireStaff
        mockMvc.perform(get("/api/v1/test-roles/staff").with(authentication(tenantAdminAuth)))
                .andExpect(status().isOk())
                .andExpect(content().string("staff-ok"));

        mockMvc.perform(get("/api/v1/test-roles/staff").with(authentication(counterStaffAuth)))
                .andExpect(status().isOk())
                .andExpect(content().string("staff-ok"));

        // SUPERADMIN and END_USER are forbidden
        mockMvc.perform(get("/api/v1/test-roles/staff").with(authentication(superAdminAuth)))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/v1/test-roles/staff").with(authentication(endUserAuth)))
                .andExpect(status().isForbidden());
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

        @GetMapping("/staff")
        @RequireStaff
        public ResponseEntity<String> staffEndpoint() {
            return ResponseEntity.ok("staff-ok");
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

        @GetMapping("/current-user")
        public ResponseEntity<String> currentUserEndpoint(@CurrentUser AuthenticatedUser user) {
            if (user == null) {
                return ResponseEntity.status(500).body("user-is-null");
            }
            return ResponseEntity.ok("user:" + user.email());
        }
    }
}


