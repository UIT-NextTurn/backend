package com.nextturn.controller.queue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nextturn.config.CorsConfig;
import com.nextturn.config.RestClientConfig;
import com.nextturn.config.ScalarConfig;
import com.nextturn.dto.queue.CallNextTicketRequest;
import com.nextturn.dto.queue.LobbyDisplayResponse;
import com.nextturn.dto.queue.QueueBoardResponse;
import com.nextturn.dto.queue.ServeTicketRequest;
import com.nextturn.dto.queue.TicketTransitionRequest;
import com.nextturn.dto.ticket.TicketResponse;
import com.nextturn.enums.TicketCustomerType;
import com.nextturn.enums.TicketStatus;
import com.nextturn.exception.GlobalExceptionHandler;
import com.nextturn.exception.queue.QueueErrorCode;
import com.nextturn.exception.queue.QueueException;
import com.nextturn.infrastructure.multitenancy.TenantContextFilter;
import com.nextturn.infrastructure.security.TestSecurityConfig;
import com.nextturn.infrastructure.security.config.SecurityConfig;
import com.nextturn.infrastructure.security.keycloak.KeycloakJwtAuthenticationConverter;
import com.nextturn.infrastructure.security.keycloak.KeycloakJwtGrantedAuthoritiesConverter;
import com.nextturn.infrastructure.security.principal.CurrentUserArgumentResolver;
import com.nextturn.infrastructure.security.service.SecurityContextHelper;
import com.nextturn.service.queue.TenantQueueCallingService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Instant;
import java.time.LocalDate;
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
 * End-to-end MockMvc test runner for Counter Queue Calling FSM & Public Lobby Display
 * endpoints (REQ-QC-01 through REQ-QC-04).
 */
@WebMvcTest(controllers = {TenantQueueCallingController.class, PublicLobbyQueueController.class})
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
class QueueCallingFeatureManualTestPlanRunnerTest {

    private static final String TENANT_CODE = "clinic-alpha";

    @Autowired
    private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Autowired
    private KeycloakJwtAuthenticationConverter authenticationConverter;

    @MockitoBean
    private TenantQueueCallingService queueCallingService;

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
    @DisplayName("Phase 1 (REQ-QC-01): POST /api/v1/queue/call-next - Calls next priority ticket (RBAC enforced)")
    void tcQc01_CallNext_SuccessAndRbac() throws Exception {
        CallNextTicketRequest req = CallNextTicketRequest.builder()
                .serviceId("srv-001")
                .counterLabel("Quầy 01")
                .build();

        TicketResponse called = TicketResponse.builder()
                .id("tkt-1")
                .ticketCode("A-001")
                .customerType(TicketCustomerType.APPOINTMENT)
                .status(TicketStatus.CALLING)
                .counterLabel("Quầy 01")
                .build();

        when(queueCallingService.callNextTicket(any(), any())).thenReturn(called);

        mockMvc.perform(post("/api/v1/queue/call-next")
                        .with(authentication(authWithRole("COUNTER_STAFF")))
                        .header("X-Tenant-Code", TENANT_CODE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.ticketCode").value("A-001"))
                .andExpect(jsonPath("$.data.status").value("CALLING"));

        // END_USER forbidden
        mockMvc.perform(post("/api/v1/queue/call-next")
                        .with(authentication(authWithRole("END_USER")))
                        .header("X-Tenant-Code", TENANT_CODE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isForbidden());

        // Unauthenticated -> 401
        mockMvc.perform(post("/api/v1/queue/call-next")
                        .header("X-Tenant-Code", TENANT_CODE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @Order(2)
    @DisplayName("Phase 2 (REQ-QC-02 & 04): Recall, Serve (with 409 version conflict), Complete, No-Show")
    void tcQc02_LifecycleTransitions() throws Exception {
        when(queueCallingService.recallTicket(eq("tkt-1"), any(), any()))
                .thenReturn(TicketResponse.builder().id("tkt-1").ticketCode("A-001").recallCount(1).status(TicketStatus.CALLING).build());

        mockMvc.perform(post("/api/v1/queue/tickets/tkt-1/recall")
                        .with(authentication(authWithRole("COUNTER_STAFF")))
                        .header("X-Tenant-Code", TENANT_CODE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.recallCount").value(1));

        when(queueCallingService.serveTicket(eq("tkt-1"), any(), any()))
                .thenReturn(TicketResponse.builder().id("tkt-1").status(TicketStatus.SERVING).providerId("spec-1").build())
                .thenThrow(new QueueException(QueueErrorCode.VERSION_CONFLICT));

        mockMvc.perform(post("/api/v1/queue/tickets/tkt-1/serve")
                        .with(authentication(authWithRole("COUNTER_STAFF")))
                        .header("X-Tenant-Code", TENANT_CODE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(ServeTicketRequest.builder().expectedVersion(0).build())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SERVING"));

        mockMvc.perform(post("/api/v1/queue/tickets/tkt-1/serve")
                        .with(authentication(authWithRole("COUNTER_STAFF")))
                        .header("X-Tenant-Code", TENANT_CODE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(ServeTicketRequest.builder().expectedVersion(0).build())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("QUEUE_012"));

        when(queueCallingService.completeTicket(eq("tkt-1"), any(), any()))
                .thenReturn(TicketResponse.builder().id("tkt-1").status(TicketStatus.COMPLETED).actualDuration(420).build());

        mockMvc.perform(post("/api/v1/queue/tickets/tkt-1/complete")
                        .with(authentication(authWithRole("TENANT_ADMIN")))
                        .header("X-Tenant-Code", TENANT_CODE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(TicketTransitionRequest.builder().expectedVersion(1).build())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("COMPLETED"));

        when(queueCallingService.markNoShow(eq("tkt-2"), any(), any()))
                .thenReturn(TicketResponse.builder().id("tkt-2").status(TicketStatus.NO_SHOW).build());

        mockMvc.perform(post("/api/v1/queue/tickets/tkt-2/no-show")
                        .with(authentication(authWithRole("COUNTER_STAFF")))
                        .header("X-Tenant-Code", TENANT_CODE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("NO_SHOW"));
    }

    @Test
    @Order(3)
    @DisplayName("Phase 3: Kanban board & Public Lobby TV snapshot + SSE stream")
    void tcQc03_BoardAndPublicLobbyDisplay() throws Exception {
        when(queueCallingService.getQueueBoard("srv-001"))
                .thenReturn(QueueBoardResponse.builder().workDate(LocalDate.now()).serviceId("srv-001").waitingTickets(List.of()).build());

        mockMvc.perform(get("/api/v1/queue/board")
                        .with(authentication(authWithRole("COUNTER_STAFF")))
                        .header("X-Tenant-Code", TENANT_CODE)
                        .param("serviceId", "srv-001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.serviceId").value("srv-001"));

        when(queueCallingService.getLobbyDisplay())
                .thenReturn(LobbyDisplayResponse.builder().tenantCode(TENANT_CODE).callingTickets(List.of()).servingTickets(List.of()).build());

        mockMvc.perform(get("/api/v1/public/queue/display")
                        .header("X-Tenant-Code", TENANT_CODE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.tenantCode").value(TENANT_CODE));

        SseEmitter emitter = new SseEmitter(1000L);
        emitter.complete();
        when(queueCallingService.subscribeToLobbyDisplay()).thenReturn(emitter);

        mockMvc.perform(get("/api/v1/public/queue/display/stream")
                        .header("X-Tenant-Code", TENANT_CODE))
                .andExpect(status().isOk());
    }
}
