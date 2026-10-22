package com.nextturn.infrastructure.security;

import com.nextturn.config.CorsConfig;
import com.nextturn.infrastructure.multitenancy.TenantContextFilter;
import com.nextturn.infrastructure.multitenancy.TenantContextHolder;
import com.nextturn.infrastructure.security.config.SecurityConfig;
import com.nextturn.infrastructure.security.keycloak.KeycloakJwtAuthenticationConverter;
import com.nextturn.infrastructure.security.keycloak.KeycloakJwtGrantedAuthoritiesConverter;
import com.nextturn.infrastructure.security.principal.CurrentUserArgumentResolver;
import com.nextturn.infrastructure.security.service.SecurityContextHelper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = EndpointSecurityIntegrationTest.TestEndpointController.class)
@EnableMethodSecurity(prePostEnabled = true)
@Import({
        SecurityConfig.class,
        TestSecurityConfig.class,
        CorsConfig.class,
        TenantContextFilter.class,
        KeycloakJwtAuthenticationConverter.class,
        KeycloakJwtGrantedAuthoritiesConverter.class,
        CurrentUserArgumentResolver.class,
        SecurityContextHelper.class,
        EndpointSecurityIntegrationTest.TestEndpointController.class
})
class EndpointSecurityIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private KeycloakJwtAuthenticationConverter authenticationConverter;

    @Test
    @DisplayName("Public endpoints (/api/v1/public/**, /api/v1/auth/**) should be accessible without authentication")
    void publicEndpointsShouldPermitAll() throws Exception {
        mockMvc.perform(get("/api/v1/public/ping"))
                .andExpect(status().isOk())
                .andExpect(content().string("public-ok"));

        mockMvc.perform(get("/api/v1/auth/login"))
                .andExpect(status().isOk())
                .andExpect(content().string("auth-ok"));
    }

    @Test
    @DisplayName("Central superadmin endpoint (/api/v1/central/**) requires SUPERADMIN role")
    void centralEndpointsSecurityRules() throws Exception {
        // 1. Unauthenticated -> 401
        mockMvc.perform(get("/api/v1/central/dashboard"))
                .andExpect(status().isUnauthorized());

        // 2. SUPERADMIN -> 200 OK
        AbstractAuthenticationToken superAdminAuth = authenticationConverter.convert(
                createMockJwt("SUPERADMIN", "sa@nextturn.com", null)
        );
        mockMvc.perform(get("/api/v1/central/dashboard").with(authentication(superAdminAuth)))
                .andExpect(status().isOk())
                .andExpect(content().string("central-dashboard-ok"));

        // 3. TENANT_ADMIN -> 403 Forbidden
        AbstractAuthenticationToken tenantAdminAuth = authenticationConverter.convert(
                createMockJwt("TENANT_ADMIN", "admin@clinic1.com", "clinic1")
        );
        mockMvc.perform(get("/api/v1/central/dashboard").with(authentication(tenantAdminAuth)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Tenant admin endpoint (/api/v1/tenant/admin/**) requires TENANT_ADMIN role")
    void tenantAdminEndpointsSecurityRules() throws Exception {
        AbstractAuthenticationToken tenantAdminAuth = authenticationConverter.convert(
                createMockJwt("TENANT_ADMIN", "admin@clinic1.com", "clinic1")
        );
        AbstractAuthenticationToken counterStaffAuth = authenticationConverter.convert(
                createMockJwt("COUNTER_STAFF", "staff@clinic1.com", "clinic1")
        );

        // TENANT_ADMIN -> 200 OK
        mockMvc.perform(get("/api/v1/tenant/admin/settings").with(authentication(tenantAdminAuth)))
                .andExpect(status().isOk())
                .andExpect(content().string("tenant-admin-settings-ok"));

        // COUNTER_STAFF -> 403 Forbidden
        mockMvc.perform(get("/api/v1/tenant/admin/settings").with(authentication(counterStaffAuth)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Counter staff endpoint (/api/v1/tenant/counter/**) requires COUNTER_STAFF role")
    void counterStaffEndpointsSecurityRules() throws Exception {
        AbstractAuthenticationToken counterStaffAuth = authenticationConverter.convert(
                createMockJwt("COUNTER_STAFF", "staff@clinic1.com", "clinic1")
        );
        AbstractAuthenticationToken endUserAuth = authenticationConverter.convert(
                createMockJwt("END_USER", "cust@gmail.com", "clinic1")
        );

        // COUNTER_STAFF -> 200 OK
        mockMvc.perform(get("/api/v1/tenant/counter/queue").with(authentication(counterStaffAuth)))
                .andExpect(status().isOk())
                .andExpect(content().string("tenant-counter-queue-ok"));

        // END_USER -> 403 Forbidden
        mockMvc.perform(get("/api/v1/tenant/counter/queue").with(authentication(endUserAuth)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Customer endpoint (/api/v1/customer/**) requires END_USER role")
    void customerEndpointsSecurityRules() throws Exception {
        AbstractAuthenticationToken endUserAuth = authenticationConverter.convert(
                createMockJwt("END_USER", "cust@gmail.com", "clinic1")
        );
        AbstractAuthenticationToken counterStaffAuth = authenticationConverter.convert(
                createMockJwt("COUNTER_STAFF", "staff@clinic1.com", "clinic1")
        );

        // END_USER -> 200 OK
        mockMvc.perform(get("/api/v1/customer/tickets").with(authentication(endUserAuth)))
                .andExpect(status().isOk())
                .andExpect(content().string("customer-tickets-ok"));

        // COUNTER_STAFF -> 403 Forbidden
        mockMvc.perform(get("/api/v1/customer/tickets").with(authentication(counterStaffAuth)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("TenantContextFilter should bind tenant_code during request execution in controller")
    void tenantContextFilterShouldBindTenantCode() throws Exception {
        AbstractAuthenticationToken auth = authenticationConverter.convert(
                createMockJwt("TENANT_ADMIN", "admin@hospital_a.com", "hospital_a")
        );

        mockMvc.perform(get("/api/v1/tenant/admin/check-context").with(authentication(auth)))
                .andExpect(status().isOk())
                .andExpect(content().string("tenant:hospital_a"));
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

    @RestController
    static class TestEndpointController {

        @GetMapping("/api/v1/public/ping")
        public ResponseEntity<String> publicPing() {
            return ResponseEntity.ok("public-ok");
        }

        @GetMapping("/api/v1/auth/login")
        public ResponseEntity<String> authLogin() {
            return ResponseEntity.ok("auth-ok");
        }

        @GetMapping("/api/v1/central/dashboard")
        public ResponseEntity<String> centralDashboard() {
            return ResponseEntity.ok("central-dashboard-ok");
        }

        @GetMapping("/api/v1/tenant/admin/settings")
        public ResponseEntity<String> tenantAdminSettings() {
            return ResponseEntity.ok("tenant-admin-settings-ok");
        }

        @GetMapping("/api/v1/tenant/admin/check-context")
        public ResponseEntity<String> checkContext() {
            String tenant = TenantContextHolder.getTenantCode();
            return ResponseEntity.ok("tenant:" + tenant);
        }

        @GetMapping("/api/v1/tenant/counter/queue")
        public ResponseEntity<String> tenantCounterQueue() {
            return ResponseEntity.ok("tenant-counter-queue-ok");
        }

        @GetMapping("/api/v1/customer/tickets")
        public ResponseEntity<String> customerTickets() {
            return ResponseEntity.ok("customer-tickets-ok");
        }
    }
}
