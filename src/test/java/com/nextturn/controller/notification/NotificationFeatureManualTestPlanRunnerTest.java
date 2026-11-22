package com.nextturn.controller.notification;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nextturn.config.CorsConfig;
import com.nextturn.config.RestClientConfig;
import com.nextturn.config.ScalarConfig;
import com.nextturn.dto.notification.NotificationLogResponse;
import com.nextturn.dto.notification.NotificationTemplateResponse;
import com.nextturn.dto.notification.ReminderDispatchSummaryResponse;
import com.nextturn.dto.notification.UpsertNotificationTemplateRequest;
import com.nextturn.enums.NotificationChannel;
import com.nextturn.enums.NotificationDeliveryStatus;
import com.nextturn.exception.GlobalExceptionHandler;
import com.nextturn.infrastructure.multitenancy.TenantContextFilter;
import com.nextturn.infrastructure.security.TestSecurityConfig;
import com.nextturn.infrastructure.security.config.SecurityConfig;
import com.nextturn.infrastructure.security.keycloak.KeycloakJwtAuthenticationConverter;
import com.nextturn.infrastructure.security.keycloak.KeycloakJwtGrantedAuthoritiesConverter;
import com.nextturn.infrastructure.security.principal.CurrentUserArgumentResolver;
import com.nextturn.infrastructure.security.service.SecurityContextHelper;
import com.nextturn.service.notification.TenantAppointmentReminderService;
import com.nextturn.service.notification.TenantNotificationLogService;
import com.nextturn.service.notification.TenantNotificationTemplateService;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end MockMvc test suite for Tenant Notification Template customization,
 * Delivery Log audit queries, and Appointment Reminder trigger endpoints (REQ-NOTI-01, REQ-NOTI-04).
 */
@WebMvcTest(controllers = TenantNotificationController.class)
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
class NotificationFeatureManualTestPlanRunnerTest {

    private static final String TENANT_CODE = "clinic-alpha";

    @Autowired
    private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Autowired
    private KeycloakJwtAuthenticationConverter authenticationConverter;

    @MockitoBean
    private TenantNotificationTemplateService templateService;

    @MockitoBean
    private TenantNotificationLogService logService;

    @MockitoBean
    private TenantAppointmentReminderService reminderService;

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
    @DisplayName("Phase 1 (REQ-NOTI-04): GET /api/v1/notifications/templates enforces TENANT_ADMIN RBAC")
    void tcNoti01_GetTemplates_RbacAndResponse() throws Exception {
        NotificationTemplateResponse template = NotificationTemplateResponse.builder()
                .templateCode("APT_REMINDER_24H")
                .templateName("Nhắc lịch hẹn trước 24 giờ")
                .channel(NotificationChannel.EMAIL)
                .bodyTemplate("Xin chào {customer_name}, lịch {service_name} lúc {appointment_time}")
                .isActive(true)
                .customized(false)
                .build();

        when(templateService.getAllTemplates()).thenReturn(List.of(template));

        mockMvc.perform(get("/api/v1/notifications/templates")
                        .with(authentication(authWithRole("TENANT_ADMIN")))
                        .header("X-Tenant-Code", TENANT_CODE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].templateCode").value("APT_REMINDER_24H"))
                .andExpect(jsonPath("$.data[0].channel").value("EMAIL"));

        // Non-admin roles receive 403 Forbidden
        mockMvc.perform(get("/api/v1/notifications/templates")
                        .with(authentication(authWithRole("COUNTER_STAFF")))
                        .header("X-Tenant-Code", TENANT_CODE))
                .andExpect(status().isForbidden());

        // Unauthenticated receives 401 Unauthorized
        mockMvc.perform(get("/api/v1/notifications/templates")
                        .header("X-Tenant-Code", TENANT_CODE))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @Order(2)
    @DisplayName("Phase 2 (REQ-NOTI-04): PUT /api/v1/notifications/templates/{code} customizes placeholder template")
    void tcNoti02_UpsertTemplate_Success() throws Exception {
        UpsertNotificationTemplateRequest req = UpsertNotificationTemplateRequest.builder()
                .templateName("Mời khách vào quầy")
                .channel(NotificationChannel.PUSH)
                .subjectTemplate("Số vé {ticket_code}")
                .bodyTemplate("Mời {customer_name} mang vé {ticket_code} gặp {counter_staff_name}")
                .isActive(true)
                .build();

        NotificationTemplateResponse saved = NotificationTemplateResponse.builder()
                .id("tpl-001")
                .templateCode("TICKET_CALLED_ALERT")
                .templateName("Mời khách vào quầy")
                .channel(NotificationChannel.PUSH)
                .bodyTemplate(req.getBodyTemplate())
                .isActive(true)
                .customized(true)
                .build();

        when(templateService.upsertTemplate(eq("TICKET_CALLED_ALERT"), any())).thenReturn(saved);

        mockMvc.perform(put("/api/v1/notifications/templates/TICKET_CALLED_ALERT")
                        .with(authentication(authWithRole("TENANT_ADMIN")))
                        .header("X-Tenant-Code", TENANT_CODE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.templateCode").value("TICKET_CALLED_ALERT"))
                .andExpect(jsonPath("$.data.customized").value(true));
    }

    @Test
    @Order(3)
    @DisplayName("Phase 3 (REQ-NOTI-04): GET /api/v1/notifications/logs searches delivery logs by ticketCode & status")
    void tcNoti03_SearchDeliveryLogs_Success() throws Exception {
        NotificationLogResponse entry = NotificationLogResponse.builder()
                .id("log-001")
                .ticketCode("A-012")
                .recipientContact("0909123456")
                .channel(NotificationChannel.PUSH)
                .templateCode("TICKET_CALLED_ALERT")
                .messagePayload("Mời vé A-012 đến Quầy 01")
                .status(NotificationDeliveryStatus.SUCCESS)
                .sentAt(LocalDateTime.of(2026, 11, 23, 9, 15))
                .build();

        when(logService.searchLogs(eq(null), eq("A-012"), eq(NotificationChannel.PUSH),
                eq(NotificationDeliveryStatus.SUCCESS), eq(null), any()))
                .thenReturn(new PageImpl<>(List.of(entry), PageRequest.of(0, 20), 1));

        mockMvc.perform(get("/api/v1/notifications/logs")
                        .with(authentication(authWithRole("TENANT_ADMIN")))
                        .header("X-Tenant-Code", TENANT_CODE)
                        .param("ticketCode", "A-012")
                        .param("channel", "PUSH")
                        .param("status", "SUCCESS"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].ticketCode").value("A-012"))
                .andExpect(jsonPath("$.data.content[0].status").value("SUCCESS"));
    }

    @Test
    @Order(4)
    @DisplayName("Phase 4 (REQ-NOTI-01): POST /api/v1/notifications/reminders/run executes appointment reminder sweep")
    void tcNoti04_TriggerReminderSweep_Success() throws Exception {
        ReminderDispatchSummaryResponse summary = ReminderDispatchSummaryResponse.builder()
                .reminders24hSent(3)
                .reminders1hSent(2)
                .failedCount(0)
                .build();

        when(reminderService.dispatchUpcomingReminders()).thenReturn(summary);

        mockMvc.perform(post("/api/v1/notifications/reminders/run")
                        .with(authentication(authWithRole("TENANT_ADMIN")))
                        .header("X-Tenant-Code", TENANT_CODE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.reminders24hSent").value(3))
                .andExpect(jsonPath("$.data.reminders1hSent").value(2))
                .andExpect(jsonPath("$.data.failedCount").value(0));
    }
}
