package com.nextturn.controller.dashboard;

import com.nextturn.config.CorsConfig;
import com.nextturn.config.RestClientConfig;
import com.nextturn.config.ScalarConfig;
import com.nextturn.dto.dashboard.CancellationNoShowAnalyticsResponse;
import com.nextturn.dto.dashboard.FeedbackTrendAnalyticsResponse;
import com.nextturn.dto.dashboard.PlatformDashboardOverviewResponse;
import com.nextturn.dto.dashboard.ServicePerformanceAnalyticsResponse;
import com.nextturn.dto.dashboard.TenantDashboardOverviewResponse;
import com.nextturn.dto.dashboard.TenantQuotaMonitoringResponse;
import com.nextturn.enums.QuotaMonitorFilter;
import com.nextturn.exception.GlobalExceptionHandler;
import com.nextturn.exception.dashboard.DashboardErrorCode;
import com.nextturn.exception.dashboard.DashboardException;
import com.nextturn.infrastructure.multitenancy.TenantContextFilter;
import com.nextturn.infrastructure.security.TestSecurityConfig;
import com.nextturn.infrastructure.security.config.SecurityConfig;
import com.nextturn.infrastructure.security.keycloak.KeycloakJwtAuthenticationConverter;
import com.nextturn.infrastructure.security.keycloak.KeycloakJwtGrantedAuthoritiesConverter;
import com.nextturn.infrastructure.security.principal.CurrentUserArgumentResolver;
import com.nextturn.infrastructure.security.service.SecurityContextHelper;
import com.nextturn.service.dashboard.DashboardExportService;
import com.nextturn.service.dashboard.DashboardExportService.ExportedReportFile;
import com.nextturn.service.dashboard.DashboardSseBroadcaster;
import com.nextturn.service.dashboard.PlatformDashboardService;
import com.nextturn.service.dashboard.TenantDashboardService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = {TenantDashboardController.class, PlatformDashboardController.class})
@EnableMethodSecurity(prePostEnabled = true)
@Import({
        ScalarConfig.class,
        RestClientConfig.class,
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
class DashboardFeatureManualTestPlanRunnerTest {

    private static final String TENANT_CODE = "clinic-alpha";

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
                .claim("resource_access", Map.of("nextturn-backend", Map.of("roles", List.of(role))))
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600));
        if (tenantCode != null) {
            builder.claim("tenant_code", tenantCode);
        }
        return authenticationConverter.convert(builder.build());
    }

    @Test
    @DisplayName("RBAC: Unauthenticated returns 401; COUNTER_STAFF rejected on tenant dashboard; TENANT_ADMIN rejected on central dashboard")
    void enforceRbacAcrossTenantAndPlatformDashboards() throws Exception {
        mockMvc.perform(get("/api/v1/dashboard/overview").header("X-Tenant-Code", TENANT_CODE))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/v1/dashboard/overview")
                        .with(authentication(authWithRole("COUNTER_STAFF", TENANT_CODE)))
                        .header("X-Tenant-Code", TENANT_CODE))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/v1/central/dashboard/overview")
                        .with(authentication(authWithRole("TENANT_ADMIN", TENANT_CODE))))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("REQ-DASHBOARD-01..04: Tenant Admin can fetch overview, analytics, SSE stream, and CSV export")
    void tenantAdminDashboardEndpointsReturnExpectedPayloads() throws Exception {
        when(tenantDashboardService.getOverview()).thenReturn(TenantDashboardOverviewResponse.builder()
                .workDate(LocalDate.of(2026, 11, 23))
                .totalTickets(25L)
                .completedTickets(18L)
                .noShowRate(4.0d)
                .noShowSeverity("GREEN")
                .avgCsatRating(4.7d)
                .build());

        mockMvc.perform(get("/api/v1/dashboard/overview")
                        .with(authentication(authWithRole("TENANT_ADMIN", TENANT_CODE))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalTickets").value(25))
                .andExpect(jsonPath("$.data.noShowSeverity").value("GREEN"));

        when(tenantDashboardService.getCancellationAndNoShowAnalytics(any(), any(), any(), any(), any()))
                .thenReturn(CancellationNoShowAnalyticsResponse.builder().totalTickets(25L).noShowRate(4.0d).build());
        mockMvc.perform(get("/api/v1/dashboard/analytics/cancellations-noshows?preset=LAST_7_DAYS")
                        .with(authentication(authWithRole("TENANT_ADMIN", TENANT_CODE))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalTickets").value(25));

        when(tenantDashboardService.getServicePerformanceAnalytics(any(), any(), any(), any(), any()))
                .thenReturn(ServicePerformanceAnalyticsResponse.builder().overallAvgServeMinutes(12L).build());
        mockMvc.perform(get("/api/v1/dashboard/analytics/service-performance?preset=THIS_MONTH")
                        .with(authentication(authWithRole("TENANT_ADMIN", TENANT_CODE))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.overallAvgServeMinutes").value(12));

        when(tenantDashboardService.getFeedbackTrends(any(), any(), any(), any(), any()))
                .thenReturn(FeedbackTrendAnalyticsResponse.builder().csatPercentage(92.5d).build());
        mockMvc.perform(get("/api/v1/dashboard/analytics/feedback-trends")
                        .with(authentication(authWithRole("TENANT_ADMIN", TENANT_CODE))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.csatPercentage").value(92.5));

        when(exportService.exportOperationalReport(any(), any(), any(), any(), any(), any()))
                .thenReturn(new ExportedReportFile("report.csv", "text/csv; charset=UTF-8", "col\nval".getBytes(StandardCharsets.UTF_8)));
        mockMvc.perform(get("/api/v1/dashboard/export?format=CSV")
                        .with(authentication(authWithRole("TENANT_ADMIN", TENANT_CODE))))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"report.csv\""));

        SseEmitter emitter = new SseEmitter(0L);
        emitter.complete();
        when(sseBroadcaster.subscribeTenantOverview(TENANT_CODE)).thenReturn(emitter);
        mockMvc.perform(get("/api/v1/dashboard/overview/stream")
                        .with(authentication(authWithRole("TENANT_ADMIN", TENANT_CODE))))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("REQ-DASHBOARD-05..06 & Errors: Superadmin platform overview, quotas, and domain error codes")
    void superadminPlatformEndpointsAndErrorHandling() throws Exception {
        when(platformDashboardService.getPlatformOverview(30)).thenReturn(PlatformDashboardOverviewResponse.builder()
                .activeTenants(10L)
                .totalPlatformTicketsToday(320L)
                .trafficHistoryDays(30)
                .build());

        mockMvc.perform(get("/api/v1/central/dashboard/overview?days=30")
                        .with(authentication(authWithRole("SUPERADMIN", null))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.activeTenants").value(10))
                .andExpect(jsonPath("$.data.totalPlatformTicketsToday").value(320));

        when(platformDashboardService.getTenantQuotaMonitoring(QuotaMonitorFilter.TICKET_EXCEEDED))
                .thenReturn(TenantQuotaMonitoringResponse.builder().criticalCount(2L).build());
        mockMvc.perform(get("/api/v1/central/dashboard/quotas?filter=TICKET_EXCEEDED")
                        .with(authentication(authWithRole("SUPERADMIN", null))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.criticalCount").value(2));

        when(platformDashboardService.getPlatformOverview(15))
                .thenThrow(new DashboardException(DashboardErrorCode.INVALID_FILTER_PARAMETER));
        mockMvc.perform(get("/api/v1/central/dashboard/overview?days=15")
                        .with(authentication(authWithRole("SUPERADMIN", null))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("DASH_004"));
    }
}
