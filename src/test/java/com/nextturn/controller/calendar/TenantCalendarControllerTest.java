package com.nextturn.controller.calendar;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nextturn.config.CorsConfig;
import com.nextturn.config.ScalarConfig;
import com.nextturn.dto.calendar.CalendarSettingsRequest;
import com.nextturn.dto.calendar.CalendarSettingsResponse;
import com.nextturn.dto.calendar.ConfirmSlotRequest;
import com.nextturn.dto.calendar.CreateShiftRequest;
import com.nextturn.dto.calendar.HoldSlotRequest;
import com.nextturn.dto.calendar.ReleaseSlotRequest;
import com.nextturn.dto.calendar.ShiftResponse;
import com.nextturn.dto.calendar.SlotHoldingResponse;
import com.nextturn.dto.calendar.TimeSlotResponse;
import com.nextturn.dto.calendar.UpdateShiftRequest;
import com.nextturn.enums.ShiftStatus;
import com.nextturn.enums.TimeSlotStatus;
import com.nextturn.exception.GlobalExceptionHandler;
import com.nextturn.exception.calendar.CalendarErrorCode;
import com.nextturn.exception.calendar.CalendarException;
import com.nextturn.infrastructure.multitenancy.TenantContextFilter;
import com.nextturn.infrastructure.security.TestSecurityConfig;
import com.nextturn.infrastructure.security.config.SecurityConfig;
import com.nextturn.infrastructure.security.keycloak.KeycloakJwtAuthenticationConverter;
import com.nextturn.infrastructure.security.keycloak.KeycloakJwtGrantedAuthoritiesConverter;
import com.nextturn.infrastructure.security.principal.CurrentUserArgumentResolver;
import com.nextturn.infrastructure.security.service.SecurityContextHelper;
import com.nextturn.service.calendar.TenantCalendarService;
import com.nextturn.service.calendar.TenantCalendarSettingsService;
import com.nextturn.service.calendar.TenantTimeSlotHoldingService;
import com.nextturn.service.calendar.TenantTimeSlotQueryService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(TenantCalendarController.class)
@EnableMethodSecurity(prePostEnabled = true)
@Import({
        ScalarConfig.class,
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
class TenantCalendarControllerTest {

    @Autowired
    private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Autowired
    private KeycloakJwtAuthenticationConverter authenticationConverter;

    @MockitoBean
    private TenantCalendarService calendarService;

    @MockitoBean
    private TenantTimeSlotQueryService timeSlotQueryService;

    @MockitoBean
    private TenantTimeSlotHoldingService timeSlotHoldingService;

    @MockitoBean
    private TenantCalendarSettingsService calendarSettingsService;

    @Test
    @DisplayName("POST /api/v1/calendar/shifts: should create shift and return 201 Created for TENANT_ADMIN")
    void shouldCreateShiftWhenTenantAdmin() throws Exception {
        LocalDate tomorrow = LocalDate.now().plusDays(1);
        CreateShiftRequest request = CreateShiftRequest.builder()
                .staffId("staff-uuid-1")
                .serviceId("srv-uuid-1")
                .workDate(tomorrow)
                .shiftStart(LocalTime.of(8, 0))
                .shiftEnd(LocalTime.of(12, 0))
                .build();

        ShiftResponse shiftResp = ShiftResponse.builder()
                .id("shift-uuid-1")
                .staffId("staff-uuid-1")
                .staffCode("STAFF-0001")
                .staffFullName("Nguyễn Văn A")
                .serviceId("srv-uuid-1")
                .serviceName("Khám tổng quát")
                .workDate(tomorrow)
                .shiftStart(LocalTime.of(8, 0))
                .shiftEnd(LocalTime.of(12, 0))
                .status(ShiftStatus.SCHEDULED)
                .createdAt(LocalDateTime.now())
                .build();

        when(calendarService.createShifts(any(CreateShiftRequest.class))).thenReturn(List.of(shiftResp));

        AbstractAuthenticationToken adminAuth = authenticationConverter.convert(
                createMockJwt("TENANT_ADMIN", "admin@tenant.com", "tenant-alpha")
        );

        mockMvc.perform(post("/api/v1/calendar/shifts")
                        .with(authentication(adminAuth))
                        .header("X-Tenant-Code", "tenant-alpha")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value(201))
                .andExpect(jsonPath("$.data[0].id").value("shift-uuid-1"))
                .andExpect(jsonPath("$.data[0].staffFullName").value("Nguyễn Văn A"));
    }

    @Test
    @DisplayName("POST /api/v1/calendar/shifts: should return 409 Conflict when overlapping shift exists")
    void shouldReturn409WhenShiftOverlaps() throws Exception {
        LocalDate tomorrow = LocalDate.now().plusDays(1);
        CreateShiftRequest request = CreateShiftRequest.builder()
                .staffId("staff-uuid-1")
                .serviceId("srv-uuid-1")
                .workDate(tomorrow)
                .shiftStart(LocalTime.of(8, 0))
                .shiftEnd(LocalTime.of(12, 0))
                .build();

        String conflictMsg = "Nhân viên đã được phân công ca trực khác từ 08:00 đến 12:00 trong ngày này. Vui lòng kiểm tra lại.";
        when(calendarService.createShifts(any(CreateShiftRequest.class)))
                .thenThrow(new CalendarException(CalendarErrorCode.SHIFT_OVERLAPPING, conflictMsg));

        AbstractAuthenticationToken adminAuth = authenticationConverter.convert(
                createMockJwt("TENANT_ADMIN", "admin@tenant.com", "tenant-alpha")
        );

        mockMvc.perform(post("/api/v1/calendar/shifts")
                        .with(authentication(adminAuth))
                        .header("X-Tenant-Code", "tenant-alpha")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.errorCode").value("CAL_010"))
                .andExpect(jsonPath("$.message").value(conflictMsg));
    }

    @Test
    @DisplayName("PUT /api/v1/calendar/shifts/{shiftId}: should update shift successfully")
    void shouldUpdateShiftSuccessfully() throws Exception {
        UpdateShiftRequest request = UpdateShiftRequest.builder()
                .shiftStart(LocalTime.of(9, 0))
                .shiftEnd(LocalTime.of(13, 0))
                .build();

        ShiftResponse shiftResp = ShiftResponse.builder()
                .id("shift-1")
                .shiftStart(LocalTime.of(9, 0))
                .shiftEnd(LocalTime.of(13, 0))
                .status(ShiftStatus.SCHEDULED)
                .build();

        when(calendarService.updateShift(eq("shift-1"), any(UpdateShiftRequest.class))).thenReturn(shiftResp);

        AbstractAuthenticationToken adminAuth = authenticationConverter.convert(
                createMockJwt("TENANT_ADMIN", "admin@tenant.com", "tenant-alpha")
        );

        mockMvc.perform(put("/api/v1/calendar/shifts/shift-1")
                        .with(authentication(adminAuth))
                        .header("X-Tenant-Code", "tenant-alpha")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(200))
                .andExpect(jsonPath("$.data.shiftStart").value("09:00:00"));
    }

    @Test
    @DisplayName("DELETE /api/v1/calendar/shifts/{shiftId}: should cancel shift successfully")
    void shouldCancelShiftWhenTenantAdmin() throws Exception {
        ShiftResponse shiftResp = ShiftResponse.builder()
                .id("shift-uuid-1")
                .status(ShiftStatus.CANCELLED)
                .build();

        when(calendarService.cancelShift("shift-uuid-1")).thenReturn(shiftResp);

        AbstractAuthenticationToken adminAuth = authenticationConverter.convert(
                createMockJwt("TENANT_ADMIN", "admin@tenant.com", "tenant-alpha")
        );

        mockMvc.perform(delete("/api/v1/calendar/shifts/shift-uuid-1")
                        .with(authentication(adminAuth))
                        .header("X-Tenant-Code", "tenant-alpha"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(200))
                .andExpect(jsonPath("$.data.status").value("CANCELLED"));
    }

    @Test
    @DisplayName("GET /api/v1/calendar/slots: should return available slots")
    void shouldGetAvailableSlots() throws Exception {
        LocalDate tomorrow = LocalDate.now().plusDays(1);
        TimeSlotResponse slot = TimeSlotResponse.builder()
                .id("slot-1")
                .serviceId("srv-1")
                .serviceName("Khám tổng quát")
                .workDate(tomorrow)
                .slotStart(LocalTime.of(8, 0))
                .slotEnd(LocalTime.of(8, 30))
                .capacityPerSlot(2)
                .bookedCount(0)
                .availableCount(2)
                .status(TimeSlotStatus.AVAILABLE)
                .build();

        when(timeSlotQueryService.getAvailableSlots(eq("srv-1"), eq(tomorrow))).thenReturn(List.of(slot));

        AbstractAuthenticationToken adminAuth = authenticationConverter.convert(
                createMockJwt("TENANT_ADMIN", "admin@tenant.com", "tenant-alpha")
        );

        mockMvc.perform(get("/api/v1/calendar/slots")
                        .with(authentication(adminAuth))
                        .header("X-Tenant-Code", "tenant-alpha")
                        .param("serviceId", "srv-1")
                        .param("workDate", tomorrow.toString())
                        .param("availableOnly", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(200))
                .andExpect(jsonPath("$.data[0].id").value("slot-1"))
                .andExpect(jsonPath("$.data[0].capacityPerSlot").value(2))
                .andExpect(jsonPath("$.data[0].status").value("AVAILABLE"));
    }

    @Test
    @DisplayName("POST /api/v1/calendar/slots/{slotId}/hold: should hold slot successfully")
    void shouldHoldSlotSuccessfully() throws Exception {
        SlotHoldingResponse holdingResponse = SlotHoldingResponse.builder()
                .slotId("slot-1")
                .holdingToken("hold-tok-123")
                .holdingExpiresAt(LocalDateTime.now().plusMinutes(10))
                .status(TimeSlotStatus.HOLDING)
                .message("Khung giờ đã được giữ chỗ tạm thời trong 10 phút")
                .build();

        when(timeSlotHoldingService.holdSlot(eq("slot-1"), eq(10))).thenReturn(holdingResponse);

        AbstractAuthenticationToken adminAuth = authenticationConverter.convert(
                createMockJwt("TENANT_ADMIN", "admin@tenant.com", "tenant-alpha")
        );

        mockMvc.perform(post("/api/v1/calendar/slots/slot-1/hold")
                        .with(authentication(adminAuth))
                        .header("X-Tenant-Code", "tenant-alpha")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new HoldSlotRequest(10))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(200))
                .andExpect(jsonPath("$.data.holdingToken").value("hold-tok-123"))
                .andExpect(jsonPath("$.data.status").value("HOLDING"));
    }

    @Test
    @DisplayName("POST /api/v1/calendar/slots/{slotId}/release: should release slot successfully")
    void shouldReleaseSlotSuccessfully() throws Exception {
        ReleaseSlotRequest request = new ReleaseSlotRequest("hold-tok-123");

        AbstractAuthenticationToken adminAuth = authenticationConverter.convert(
                createMockJwt("TENANT_ADMIN", "admin@tenant.com", "tenant-alpha")
        );

        mockMvc.perform(post("/api/v1/calendar/slots/slot-1/release")
                        .with(authentication(adminAuth))
                        .header("X-Tenant-Code", "tenant-alpha")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(200));

        verify(timeSlotHoldingService).releaseHeldSlot("slot-1", "hold-tok-123");
    }

    @Test
    @DisplayName("POST /api/v1/calendar/slots/{slotId}/confirm: should confirm slot successfully")
    void shouldConfirmSlotSuccessfully() throws Exception {
        ConfirmSlotRequest request = new ConfirmSlotRequest("hold-tok-123");

        AbstractAuthenticationToken adminAuth = authenticationConverter.convert(
                createMockJwt("TENANT_ADMIN", "admin@tenant.com", "tenant-alpha")
        );

        mockMvc.perform(post("/api/v1/calendar/slots/slot-1/confirm")
                        .with(authentication(adminAuth))
                        .header("X-Tenant-Code", "tenant-alpha")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(200));

        verify(timeSlotHoldingService).confirmSlot("slot-1", "hold-tok-123");
    }

    @Test
    @DisplayName("GET /api/v1/calendar/settings: should return facility booking settings")
    void shouldGetSettingsSuccessfully() throws Exception {
        CalendarSettingsResponse settingsResponse = CalendarSettingsResponse.builder()
                .id("settings-default")
                .minLeadTimeHours(2)
                .maxAdvanceBookingDays(30)
                .cancellationDeadlineHours(12)
                .build();

        when(calendarSettingsService.getSettings()).thenReturn(settingsResponse);

        AbstractAuthenticationToken adminAuth = authenticationConverter.convert(
                createMockJwt("TENANT_ADMIN", "admin@tenant.com", "tenant-alpha")
        );

        mockMvc.perform(get("/api/v1/calendar/settings")
                        .with(authentication(adminAuth))
                        .header("X-Tenant-Code", "tenant-alpha"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(200))
                .andExpect(jsonPath("$.data.minLeadTimeHours").value(2))
                .andExpect(jsonPath("$.data.maxAdvanceBookingDays").value(30));
    }

    @Test
    @DisplayName("PUT /api/v1/calendar/settings: should update facility booking settings")
    void shouldUpdateSettingsSuccessfully() throws Exception {
        CalendarSettingsRequest request = CalendarSettingsRequest.builder()
                .minLeadTimeHours(4)
                .maxAdvanceBookingDays(14)
                .cancellationDeadlineHours(6)
                .build();

        CalendarSettingsResponse response = CalendarSettingsResponse.builder()
                .id("settings-default")
                .minLeadTimeHours(4)
                .maxAdvanceBookingDays(14)
                .cancellationDeadlineHours(6)
                .build();

        when(calendarSettingsService.updateSettings(any(CalendarSettingsRequest.class))).thenReturn(response);

        AbstractAuthenticationToken adminAuth = authenticationConverter.convert(
                createMockJwt("TENANT_ADMIN", "admin@tenant.com", "tenant-alpha")
        );

        mockMvc.perform(put("/api/v1/calendar/settings")
                        .with(authentication(adminAuth))
                        .header("X-Tenant-Code", "tenant-alpha")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(200))
                .andExpect(jsonPath("$.data.minLeadTimeHours").value(4))
                .andExpect(jsonPath("$.data.maxAdvanceBookingDays").value(14));
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
}
