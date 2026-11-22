package com.nextturn.controller.ticket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nextturn.config.CorsConfig;
import com.nextturn.config.RestClientConfig;
import com.nextturn.config.ScalarConfig;
import com.nextturn.dto.ticket.IssueTicketRequest;
import com.nextturn.dto.ticket.TicketResponse;
import com.nextturn.enums.TicketCustomerType;
import com.nextturn.enums.TicketStatus;
import com.nextturn.exception.GlobalExceptionHandler;
import com.nextturn.exception.ticket.TicketErrorCode;
import com.nextturn.exception.ticket.TicketException;
import com.nextturn.infrastructure.multitenancy.TenantContextFilter;
import com.nextturn.infrastructure.security.TestSecurityConfig;
import com.nextturn.infrastructure.security.config.SecurityConfig;
import com.nextturn.infrastructure.security.keycloak.KeycloakJwtAuthenticationConverter;
import com.nextturn.infrastructure.security.keycloak.KeycloakJwtGrantedAuthoritiesConverter;
import com.nextturn.infrastructure.security.principal.CurrentUserArgumentResolver;
import com.nextturn.infrastructure.security.service.SecurityContextHelper;
import com.nextturn.service.ticket.TenantTicketService;
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
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end automated Manual Test Plan Runner for Queue Ticket endpoints
 * covering REQ-TICKET-01 through REQ-TICKET-04.
 */
@WebMvcTest(controllers = {PublicTicketController.class, TenantTicketController.class})
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
class TicketFeatureManualTestPlanRunnerTest {

    private static final String TENANT_CODE = "clinic-alpha";

    @Autowired
    private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Autowired
    private KeycloakJwtAuthenticationConverter authenticationConverter;

    @MockitoBean
    private TenantTicketService ticketService;

    private AbstractAuthenticationToken staffAuth() {
        Jwt jwt = Jwt.withTokenValue("mock-staff-jwt")
                .header("alg", "RS256")
                .subject("staff-user-1")
                .claim("tenant_code", TENANT_CODE)
                .claim("resource_access", Map.of("nextturn-backend", Map.of("roles", List.of("TENANT_ADMIN"))))
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600))
                .build();
        return authenticationConverter.convert(jwt);
    }

    @Test
    @Order(1)
    @DisplayName("Phase 1 (REQ-TICKET-01): POST /api/v1/public/tickets - Issue monotonic unique ticket A-001 (and A-1000+)")
    void tcTicket01_IssueTicket_Success() throws Exception {
        TicketResponse response = TicketResponse.builder()
                .id("tkt-uuid-001")
                .ticketCode("A-001")
                .sequenceNumber(1)
                .workDate(LocalDate.now())
                .serviceId("srv-001")
                .serviceCode("SVC-0001")
                .serviceName("Khám Nội")
                .customerType(TicketCustomerType.WALK_IN)
                .customerPhone("0901234567")
                .status(TicketStatus.WAITING)
                .currentServingNumber("A-000")
                .queuePosition(1L)
                .peopleAheadCount(0L)
                .estimatedWaitMinutes(0L)
                .approachingTurnAlert(true)
                .issuedAt(LocalDateTime.now())
                .build();

        when(ticketService.issueTicket(any(IssueTicketRequest.class))).thenReturn(response);

        IssueTicketRequest request = IssueTicketRequest.builder()
                .serviceId("srv-001")
                .customerPhone("0901234567")
                .customerName("Nguyễn Văn Khách")
                .build();

        mockMvc.perform(post("/api/v1/public/tickets")
                        .header("X-Tenant-Code", TENANT_CODE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value(201))
                .andExpect(jsonPath("$.data.ticketCode").value("A-001"))
                .andExpect(jsonPath("$.data.status").value("WAITING"))
                .andExpect(jsonPath("$.data.queuePosition").value(1));
    }

    @Test
    @Order(2)
    @DisplayName("Phase 2 (REQ-TICKET-02): GET /api/v1/public/tickets/{ticketIdOrCode} - Live ticket waiting position & estimate")
    void tcTicket02_GetTicketDetails_Success() throws Exception {
        TicketResponse response = TicketResponse.builder()
                .id("tkt-uuid-024")
                .ticketCode("A-024")
                .sequenceNumber(24)
                .serviceName("Khám Nội")
                .status(TicketStatus.WAITING)
                .currentServingNumber("A-021")
                .queuePosition(3L)
                .peopleAheadCount(2L)
                .estimatedWaitMinutes(30L)
                .approachingTurnAlert(true)
                .build();

        when(ticketService.getTicketDetails("A-024")).thenReturn(response);

        mockMvc.perform(get("/api/v1/public/tickets/A-024")
                        .header("X-Tenant-Code", TENANT_CODE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.ticketCode").value("A-024"))
                .andExpect(jsonPath("$.data.currentServingNumber").value("A-021"))
                .andExpect(jsonPath("$.data.peopleAheadCount").value(2))
                .andExpect(jsonPath("$.data.approachingTurnAlert").value(true));
    }

    @Test
    @Order(3)
    @DisplayName("Phase 3 (REQ-TICKET-03): GET /api/v1/public/tickets/{ticketIdOrCode}/stream - Open real-time SSE stream")
    void tcTicket03_SubscribeTicketStream_Success() throws Exception {
        SseEmitter emitter = new SseEmitter(1000L);
        emitter.complete();
        when(ticketService.subscribeToTicketStream("A-024")).thenReturn(emitter);

        mockMvc.perform(get("/api/v1/public/tickets/A-024/stream")
                        .header("X-Tenant-Code", TENANT_CODE))
                .andExpect(status().isOk());
    }

    @Test
    @Order(4)
    @DisplayName("Phase 4 (REQ-TICKET-04): GET /api/v1/public/tickets/recover - Recover active ticket by phone today")
    void tcTicket04_RecoverTicket_SuccessAndNotFound() throws Exception {
        TicketResponse active = TicketResponse.builder()
                .id("tkt-uuid-024")
                .ticketCode("A-024")
                .customerPhone("0901234567")
                .status(TicketStatus.WAITING)
                .queuePosition(3L)
                .build();

        when(ticketService.recoverTicketsByPhoneToday("0901234567")).thenReturn(List.of(active));

        mockMvc.perform(get("/api/v1/public/tickets/recover")
                        .header("X-Tenant-Code", TENANT_CODE)
                        .param("customerPhone", "0901234567"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].ticketCode").value("A-024"));

        when(ticketService.recoverTicketsByPhoneToday("0999888777"))
                .thenThrow(new TicketException(TicketErrorCode.NO_ACTIVE_TICKET_TODAY));

        mockMvc.perform(get("/api/v1/public/tickets/recover")
                        .header("X-Tenant-Code", TENANT_CODE)
                        .param("customerPhone", "0999888777"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("TICKET_009"))
                .andExpect(jsonPath("$.message").value("Không tìm thấy vé hàng đợi nào đang chờ phục vụ với số điện thoại này trong hôm nay."));
    }

    @Test
    @Order(5)
    @DisplayName("Phase 5: GET /api/v1/tickets - Authenticated admin/staff monitors today's tickets")
    void tcTicket05_AdminGetTicketsForDate_Success() throws Exception {
        TicketResponse ticket = TicketResponse.builder()
                .id("tkt-uuid-1000")
                .ticketCode("A-1000")
                .sequenceNumber(1000)
                .status(TicketStatus.WAITING)
                .build();

        when(ticketService.getTicketsForDate(null, null)).thenReturn(List.of(ticket));

        mockMvc.perform(get("/api/v1/tickets")
                        .with(authentication(staffAuth()))
                        .header("X-Tenant-Code", TENANT_CODE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].ticketCode").value("A-1000"));
    }

    @Test
    @Order(6)
    @DisplayName("Phase 6: GET /api/v1/tickets/{ticketIdOrCode} - Authenticated admin/staff retrieves ticket details (401 if unauthenticated)")
    void tcTicket06_AdminGetTicketByIdOrCode_SuccessAndUnauthorized() throws Exception {
        TicketResponse ticket = TicketResponse.builder()
                .id("tkt-uuid-1000")
                .ticketCode("A-1000")
                .sequenceNumber(1000)
                .status(TicketStatus.WAITING)
                .currentServingNumber("A-998")
                .queuePosition(2L)
                .build();

        when(ticketService.getTicketDetails("A-1000")).thenReturn(ticket);

        mockMvc.perform(get("/api/v1/tickets/A-1000")
                        .with(authentication(staffAuth()))
                        .header("X-Tenant-Code", TENANT_CODE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.ticketCode").value("A-1000"))
                .andExpect(jsonPath("$.data.currentServingNumber").value("A-998"));

        mockMvc.perform(get("/api/v1/tickets/A-1000")
                        .header("X-Tenant-Code", TENANT_CODE))
                .andExpect(status().isUnauthorized());
    }
}
