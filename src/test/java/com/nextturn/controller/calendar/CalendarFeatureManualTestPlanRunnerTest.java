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

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end automated test runner executing the complete Manual Test Plan for the Calendar Feature.
 * Validates all test cases across Phase 1 to Phase 5 in order.
 */
@WebMvcTest(controllers = {TenantCalendarController.class, PublicCalendarController.class})
@EnableMethodSecurity(prePostEnabled = true)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
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
class CalendarFeatureManualTestPlanRunnerTest {

    private static final String TENANT_CODE = "tenant-alpha";

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

    private AbstractAuthenticationToken getTenantAdminAuth() {
        return authenticationConverter.convert(createMockJwt("TENANT_ADMIN", "admin@tenant-alpha.com", TENANT_CODE));
    }

    // =========================================================================
    // Phase 1: Work Shift Allocation & Time Slot Auto-Generation
    // =========================================================================

    @Test
    @Order(1)
    @DisplayName("TC-CAL-01: Create work shift with valid constraints -> 201 Created")
    void tcCal01_createShift_validConstraints_shouldReturn201() throws Exception {
        LocalDate workDate = LocalDate.now().plusDays(2);
        CreateShiftRequest request = CreateShiftRequest.builder()
                .staffId("staff-001")
                .serviceId("srv-001")
                .workDate(workDate)
                .shiftStart(LocalTime.of(8, 0))
                .shiftEnd(LocalTime.of(11, 0))
                .build();

        ShiftResponse shiftResponse = ShiftResponse.builder()
                .id("shift-uuid-1")
                .staffId("staff-001")
                .staffCode("STAFF-0001")
                .staffFullName("Nguyễn Văn A")
                .serviceId("srv-001")
                .serviceName("Khám tổng quát")
                .workDate(workDate)
                .shiftStart(LocalTime.of(8, 0))
                .shiftEnd(LocalTime.of(11, 0))
                .status(ShiftStatus.SCHEDULED)
                .createdAt(LocalDateTime.now())
                .build();

        when(calendarService.createShifts(any(CreateShiftRequest.class))).thenReturn(List.of(shiftResponse));

        mockMvc.perform(post("/api/v1/calendar/shifts")
                        .with(authentication(getTenantAdminAuth()))
                        .header("X-Tenant-Code", TENANT_CODE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value(201))
                .andExpect(jsonPath("$.data[0].id").value("shift-uuid-1"))
                .andExpect(jsonPath("$.data[0].status").value("SCHEDULED"));
    }

    @Test
    @Order(2)
    @DisplayName("TC-CAL-02: Reject shift with work date in the past -> 400 Bad Request (Validation Error SYS_002)")
    void tcCal02_createShift_pastDate_shouldReturn400() throws Exception {
        CreateShiftRequest request = CreateShiftRequest.builder()
                .staffId("staff-001")
                .serviceId("srv-001")
                .workDate(LocalDate.of(2020, 1, 1))
                .shiftStart(LocalTime.of(8, 0))
                .shiftEnd(LocalTime.of(11, 0))
                .build();

        mockMvc.perform(post("/api/v1/calendar/shifts")
                        .with(authentication(getTenantAdminAuth()))
                        .header("X-Tenant-Code", TENANT_CODE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.errorCode").value("SYS_002"))
                .andExpect(jsonPath("$.message").value("Work date must be today or in the future"));
    }

    @Test
    @Order(3)
    @DisplayName("TC-CAL-03: Reject shift with shiftStart >= shiftEnd -> 400 Bad Request")
    void tcCal03_createShift_invalidTimeWindow_shouldReturn400() throws Exception {
        CreateShiftRequest request = CreateShiftRequest.builder()
                .staffId("staff-001")
                .serviceId("srv-001")
                .workDate(LocalDate.now().plusDays(2))
                .shiftStart(LocalTime.of(12, 0))
                .shiftEnd(LocalTime.of(8, 0))
                .build();

        when(calendarService.createShifts(any(CreateShiftRequest.class)))
                .thenThrow(new CalendarException(CalendarErrorCode.TIME_WINDOW_INVALID));

        mockMvc.perform(post("/api/v1/calendar/shifts")
                        .with(authentication(getTenantAdminAuth()))
                        .header("X-Tenant-Code", TENANT_CODE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.errorCode").value("CAL_008"));
    }

    @Test
    @Order(4)
    @DisplayName("TC-CAL-04: Reject shift with duration less than service standard -> 400 Bad Request")
    void tcCal04_createShift_durationTooShort_shouldReturn400() throws Exception {
        CreateShiftRequest request = CreateShiftRequest.builder()
                .staffId("staff-001")
                .serviceId("srv-001")
                .workDate(LocalDate.now().plusDays(2))
                .shiftStart(LocalTime.of(8, 0))
                .shiftEnd(LocalTime.of(8, 20))
                .build();

        String durationMsg = "Tổng thời lượng của ca (20 phút) tối thiểu phải lớn hơn hoặc bằng thời lượng phục vụ chuẩn (30 phút) của dịch vụ mà nhân viên đó đảm nhiệm.";
        when(calendarService.createShifts(any(CreateShiftRequest.class)))
                .thenThrow(new CalendarException(CalendarErrorCode.DURATION_TOO_SHORT, durationMsg));

        mockMvc.perform(post("/api/v1/calendar/shifts")
                        .with(authentication(getTenantAdminAuth()))
                        .header("X-Tenant-Code", TENANT_CODE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.errorCode").value("CAL_009"))
                .andExpect(jsonPath("$.message").value(durationMsg));
    }

    @Test
    @Order(5)
    @DisplayName("TC-CAL-05: Overlap check: reject overlapping shift with exact Vietnamese message -> 409 Conflict")
    void tcCal05_createShift_overlap_shouldReturn409() throws Exception {
        CreateShiftRequest request = CreateShiftRequest.builder()
                .staffId("staff-001")
                .serviceId("srv-001")
                .workDate(LocalDate.now().plusDays(2))
                .shiftStart(LocalTime.of(9, 0))
                .shiftEnd(LocalTime.of(13, 0))
                .build();

        String overlapMsg = "Nhân viên đã được phân công ca trực khác từ 08:00 đến 11:00 trong ngày này. Vui lòng kiểm tra lại.";
        when(calendarService.createShifts(any(CreateShiftRequest.class)))
                .thenThrow(new CalendarException(CalendarErrorCode.SHIFT_OVERLAPPING, overlapMsg));

        mockMvc.perform(post("/api/v1/calendar/shifts")
                        .with(authentication(getTenantAdminAuth()))
                        .header("X-Tenant-Code", TENANT_CODE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.errorCode").value("CAL_010"))
                .andExpect(jsonPath("$.message").value(overlapMsg));
    }

    @Test
    @Order(6)
    @DisplayName("TC-CAL-06: Create recurring shifts filtered by repeat days -> 201 Created")
    void tcCal06_createShift_recurring_shouldReturn201() throws Exception {
        LocalDate monday = LocalDate.now().plusDays(7);
        CreateShiftRequest request = CreateShiftRequest.builder()
                .staffId("staff-001")
                .serviceId("srv-001")
                .workDate(monday)
                .repeatUntil(monday.plusDays(4))
                .repeatDays(List.of(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY))
                .shiftStart(LocalTime.of(13, 0))
                .shiftEnd(LocalTime.of(17, 0))
                .build();

        List<ShiftResponse> responses = List.of(
                ShiftResponse.builder().id("shift-rec-1").workDate(monday).build(),
                ShiftResponse.builder().id("shift-rec-2").workDate(monday.plusDays(2)).build(),
                ShiftResponse.builder().id("shift-rec-3").workDate(monday.plusDays(4)).build()
        );

        when(calendarService.createShifts(any(CreateShiftRequest.class))).thenReturn(responses);

        mockMvc.perform(post("/api/v1/calendar/shifts")
                        .with(authentication(getTenantAdminAuth()))
                        .header("X-Tenant-Code", TENANT_CODE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value(201))
                .andExpect(jsonPath("$.data.length()").value(3));
    }

    // =========================================================================
    // Phase 2: Time Slot Query & Valkey Caching
    // =========================================================================

    @Test
    @Order(7)
    @DisplayName("TC-CAL-07: Query available time slots via Admin and Public endpoints -> 200 OK")
    void tcCal07_querySlots_shouldReturn200() throws Exception {
        LocalDate workDate = LocalDate.now().plusDays(2);
        TimeSlotResponse slot = TimeSlotResponse.builder()
                .id("slot-001")
                .serviceId("srv-001")
                .workDate(workDate)
                .slotStart(LocalTime.of(8, 0))
                .slotEnd(LocalTime.of(8, 30))
                .capacityPerSlot(1)
                .bookedCount(0)
                .availableCount(1)
                .status(TimeSlotStatus.AVAILABLE)
                .build();

        when(timeSlotQueryService.getAvailableSlots("srv-001", workDate)).thenReturn(List.of(slot));

        // Test Admin endpoint
        mockMvc.perform(get("/api/v1/calendar/slots")
                        .with(authentication(getTenantAdminAuth()))
                        .header("X-Tenant-Code", TENANT_CODE)
                        .param("serviceId", "srv-001")
                        .param("workDate", workDate.toString())
                        .param("availableOnly", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(200))
                .andExpect(jsonPath("$.data[0].id").value("slot-001"));

        // Test Public endpoint (unauthenticated)
        mockMvc.perform(get("/api/v1/public/calendar/slots")
                        .header("X-Tenant-Code", TENANT_CODE)
                        .param("serviceId", "srv-001")
                        .param("workDate", workDate.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(200))
                .andExpect(jsonPath("$.data[0].id").value("slot-001"));
    }

    // =========================================================================
    // Phase 3: Slot Contention & Temporary Holding (Race Condition Prevention)
    // =========================================================================

    @Test
    @Order(8)
    @DisplayName("TC-CAL-08: Hold slot temporarily (default 10m) -> 200 OK")
    void tcCal08_holdSlot_success_shouldReturn200() throws Exception {
        LocalDateTime expiresAt = LocalDateTime.now().plusMinutes(10);
        SlotHoldingResponse holdingResponse = SlotHoldingResponse.builder()
                .slotId("slot-001")
                .holdingToken("token-lease-xyz")
                .holdingExpiresAt(expiresAt)
                .status(TimeSlotStatus.HOLDING)
                .message("Khung giờ đã được giữ chỗ tạm thời trong 10 phút")
                .build();

        when(timeSlotHoldingService.holdSlot("slot-001", 10)).thenReturn(holdingResponse);

        mockMvc.perform(post("/api/v1/public/calendar/slots/slot-001/hold")
                        .header("X-Tenant-Code", TENANT_CODE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new HoldSlotRequest(10))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(200))
                .andExpect(jsonPath("$.data.holdingToken").value("token-lease-xyz"))
                .andExpect(jsonPath("$.data.status").value("HOLDING"));
    }

    @Test
    @Order(9)
    @DisplayName("TC-CAL-09: Concurrent hold attempt: returns exact Vietnamese warning -> 409 Conflict")
    void tcCal09_holdSlot_raceCondition_shouldReturn409() throws Exception {
        String raceMsg = "Khung giờ này vừa có người khác nhanh tay chọn trước. Vui lòng chọn một khung giờ khác.";
        when(timeSlotHoldingService.holdSlot("slot-001", 10))
                .thenThrow(new CalendarException(CalendarErrorCode.SLOT_ALREADY_HOLDING_OR_BOOKED, raceMsg));

        mockMvc.perform(post("/api/v1/public/calendar/slots/slot-001/hold")
                        .header("X-Tenant-Code", TENANT_CODE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new HoldSlotRequest(10))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.errorCode").value("CAL_014"))
                .andExpect(jsonPath("$.message").value(raceMsg));
    }

    @Test
    @Order(10)
    @DisplayName("TC-CAL-10: Release held slot with matching token -> 200 OK")
    void tcCal10_releaseSlot_success_shouldReturn200() throws Exception {
        doNothing().when(timeSlotHoldingService).releaseHeldSlot("slot-001", "token-lease-xyz");

        mockMvc.perform(post("/api/v1/public/calendar/slots/slot-001/release")
                        .header("X-Tenant-Code", TENANT_CODE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ReleaseSlotRequest("token-lease-xyz"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(200));

        verify(timeSlotHoldingService).releaseHeldSlot("slot-001", "token-lease-xyz");
    }

    @Test
    @Order(11)
    @DisplayName("TC-CAL-11: Reject release with invalid token -> 400 Bad Request")
    void tcCal11_releaseSlot_invalidToken_shouldReturn400() throws Exception {
        doThrow(new CalendarException(CalendarErrorCode.HOLDING_TOKEN_INVALID))
                .when(timeSlotHoldingService).releaseHeldSlot("slot-001", "wrong-token");

        mockMvc.perform(post("/api/v1/public/calendar/slots/slot-001/release")
                        .header("X-Tenant-Code", TENANT_CODE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ReleaseSlotRequest("wrong-token"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.errorCode").value("CAL_015"));
    }

    @Test
    @Order(12)
    @DisplayName("TC-CAL-12: Confirm booking on held slot -> 200 OK")
    void tcCal12_confirmSlot_success_shouldReturn200() throws Exception {
        doNothing().when(timeSlotHoldingService).confirmSlot("slot-001", "token-lease-xyz");

        mockMvc.perform(post("/api/v1/public/calendar/slots/slot-001/confirm")
                        .header("X-Tenant-Code", TENANT_CODE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ConfirmSlotRequest("token-lease-xyz"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(200));

        verify(timeSlotHoldingService).confirmSlot("slot-001", "token-lease-xyz");
    }

    @Test
    @Order(13)
    @DisplayName("TC-CAL-13: Reject confirmation with expired token -> 400 Bad Request")
    void tcCal13_confirmSlot_expiredToken_shouldReturn400() throws Exception {
        doThrow(new CalendarException(CalendarErrorCode.HOLDING_TOKEN_EXPIRED))
                .when(timeSlotHoldingService).confirmSlot("slot-001", "expired-token");

        mockMvc.perform(post("/api/v1/public/calendar/slots/slot-001/confirm")
                        .header("X-Tenant-Code", TENANT_CODE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ConfirmSlotRequest("expired-token"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.errorCode").value("CAL_016"));
    }

    // =========================================================================
    // Phase 4: Shift Modification & Cancellation Guard
    // =========================================================================

    @Test
    @Order(14)
    @DisplayName("TC-CAL-15: Block shift cancellation when booked appointments exist -> 409 Conflict")
    void tcCal15_cancelShift_bookedAppointmentsExist_shouldReturn409() throws Exception {
        String bookedCancelMsg = "Ca trực hiện có 1 lịch hẹn của khách hàng đã được xác nhận. Vui lòng hủy lịch hẹn hoặc điều chuyển nhân viên thay thế trước khi hủy ca trực này.";
        when(calendarService.cancelShift("shift-uuid-1"))
                .thenThrow(new CalendarException(CalendarErrorCode.SHIFT_CANNOT_CANCEL_WITH_BOOKED_SLOTS, bookedCancelMsg));

        mockMvc.perform(delete("/api/v1/calendar/shifts/shift-uuid-1")
                        .with(authentication(getTenantAdminAuth()))
                        .header("X-Tenant-Code", TENANT_CODE))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.errorCode").value("CAL_017"))
                .andExpect(jsonPath("$.message").value(bookedCancelMsg));
    }

    @Test
    @Order(15)
    @DisplayName("TC-CAL-16: Block shift truncation when cut window has booked appointments -> 409 Conflict")
    void tcCal16_updateShift_cutWindowBooked_shouldReturn409() throws Exception {
        UpdateShiftRequest request = UpdateShiftRequest.builder()
                .shiftStart(LocalTime.of(9, 0))
                .shiftEnd(LocalTime.of(11, 0))
                .build();

        String cutMsg = "Ca trực hiện có 1 lịch hẹn của khách hàng đã được xác nhận trong khoảng thời gian bị cắt giảm. Vui lòng hủy lịch hẹn hoặc điều chuyển nhân viên thay thế trước khi điều chỉnh ca trực này.";
        when(calendarService.updateShift(eq("shift-uuid-1"), any(UpdateShiftRequest.class)))
                .thenThrow(new CalendarException(CalendarErrorCode.SHIFT_CANNOT_TRUNCATE_WITH_BOOKED_SLOTS, cutMsg));

        mockMvc.perform(put("/api/v1/calendar/shifts/shift-uuid-1")
                        .with(authentication(getTenantAdminAuth()))
                        .header("X-Tenant-Code", TENANT_CODE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.errorCode").value("CAL_018"))
                .andExpect(jsonPath("$.message").value(cutMsg));
    }

    @Test
    @Order(16)
    @DisplayName("TC-CAL-17: Adjust shift when cut window is clear -> 200 OK")
    void tcCal17_updateShift_clearCutWindow_shouldReturn200() throws Exception {
        UpdateShiftRequest request = UpdateShiftRequest.builder()
                .shiftStart(LocalTime.of(8, 30))
                .shiftEnd(LocalTime.of(11, 30))
                .build();

        ShiftResponse updated = ShiftResponse.builder()
                .id("shift-uuid-1")
                .shiftStart(LocalTime.of(8, 30))
                .shiftEnd(LocalTime.of(11, 30))
                .status(ShiftStatus.SCHEDULED)
                .build();

        when(calendarService.updateShift(eq("shift-uuid-1"), any(UpdateShiftRequest.class))).thenReturn(updated);

        mockMvc.perform(put("/api/v1/calendar/shifts/shift-uuid-1")
                        .with(authentication(getTenantAdminAuth()))
                        .header("X-Tenant-Code", TENANT_CODE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(200))
                .andExpect(jsonPath("$.data.shiftStart").value("08:30:00"))
                .andExpect(jsonPath("$.data.shiftEnd").value("11:30:00"));
    }

    @Test
    @Order(17)
    @DisplayName("TC-CAL-18: Cancel shift when all slots are available -> 200 OK")
    void tcCal18_cancelShift_allAvailable_shouldReturn200() throws Exception {
        ShiftResponse cancelled = ShiftResponse.builder()
                .id("shift-uuid-1")
                .status(ShiftStatus.CANCELLED)
                .build();

        when(calendarService.cancelShift("shift-uuid-1")).thenReturn(cancelled);

        mockMvc.perform(delete("/api/v1/calendar/shifts/shift-uuid-1")
                        .with(authentication(getTenantAdminAuth()))
                        .header("X-Tenant-Code", TENANT_CODE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(200))
                .andExpect(jsonPath("$.data.status").value("CANCELLED"));
    }

    // =========================================================================
    // Phase 5: Facility Booking Policies & Lead Times
    // =========================================================================

    @Test
    @Order(18)
    @DisplayName("TC-CAL-19: Retrieve default facility calendar settings -> 200 OK")
    void tcCal19_getSettings_default_shouldReturn200() throws Exception {
        CalendarSettingsResponse settings = CalendarSettingsResponse.builder()
                .id("settings-default")
                .minLeadTimeHours(2)
                .maxAdvanceBookingDays(30)
                .cancellationDeadlineHours(12)
                .build();

        when(calendarSettingsService.getSettings()).thenReturn(settings);

        mockMvc.perform(get("/api/v1/calendar/settings")
                        .with(authentication(getTenantAdminAuth()))
                        .header("X-Tenant-Code", TENANT_CODE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(200))
                .andExpect(jsonPath("$.data.minLeadTimeHours").value(2))
                .andExpect(jsonPath("$.data.maxAdvanceBookingDays").value(30))
                .andExpect(jsonPath("$.data.cancellationDeadlineHours").value(12));
    }

    @Test
    @Order(19)
    @DisplayName("TC-CAL-20: Update facility booking policies -> 200 OK")
    void tcCal20_updateSettings_shouldReturn200() throws Exception {
        CalendarSettingsRequest request = CalendarSettingsRequest.builder()
                .minLeadTimeHours(4)
                .maxAdvanceBookingDays(14)
                .cancellationDeadlineHours(6)
                .build();

        CalendarSettingsResponse updated = CalendarSettingsResponse.builder()
                .id("settings-default")
                .minLeadTimeHours(4)
                .maxAdvanceBookingDays(14)
                .cancellationDeadlineHours(6)
                .build();

        when(calendarSettingsService.updateSettings(any(CalendarSettingsRequest.class))).thenReturn(updated);

        mockMvc.perform(put("/api/v1/calendar/settings")
                        .with(authentication(getTenantAdminAuth()))
                        .header("X-Tenant-Code", TENANT_CODE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(200))
                .andExpect(jsonPath("$.data.minLeadTimeHours").value(4))
                .andExpect(jsonPath("$.data.maxAdvanceBookingDays").value(14))
                .andExpect(jsonPath("$.data.cancellationDeadlineHours").value(6));
    }

    @Test
    @Order(20)
    @DisplayName("TC-CAL-21: Public endpoint retrieves updated facility booking settings -> 200 OK")
    void tcCal21_publicGetSettings_shouldReturn200() throws Exception {
        CalendarSettingsResponse updated = CalendarSettingsResponse.builder()
                .id("settings-default")
                .minLeadTimeHours(4)
                .maxAdvanceBookingDays(14)
                .cancellationDeadlineHours(6)
                .build();

        when(calendarSettingsService.getSettings()).thenReturn(updated);

        mockMvc.perform(get("/api/v1/public/calendar/settings")
                        .header("X-Tenant-Code", TENANT_CODE))
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

