package com.nextturn.controller.feedback;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nextturn.config.CorsConfig;
import com.nextturn.config.RestClientConfig;
import com.nextturn.config.ScalarConfig;
import com.nextturn.dto.feedback.FeedbackFormInitResponse;
import com.nextturn.dto.feedback.FeedbackResponse;
import com.nextturn.dto.feedback.FeedbackSummaryResponse;
import com.nextturn.dto.feedback.SubmitFeedbackRequest;
import com.nextturn.exception.GlobalExceptionHandler;
import com.nextturn.exception.feedback.FeedbackErrorCode;
import com.nextturn.exception.feedback.FeedbackException;
import com.nextturn.infrastructure.multitenancy.TenantContextFilter;
import com.nextturn.infrastructure.security.TestSecurityConfig;
import com.nextturn.infrastructure.security.config.SecurityConfig;
import com.nextturn.infrastructure.security.keycloak.KeycloakJwtAuthenticationConverter;
import com.nextturn.infrastructure.security.keycloak.KeycloakJwtGrantedAuthoritiesConverter;
import com.nextturn.infrastructure.security.principal.CurrentUserArgumentResolver;
import com.nextturn.infrastructure.security.service.SecurityContextHelper;
import com.nextturn.service.feedback.TenantFeedbackService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end MockMvc test runner for Public Customer Feedback & Tenant Admin CSAT Analytics
 * endpoints (REQ-FEEDBACK-01 through REQ-FEEDBACK-04).
 */
@WebMvcTest(controllers = {PublicFeedbackController.class, TenantFeedbackController.class})
@EnableMethodSecurity(prePostEnabled = true)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
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
class FeedbackFeatureManualTestPlanRunnerTest {

    private static final String TENANT_CODE = "clinic-alpha";

    @Autowired
    private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Autowired
    private KeycloakJwtAuthenticationConverter authenticationConverter;

    @MockitoBean
    private TenantFeedbackService feedbackService;

    private AbstractAuthenticationToken authWithRole(String role) {
        Jwt jwt = Jwt.withTokenValue("mock-jwt-" + role)
                .header("alg", "RS256")
                .subject("user-" + role)
                .claim("tenant_code", TENANT_CODE)
                .claim("resource_access", Map.of("nextturn-backend", Map.of("roles", List.of(role))))
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600))
                .build();
        return authenticationConverter.convert(jwt);
    }

    @Test
    @Order(1)
    @DisplayName("Phase 1 (REQ-FEEDBACK-01/02): GET /api/v1/public/feedback/{token} loads form and handles expired/duplicate")
    void tcFb01_GetFeedbackForm_AndErrorStates() throws Exception {
        FeedbackFormInitResponse form = FeedbackFormInitResponse.builder()
                .feedbackToken("tok-123")
                .ticketId("tkt-1")
                .ticketCode("A-001")
                .serviceName("Khám tổng quát")
                .providerName("BS. Lê Hoa")
                .customerName("Nguyễn Văn An")
                .availableCriteriaTags(List.of("Thái độ phục vụ", "Thời gian chờ", "Không gian cơ sở"))
                .build();

        when(feedbackService.getFeedbackForm("tok-123")).thenReturn(form);
        when(feedbackService.getFeedbackForm("tok-expired"))
                .thenThrow(new FeedbackException(FeedbackErrorCode.FEEDBACK_TOKEN_EXPIRED));
        when(feedbackService.getFeedbackForm("tok-used"))
                .thenThrow(new FeedbackException(FeedbackErrorCode.FEEDBACK_ALREADY_SUBMITTED));

        mockMvc.perform(get("/api/v1/public/feedback/tok-123")
                        .param("tenantCode", TENANT_CODE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.ticketCode").value("A-001"))
                .andExpect(jsonPath("$.data.providerName").value("BS. Lê Hoa"));

        mockMvc.perform(get("/api/v1/public/feedback/tok-expired")
                        .param("tenantCode", TENANT_CODE))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.errorCode").value("FEEDBACK_002"));

        mockMvc.perform(get("/api/v1/public/feedback/tok-used")
                        .param("tenantCode", TENANT_CODE))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("FEEDBACK_003"));
    }

    @Test
    @Order(2)
    @DisplayName("Phase 2 (REQ-FEEDBACK-02): POST /api/v1/public/feedback/{token} submits CSAT rating and validates range 1-5")
    void tcFb02_SubmitFeedback_SuccessAndValidation() throws Exception {
        SubmitFeedbackRequest validReq = SubmitFeedbackRequest.builder()
                .ratingScore(5)
                .criteriaRatings(Map.of("Thái độ phục vụ", 5, "Thời gian chờ", 5))
                .comment("Rất hài lòng")
                .build();

        FeedbackResponse saved = FeedbackResponse.builder()
                .id("fb-1")
                .ticketCode("A-001")
                .ratingScore(5)
                .comment("Rất hài lòng")
                .negativeFlag(false)
                .createdAt(LocalDateTime.of(2026, 11, 23, 13, 10))
                .build();

        when(feedbackService.submitFeedback(eq("tok-123"), any())).thenReturn(saved);

        mockMvc.perform(post("/api/v1/public/feedback/tok-123")
                        .param("tenantCode", TENANT_CODE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validReq)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.ratingScore").value(5))
                .andExpect(jsonPath("$.data.negativeFlag").value(false));

        // Rating outside 1..5 -> 400 Bad Request
        SubmitFeedbackRequest invalidReq = SubmitFeedbackRequest.builder()
                .ratingScore(6)
                .build();

        mockMvc.perform(post("/api/v1/public/feedback/tok-123")
                        .param("tenantCode", TENANT_CODE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalidReq)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @Order(3)
    @DisplayName("Phase 3 (REQ-FEEDBACK-04): GET /api/v1/feedbacks filters complaints and enforces TENANT_ADMIN RBAC")
    void tcFb03_SearchFeedbacks_RbacAndNegativeFilter() throws Exception {
        FeedbackResponse negativeReview = FeedbackResponse.builder()
                .id("fb-2")
                .ticketCode("A-008")
                .ratingScore(2)
                .comment("Chờ hơi lâu")
                .negativeFlag(true)
                .build();

        when(feedbackService.searchFeedbacks(eq(null), eq(true), eq(null), eq(null), eq(null), eq(null), any()))
                .thenReturn(new PageImpl<>(List.of(negativeReview), PageRequest.of(0, 20), 1));

        mockMvc.perform(get("/api/v1/feedbacks")
                        .with(authentication(authWithRole("TENANT_ADMIN")))
                        .header("X-Tenant-Code", TENANT_CODE)
                        .param("negativeOnly", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].negativeFlag").value(true))
                .andExpect(jsonPath("$.data.content[0].ratingScore").value(2));

        // Counter staff cannot access admin feedback audit
        mockMvc.perform(get("/api/v1/feedbacks")
                        .with(authentication(authWithRole("COUNTER_STAFF")))
                        .header("X-Tenant-Code", TENANT_CODE))
                .andExpect(status().isForbidden());

        // Unauthenticated -> 401
        mockMvc.perform(get("/api/v1/feedbacks")
                        .header("X-Tenant-Code", TENANT_CODE))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @Order(4)
    @DisplayName("Phase 4 (REQ-FEEDBACK-03): GET /api/v1/feedbacks/summary returns CSAT percentage and star distribution")
    void tcFb04_GetFeedbackSummary_Success() throws Exception {
        FeedbackSummaryResponse summary = FeedbackSummaryResponse.builder()
                .totalFeedbacks(10L)
                .averageRating(4.4d)
                .csatPercentage(80.0d)
                .negativeCount(1L)
                .ratingDistribution(Map.of(1, 0L, 2, 1L, 3, 1L, 4, 2L, 5, 6L))
                .build();

        when(feedbackService.getFeedbackSummary(any(), any(), any(), any(), any(), any()))
                .thenReturn(summary);

        mockMvc.perform(get("/api/v1/feedbacks/summary")
                        .with(authentication(authWithRole("TENANT_ADMIN")))
                        .header("X-Tenant-Code", TENANT_CODE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.averageRating").value(4.4d))
                .andExpect(jsonPath("$.data.csatPercentage").value(80.0d))
                .andExpect(jsonPath("$.data.negativeCount").value(1));
    }
}
