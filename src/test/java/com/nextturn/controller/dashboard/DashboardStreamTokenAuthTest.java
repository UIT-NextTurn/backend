package com.nextturn.controller.dashboard;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nextturn.config.CorsConfig;
import com.nextturn.config.RestClientConfig;
import com.nextturn.config.ScalarConfig;
import com.nextturn.exception.AppException;
import com.nextturn.exception.GlobalExceptionHandler;
import com.nextturn.exception.system.SystemErrorCode;
import com.nextturn.infrastructure.multitenancy.TenantContextFilter;
import com.nextturn.infrastructure.security.TestSecurityConfig;
import com.nextturn.infrastructure.security.config.SecurityConfig;
import com.nextturn.infrastructure.security.filter.DashboardStreamTokenFilter;
import com.nextturn.infrastructure.security.keycloak.KeycloakJwtAuthenticationConverter;
import com.nextturn.infrastructure.security.keycloak.KeycloakJwtGrantedAuthoritiesConverter;
import com.nextturn.infrastructure.security.principal.CurrentUserArgumentResolver;
import com.nextturn.infrastructure.security.service.SecurityContextHelper;
import com.nextturn.service.dashboard.DashboardExportService;
import com.nextturn.service.dashboard.DashboardSseBroadcaster;
import com.nextturn.service.dashboard.DashboardStreamTokenService.StreamScope;
import com.nextturn.service.dashboard.PlatformDashboardService;
import com.nextturn.service.dashboard.TenantDashboardService;
import com.nextturn.service.dashboard.impl.DashboardStreamTokenServiceImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verifies P0 #5 (browser-compatible single-use stream token authentication for Dashboard SSE)
 * and P1 #10 (CORS preflight permitting X-Tenant-Code and X-Widget-Key with credentials).
 */
@WebMvcTest(controllers = {TenantDashboardController.class, PlatformDashboardController.class})
@EnableMethodSecurity(prePostEnabled = true)
@Import({
        ScalarConfig.class,
        RestClientConfig.class,
        SecurityConfig.class,
        TestSecurityConfig.class,
        CorsConfig.class,
        TenantContextFilter.class,
        DashboardStreamTokenFilter.class,
        DashboardStreamTokenServiceImpl.class,
        KeycloakJwtAuthenticationConverter.class,
        KeycloakJwtGrantedAuthoritiesConverter.class,
        CurrentUserArgumentResolver.class,
        SecurityContextHelper.class,
        GlobalExceptionHandler.class
})
class DashboardStreamTokenAuthTest {

    private static final String TENANT_CODE = "clinic-alpha";
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private KeycloakJwtAuthenticationConverter authenticationConverter;

    @MockitoBean
    private TenantDashboardService tenantDashboardService;

    @MockitoBean
    private DashboardExportService exportService;

    @MockitoBean
    private PlatformDashboardService platformDashboardService;

    @MockitoBean
    private DashboardSseBroadcaster sseBroadcaster;

    private AbstractAuthenticationToken authWithRole(String role, String tenantCode) {
        Jwt.Builder builder = Jwt.withTokenValue("mock-jwt-" + role)
                .header("alg", "RS256")
                .subject("user-" + role)
                .claim("email", role.toLowerCase() + "@nextturn.vn")
                .claim("resource_access", Map.of("nextturn-backend", Map.of("roles", List.of(role))))
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600));
        if (tenantCode != null) {
            builder.claim("tenant_code", tenantCode);
        }
        return authenticationConverter.convert(builder.build());
    }

    @Test
    @DisplayName("P0 #5: Tenant Admin mints single-use streamToken and opens Dashboard SSE without Authorization or X-Tenant-Code headers; replay fails with 401")
    void tenantDashboardStream_SingleUseQueryTokenFlow() throws Exception {
        SseEmitter emitter = new SseEmitter(0L);
        emitter.complete();
        when(sseBroadcaster.subscribeTenantOverview(TENANT_CODE)).thenReturn(emitter);

        // 1. Mint short-lived stream token with authenticated POST
        MvcResult mintResult = mockMvc.perform(post("/api/v1/dashboard/overview/stream-token")
                        .with(authentication(authWithRole("TENANT_ADMIN", TENANT_CODE))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.streamToken").isNotEmpty())
                .andExpect(jsonPath("$.data.expiresInSeconds").value(30))
                .andExpect(jsonPath("$.data.scope").value("TENANT_DASHBOARD"))
                .andReturn();

        JsonNode dataNode = objectMapper.readTree(mintResult.getResponse().getContentAsString()).path("data");
        String streamToken = dataNode.path("streamToken").asText();
        assertThat(dataNode.path("streamUrl").asText())
                .isEqualTo("/api/v1/dashboard/overview/stream?streamToken=" + streamToken);

        // 2. Open SSE stream with ONLY ?streamToken=... (no Authorization header, no X-Tenant-Code header)
        mockMvc.perform(get("/api/v1/dashboard/overview/stream")
                        .param("streamToken", streamToken))
                .andExpect(status().isOk());

        verify(sseBroadcaster).subscribeTenantOverview(TENANT_CODE);

        // 3. Replaying the exact same single-use token must be rejected with 401
        mockMvc.perform(get("/api/v1/dashboard/overview/stream")
                        .param("streamToken", streamToken))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("P0 #5: Tenant stream token cannot open Platform Superadmin SSE stream (scope mismatch -> 401)")
    void tenantStreamToken_RejectedOnPlatformStream() throws Exception {
        MvcResult mintResult = mockMvc.perform(post("/api/v1/dashboard/overview/stream-token")
                        .with(authentication(authWithRole("TENANT_ADMIN", TENANT_CODE))))
                .andExpect(status().isOk())
                .andReturn();

        String streamToken = objectMapper.readTree(mintResult.getResponse().getContentAsString())
                .path("data").path("streamToken").asText();

        mockMvc.perform(get("/api/v1/central/dashboard/overview/stream")
                        .param("streamToken", streamToken))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("P0 #5: Superadmin mints single-use streamToken and opens Platform Dashboard SSE without Authorization header")
    void platformDashboardStream_SingleUseQueryTokenFlow() throws Exception {
        SseEmitter emitter = new SseEmitter(0L);
        emitter.complete();
        when(sseBroadcaster.subscribePlatformOverview()).thenReturn(emitter);

        MvcResult mintResult = mockMvc.perform(post("/api/v1/central/dashboard/overview/stream-token")
                        .with(authentication(authWithRole("SUPERADMIN", null))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.scope").value("PLATFORM_DASHBOARD"))
                .andReturn();

        String streamToken = objectMapper.readTree(mintResult.getResponse().getContentAsString())
                .path("data").path("streamToken").asText();

        mockMvc.perform(get("/api/v1/central/dashboard/overview/stream")
                        .param("streamToken", streamToken))
                .andExpect(status().isOk());

        verify(sseBroadcaster).subscribePlatformOverview();

        // Single-use replay rejected
        mockMvc.perform(get("/api/v1/central/dashboard/overview/stream")
                        .param("streamToken", streamToken))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("P0 #5: Expired stream token is rejected after its 30-second TTL")
    void streamTokenService_RejectsExpiredToken() {
        Instant issuedAt = Instant.parse("2026-11-28T10:00:00Z");
        Instant[] currentInstant = {issuedAt};
        Clock mutableClock = new Clock() {
            @Override
            public ZoneId getZone() {
                return ZoneId.of("UTC");
            }

            @Override
            public Clock withZone(ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return currentInstant[0];
            }
        };

        DashboardStreamTokenServiceImpl tokenService =
                new DashboardStreamTokenServiceImpl(null, objectMapper, mutableClock, 30L);

        var issued = tokenService.issueToken(
                null,
                List.of(new SimpleGrantedAuthority("ROLE_TENANT_ADMIN")),
                StreamScope.TENANT_DASHBOARD,
                TENANT_CODE
        );

        // Advance clock by 31 seconds -> expired
        currentInstant[0] = issuedAt.plusSeconds(31);

        assertThat(tokenService.consumeToken(issued.getStreamToken(), StreamScope.TENANT_DASHBOARD)).isEmpty();
    }

    @Test
    @DisplayName("P1 #10: CORS preflight allows X-Tenant-Code, X-Tenant-Id, X-Widget-Key, and credentials from frontend origin")
    void corsPreflight_AllowsTenantAndWidgetHeadersWithCredentials() throws Exception {
        mockMvc.perform(options("/api/v1/dashboard/overview")
                        .header(HttpHeaders.ORIGIN, "http://localhost:3000")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "Authorization, X-Tenant-Code, X-Widget-Key"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://localhost:3000"))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true"))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS,
                        org.hamcrest.Matchers.allOf(
                                org.hamcrest.Matchers.containsString("X-Tenant-Code"),
                                org.hamcrest.Matchers.containsString("X-Widget-Key"),
                                org.hamcrest.Matchers.containsString("Authorization")
                        )));
    }

    @Test
    @DisplayName("P1 #7 & #9: SSE endpoint forwards Last-Event-ID header and returns HTTP 429 (not 406) when tenant subscriber cap is reached")
    void dashboardStream_ForwardsLastEventIdAndReturns429OnCapExceeded() throws Exception {
        SseEmitter emitter = new SseEmitter(0L);
        emitter.complete();
        when(sseBroadcaster.subscribeTenantOverview(TENANT_CODE, "boot1234-5")).thenReturn(emitter);

        mockMvc.perform(get("/api/v1/dashboard/overview/stream")
                        .with(authentication(authWithRole("TENANT_ADMIN", TENANT_CODE)))
                        .header("Last-Event-ID", "boot1234-5"))
                .andExpect(status().isOk());

        verify(sseBroadcaster).subscribeTenantOverview(TENANT_CODE, "boot1234-5");

        // When per-tenant subscriber limit is reached on an Accept: text/event-stream request -> 429 Too Many Requests
        when(sseBroadcaster.subscribeTenantOverview(TENANT_CODE))
                .thenThrow(new AppException(SystemErrorCode.RATE_LIMIT_EXCEEDED, "Limit reached"));

        mockMvc.perform(get("/api/v1/dashboard/overview/stream")
                        .with(authentication(authWithRole("TENANT_ADMIN", TENANT_CODE)))
                        .header(HttpHeaders.ACCEPT, "text/event-stream"))
                .andExpect(status().isTooManyRequests());
    }
}
