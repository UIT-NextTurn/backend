package com.nextturn.controller.calendar;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nextturn.config.CorsConfig;
import com.nextturn.config.ScalarConfig;
import com.nextturn.dto.calendar.CalendarSettingsResponse;
import com.nextturn.dto.calendar.ConfirmSlotRequest;
import com.nextturn.dto.calendar.HoldSlotRequest;
import com.nextturn.dto.calendar.ReleaseSlotRequest;
import com.nextturn.dto.calendar.SlotHoldingResponse;
import com.nextturn.dto.calendar.TimeSlotResponse;
import com.nextturn.enums.TimeSlotStatus;
import com.nextturn.exception.GlobalExceptionHandler;
import com.nextturn.infrastructure.multitenancy.TenantContextFilter;
import com.nextturn.infrastructure.security.TestSecurityConfig;
import com.nextturn.infrastructure.security.config.SecurityConfig;
import com.nextturn.infrastructure.security.keycloak.KeycloakJwtAuthenticationConverter;
import com.nextturn.infrastructure.security.keycloak.KeycloakJwtGrantedAuthoritiesConverter;
import com.nextturn.infrastructure.security.principal.CurrentUserArgumentResolver;
import com.nextturn.infrastructure.security.service.SecurityContextHelper;
import com.nextturn.service.calendar.TenantCalendarSettingsService;
import com.nextturn.service.calendar.TenantTimeSlotHoldingService;
import com.nextturn.service.calendar.TenantTimeSlotQueryService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(PublicCalendarController.class)
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
class PublicCalendarControllerTest {

    @Autowired
    private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @MockitoBean
    private TenantTimeSlotQueryService timeSlotQueryService;

    @MockitoBean
    private TenantTimeSlotHoldingService timeSlotHoldingService;

    @MockitoBean
    private TenantCalendarSettingsService calendarSettingsService;

    @Test
    @DisplayName("GET /api/v1/public/calendar/slots: should return available slots publicly")
    void shouldGetPublicSlots() throws Exception {
        LocalDate tomorrow = LocalDate.now().plusDays(1);
        TimeSlotResponse slot = TimeSlotResponse.builder()
                .id("slot-1")
                .serviceId("srv-1")
                .serviceName("Khám tổng quát")
                .workDate(tomorrow)
                .slotStart(LocalTime.of(9, 0))
                .slotEnd(LocalTime.of(9, 30))
                .capacityPerSlot(1)
                .bookedCount(0)
                .availableCount(1)
                .status(TimeSlotStatus.AVAILABLE)
                .build();

        when(timeSlotQueryService.getAvailableSlots(eq("srv-1"), eq(tomorrow))).thenReturn(List.of(slot));

        mockMvc.perform(get("/api/v1/public/calendar/slots")
                        .header("X-Tenant-Code", "tenant-alpha")
                        .param("serviceId", "srv-1")
                        .param("workDate", tomorrow.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(200))
                .andExpect(jsonPath("$.data[0].id").value("slot-1"))
                .andExpect(jsonPath("$.data[0].status").value("AVAILABLE"));
    }

    @Test
    @DisplayName("POST /api/v1/public/calendar/slots/{slotId}/hold: should hold slot without requiring auth token")
    void shouldHoldSlotPublicly() throws Exception {
        SlotHoldingResponse holdingResponse = SlotHoldingResponse.builder()
                .slotId("slot-1")
                .holdingToken("tok-pub-123")
                .holdingExpiresAt(LocalDateTime.now().plusMinutes(10))
                .status(TimeSlotStatus.HOLDING)
                .message("Khung giờ đã được giữ chỗ tạm thời trong 10 phút")
                .build();

        when(timeSlotHoldingService.holdSlot(eq("slot-1"), eq(10))).thenReturn(holdingResponse);

        mockMvc.perform(post("/api/v1/public/calendar/slots/slot-1/hold")
                        .header("X-Tenant-Code", "tenant-alpha")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new HoldSlotRequest(10))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(200))
                .andExpect(jsonPath("$.data.holdingToken").value("tok-pub-123"))
                .andExpect(jsonPath("$.data.status").value("HOLDING"));
    }

    @Test
    @DisplayName("POST /api/v1/public/calendar/slots/{slotId}/release: should release slot publicly")
    void shouldReleaseSlotPublicly() throws Exception {
        ReleaseSlotRequest request = new ReleaseSlotRequest("tok-pub-123");

        mockMvc.perform(post("/api/v1/public/calendar/slots/slot-1/release")
                        .header("X-Tenant-Code", "tenant-alpha")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(200));

        verify(timeSlotHoldingService).releaseHeldSlot("slot-1", "tok-pub-123");
    }

    @Test
    @DisplayName("POST /api/v1/public/calendar/slots/{slotId}/confirm: should confirm slot publicly")
    void shouldConfirmSlotPublicly() throws Exception {
        ConfirmSlotRequest request = new ConfirmSlotRequest("tok-pub-123");

        mockMvc.perform(post("/api/v1/public/calendar/slots/slot-1/confirm")
                        .header("X-Tenant-Code", "tenant-alpha")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(200));

        verify(timeSlotHoldingService).confirmSlot("slot-1", "tok-pub-123");
    }

    @Test
    @DisplayName("GET /api/v1/public/calendar/settings: should get facility settings publicly")
    void shouldGetPublicSettings() throws Exception {
        CalendarSettingsResponse response = CalendarSettingsResponse.builder()
                .id("settings-default")
                .minLeadTimeHours(2)
                .maxAdvanceBookingDays(30)
                .cancellationDeadlineHours(12)
                .build();

        when(calendarSettingsService.getSettings()).thenReturn(response);

        mockMvc.perform(get("/api/v1/public/calendar/settings")
                        .header("X-Tenant-Code", "tenant-alpha"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(200))
                .andExpect(jsonPath("$.data.minLeadTimeHours").value(2))
                .andExpect(jsonPath("$.data.maxAdvanceBookingDays").value(30));
    }
}

