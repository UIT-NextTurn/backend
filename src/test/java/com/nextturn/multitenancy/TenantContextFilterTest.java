package com.nextturn.multitenancy;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.io.IOException;
import java.time.Instant;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TenantContextFilterTest {

    private TenantContextFilter filter;

    @BeforeEach
    void setUp() {
        filter = new TenantContextFilter();
        TenantContextHolder.clear();
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("Should extract 'tenant_code' from JWT and clear context in finally")
    void shouldExtractTenantCodeFromJwtAndClearInFinally() throws ServletException, IOException {
        Jwt jwt = Jwt.withTokenValue("mock-token")
                .header("alg", "RS256")
                .issuer("http://ryan-a1:8180/realms/nextturn")
                .subject("user-123")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300))
                .claim("tenant_code", "clinic_sai_gon")
                .build();

        SecurityContextHolder.getContext().setAuthentication(
                new JwtAuthenticationToken(jwt, Collections.emptyList(), "user-123")
        );

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/tenant/admin/overview");
        MockHttpServletResponse response = new MockHttpServletResponse();

        AtomicReference<String> capturedTenant = new AtomicReference<>();
        FilterChain chain = (req, res) -> {
            // Verify tenant is bound during request processing
            capturedTenant.set(TenantContextHolder.getTenantCode());
        };

        filter.doFilter(request, response, chain);

        // Within chain, tenant_code was present
        assertThat(capturedTenant.get()).isEqualTo("clinic_sai_gon");
        // After chain completes, TenantContextHolder was cleared in finally
        assertThat(TenantContextHolder.getTenantCode()).isNull();
        assertThat(TenantContextHolder.hasTenant()).isFalse();
    }

    @Test
    @DisplayName("Should clear TenantContextHolder even when downstream filter throws Exception")
    void shouldClearTenantContextOnException() {
        Jwt jwt = Jwt.withTokenValue("mock-token")
                .header("alg", "RS256")
                .issuer("http://ryan-a1:8180/realms/nextturn")
                .subject("user-123")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300))
                .claim("tenant_code", "hospital_hn")
                .build();

        SecurityContextHolder.getContext().setAuthentication(
                new JwtAuthenticationToken(jwt, Collections.emptyList(), "user-123")
        );

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/tenant/admin/error");
        MockHttpServletResponse response = new MockHttpServletResponse();

        FilterChain throwingChain = (req, res) -> {
            assertThat(TenantContextHolder.getTenantCode()).isEqualTo("hospital_hn");
            throw new RuntimeException("Simulated downstream error");
        };

        assertThatThrownBy(() -> filter.doFilter(request, response, throwingChain))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("Simulated downstream error");

        // Despite exception, finally block must have cleared the context
        assertThat(TenantContextHolder.getTenantCode()).isNull();
    }

    @Test
    @DisplayName("Should fallback to 'tenant_id' when 'tenant_code' is not present in JWT")
    void shouldFallbackToTenantIdClaim() throws ServletException, IOException {
        Jwt jwt = Jwt.withTokenValue("mock-token")
                .header("alg", "RS256")
                .issuer("http://ryan-a1:8180/realms/nextturn")
                .subject("user-456")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300))
                .claim("tenant_id", "fallback_tenant_456")
                .build();

        SecurityContextHolder.getContext().setAuthentication(
                new JwtAuthenticationToken(jwt, Collections.emptyList(), "user-456")
        );

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/test");
        MockHttpServletResponse response = new MockHttpServletResponse();

        AtomicReference<String> captured = new AtomicReference<>();
        filter.doFilter(request, response, (req, res) -> captured.set(TenantContextHolder.getTenantCode()));

        assertThat(captured.get()).isEqualTo("fallback_tenant_456");
        assertThat(TenantContextHolder.getTenantCode()).isNull();
    }

    @Test
    @DisplayName("Should fallback to X-Tenant-Code HTTP header when no JWT claim is present")
    void shouldFallbackToHttpHeader() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/public/services");
        request.addHeader("X-Tenant-Code", "header_tenant_xyz");
        MockHttpServletResponse response = new MockHttpServletResponse();

        AtomicReference<String> captured = new AtomicReference<>();
        filter.doFilter(request, response, (req, res) -> captured.set(TenantContextHolder.getTenantCode()));

        assertThat(captured.get()).isEqualTo("header_tenant_xyz");
        assertThat(TenantContextHolder.getTenantCode()).isNull();
    }
}
