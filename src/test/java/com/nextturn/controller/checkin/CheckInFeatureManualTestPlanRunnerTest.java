package com.nextturn.controller.checkin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nextturn.config.CorsConfig;
import com.nextturn.config.RestClientConfig;
import com.nextturn.config.ScalarConfig;
import com.nextturn.dto.checkin.AppointmentCheckInRequest;
import com.nextturn.dto.checkin.CreateReceptionPointRequest;
import com.nextturn.dto.checkin.ReceptionPointResponse;
import com.nextturn.dto.checkin.ReceptionQrResponse;
import com.nextturn.dto.checkin.ResolveReceptionQrResponse;
import com.nextturn.dto.checkin.UpdateReceptionPointStatusRequest;
import com.nextturn.dto.checkin.WalkInCheckInRequest;
import com.nextturn.dto.ticket.TicketResponse;
import com.nextturn.enums.ReceptionPointStatus;
import com.nextturn.enums.ReceptionQrMode;
import com.nextturn.enums.TicketCustomerType;
import com.nextturn.enums.TicketStatus;
import com.nextturn.exception.GlobalExceptionHandler;
import com.nextturn.exception.checkin.CheckInErrorCode;
import com.nextturn.exception.checkin.CheckInException;
import com.nextturn.exception.ticket.TicketErrorCode;
import com.nextturn.exception.ticket.TicketException;
import com.nextturn.infrastructure.multitenancy.TenantContextFilter;
import com.nextturn.infrastructure.security.TestSecurityConfig;
import com.nextturn.infrastructure.security.config.SecurityConfig;
import com.nextturn.infrastructure.security.keycloak.KeycloakJwtAuthenticationConverter;
import com.nextturn.infrastructure.security.keycloak.KeycloakJwtGrantedAuthoritiesConverter;
import com.nextturn.infrastructure.security.principal.CurrentUserArgumentResolver;
import com.nextturn.infrastructure.security.service.SecurityContextHelper;
import com.nextturn.service.checkin.TenantCheckInService;
import com.nextturn.service.checkin.TenantReceptionPointService;
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

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end automated MockMvc test runner for all Check-in endpoints (REQ-CHECK-IN-01..03).
 */
@WebMvcTest(controllers = {TenantCheckInController.class, PublicCheckInController.class})
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
class CheckInFeatureManualTestPlanRunnerTest {

    private static final String TENANT_CODE = "clinic-alpha";

    @Autowired
    private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Autowired
    private KeycloakJwtAuthenticationConverter authenticationConverter;

    @MockitoBean
    private TenantReceptionPointService receptionPointService;

    @MockitoBean
    private TenantCheckInService checkInService;

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
    @DisplayName("Phase 1 (REQ-CHECK-IN-03): Admin creates reception point (403 for COUNTER_STAFF)")
    void tcCheckIn01_CreateReceptionPoint_RbacAndSuccess() throws Exception {
        CreateReceptionPointRequest req = CreateReceptionPointRequest.builder()
                .pointCode("REC-MAIN-01")
                .pointName("Quầy Tiếp Đón Chính")
                .qrMode(ReceptionQrMode.DYNAMIC)
                .rotationSeconds(60)
                .build();

        ReceptionPointResponse resp = ReceptionPointResponse.builder()
                .id("rp-001")
                .pointCode("REC-MAIN-01")
                .pointName("Quầy Tiếp Đón Chính")
                .qrMode(ReceptionQrMode.DYNAMIC)
                .rotationSeconds(60)
                .status(ReceptionPointStatus.ACTIVE)
                .build();

        when(receptionPointService.createReceptionPoint(any())).thenReturn(resp);

        mockMvc.perform(post("/api/v1/check-in/reception-points")
                        .with(authentication(authWithRole("TENANT_ADMIN")))
                        .header("X-Tenant-Code", TENANT_CODE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.pointCode").value("REC-MAIN-01"))
                .andExpect(jsonPath("$.data.rotationSeconds").value(60));

        // COUNTER_STAFF cannot create reception points
        mockMvc.perform(post("/api/v1/check-in/reception-points")
                        .with(authentication(authWithRole("COUNTER_STAFF")))
                        .header("X-Tenant-Code", TENANT_CODE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isForbidden());
    }

    @Test
    @Order(2)
    @DisplayName("Phase 2 (REQ-CHECK-IN-03): List reception points & fetch 60s rotating dynamic QR")
    void tcCheckIn02_ListAndGetQr_Success() throws Exception {
        ReceptionPointResponse point = ReceptionPointResponse.builder()
                .id("rp-001")
                .pointCode("REC-MAIN-01")
                .qrMode(ReceptionQrMode.DYNAMIC)
                .status(ReceptionPointStatus.ACTIVE)
                .build();
        ReceptionQrResponse qr = ReceptionQrResponse.builder()
                .receptionPointId("rp-001")
                .pointCode("REC-MAIN-01")
                .tenantCode(TENANT_CODE)
                .qrMode(ReceptionQrMode.DYNAMIC)
                .qrToken("NTRD.abc.sig")
                .rotationSeconds(60)
                .remainingSeconds(48L)
                .build();

        when(receptionPointService.getAllReceptionPoints()).thenReturn(List.of(point));
        when(receptionPointService.getReceptionQr("rp-001")).thenReturn(qr);

        mockMvc.perform(get("/api/v1/check-in/reception-points")
                        .with(authentication(authWithRole("COUNTER_STAFF")))
                        .header("X-Tenant-Code", TENANT_CODE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].pointCode").value("REC-MAIN-01"));

        mockMvc.perform(get("/api/v1/check-in/reception-points/rp-001/qr")
                        .with(authentication(authWithRole("COUNTER_STAFF")))
                        .header("X-Tenant-Code", TENANT_CODE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.qrToken").value("NTRD.abc.sig"))
                .andExpect(jsonPath("$.data.rotationSeconds").value(60))
                .andExpect(jsonPath("$.data.remainingSeconds").value(48));
    }

    @Test
    @Order(3)
    @DisplayName("Phase 3 (REQ-CHECK-IN-03): Rotate static QR & update status (TENANT_ADMIN)")
    void tcCheckIn03_RotateStaticAndUpdateStatus_Success() throws Exception {
        when(receptionPointService.rotateStaticQr("rp-001"))
                .thenReturn(ReceptionQrResponse.builder()
                        .receptionPointId("rp-001")
                        .qrMode(ReceptionQrMode.STATIC)
                        .qrToken("NTRS.newtoken")
                        .build());

        when(receptionPointService.updateStatus(eq("rp-001"), any()))
                .thenReturn(ReceptionPointResponse.builder()
                        .id("rp-001")
                        .status(ReceptionPointStatus.INACTIVE)
                        .build());

        mockMvc.perform(post("/api/v1/check-in/reception-points/rp-001/rotate-static-qr")
                        .with(authentication(authWithRole("TENANT_ADMIN")))
                        .header("X-Tenant-Code", TENANT_CODE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.qrToken").value("NTRS.newtoken"));

        mockMvc.perform(patch("/api/v1/check-in/reception-points/rp-001/status")
                        .with(authentication(authWithRole("TENANT_ADMIN")))
                        .header("X-Tenant-Code", TENANT_CODE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                UpdateReceptionPointStatusRequest.builder()
                                        .status(ReceptionPointStatus.INACTIVE)
                                        .build())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("INACTIVE"));
    }

    @Test
    @Order(4)
    @DisplayName("Phase 4 (REQ-CHECK-IN-03): Public QR resolve returns context & rejects expired 60s token")
    void tcCheckIn04_PublicResolveQr_SuccessAndExpired() throws Exception {
        when(checkInService.resolveReceptionQr("NTRD.valid"))
                .thenReturn(ResolveReceptionQrResponse.builder()
                        .tenantCode(TENANT_CODE)
                        .pointCode("REC-MAIN-01")
                        .qrMode(ReceptionQrMode.DYNAMIC)
                        .supportsAppointmentCheckIn(true)
                        .supportsWalkIn(true)
                        .build());

        mockMvc.perform(get("/api/v1/public/check-in/resolve")
                        .param("token", "NTRD.valid"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.tenantCode").value(TENANT_CODE))
                .andExpect(jsonPath("$.data.pointCode").value("REC-MAIN-01"));

        when(checkInService.resolveReceptionQr("NTRD.expired"))
                .thenThrow(new CheckInException(CheckInErrorCode.RECEPTION_QR_EXPIRED));

        mockMvc.perform(get("/api/v1/public/check-in/resolve")
                        .param("token", "NTRD.expired"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("CHECKIN_005"));
    }

    @Test
    @Order(5)
    @DisplayName("Phase 5 (REQ-CHECK-IN-01 & 02): Public appointment & walk-in check-in endpoints")
    void tcCheckIn05_AppointmentAndWalkInCheckIn_SuccessAndCapacityFull() throws Exception {
        when(checkInService.checkInAppointment(any(AppointmentCheckInRequest.class)))
                .thenReturn(TicketResponse.builder()
                        .ticketCode("A-005")
                        .customerType(TicketCustomerType.APPOINTMENT)
                        .status(TicketStatus.WAITING)
                        .receptionPointId("rp-001")
                        .build());

        mockMvc.perform(post("/api/v1/public/check-in/appointment")
                        .header("X-Tenant-Code", TENANT_CODE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(AppointmentCheckInRequest.builder()
                                .qrToken("NTQR-APT-1")
                                .receptionQrToken("NTRD.valid")
                                .build())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.ticketCode").value("A-005"))
                .andExpect(jsonPath("$.data.customerType").value("APPOINTMENT"));

        when(checkInService.checkInWalkIn(any(WalkInCheckInRequest.class)))
                .thenReturn(TicketResponse.builder()
                        .ticketCode("A-006")
                        .customerType(TicketCustomerType.WALK_IN)
                        .status(TicketStatus.WAITING)
                        .build())
                .thenThrow(new TicketException(TicketErrorCode.WALK_IN_CAPACITY_FULL));

        WalkInCheckInRequest walkInReq = WalkInCheckInRequest.builder()
                .serviceId("srv-001")
                .customerPhone("0901234567")
                .customerName("Khách Vãng Lai")
                .receptionQrToken("NTRD.valid")
                .build();

        mockMvc.perform(post("/api/v1/public/check-in/walk-in")
                        .header("X-Tenant-Code", TENANT_CODE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(walkInReq)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.ticketCode").value("A-006"))
                .andExpect(jsonPath("$.data.customerType").value("WALK_IN"));

        mockMvc.perform(post("/api/v1/public/check-in/walk-in")
                        .header("X-Tenant-Code", TENANT_CODE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(walkInReq)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("TICKET_011"));
    }
}
