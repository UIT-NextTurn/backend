package com.nextturn.controller.appointment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nextturn.config.CorsConfig;
import com.nextturn.config.RestClientConfig;
import com.nextturn.config.ScalarConfig;
import com.nextturn.dto.appointment.AppointmentResponse;
import com.nextturn.dto.appointment.CancelAppointmentRequest;
import com.nextturn.dto.appointment.CreateAppointmentRequest;
import com.nextturn.dto.appointment.RescheduleAppointmentRequest;
import com.nextturn.dto.calendar.HoldSlotRequest;
import com.nextturn.dto.calendar.SlotHoldingResponse;
import com.nextturn.dto.calendar.TimeSlotResponse;
import com.nextturn.enums.AppointmentStatus;
import com.nextturn.enums.TimeSlotStatus;
import com.nextturn.exception.GlobalExceptionHandler;
import com.nextturn.exception.appointment.AppointmentErrorCode;
import com.nextturn.exception.appointment.AppointmentException;
import com.nextturn.infrastructure.multitenancy.TenantContextFilter;
import com.nextturn.infrastructure.security.TestSecurityConfig;
import com.nextturn.infrastructure.security.config.SecurityConfig;
import com.nextturn.infrastructure.security.keycloak.KeycloakJwtAuthenticationConverter;
import com.nextturn.infrastructure.security.keycloak.KeycloakJwtGrantedAuthoritiesConverter;
import com.nextturn.infrastructure.security.principal.CurrentUserArgumentResolver;
import com.nextturn.infrastructure.security.service.SecurityContextHelper;
import com.nextturn.service.appointment.TenantAppointmentService;
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
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
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
 * End-to-end automated Manual Test Plan Runner exercising every Appointment endpoint
 * across Phase 1 (REQ-APT-01) through Phase 5 (REQ-APT-05).
 */
@WebMvcTest(controllers = {PublicAppointmentController.class, TenantAppointmentController.class})
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
class AppointmentFeatureManualTestPlanRunnerTest {

    private static final String TENANT_CODE = "clinic-alpha";

    @Autowired
    private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Autowired
    private KeycloakJwtAuthenticationConverter authenticationConverter;

    @MockitoBean
    private TenantAppointmentService appointmentService;

    private AbstractAuthenticationToken customerAuth(String userId) {
        Jwt jwt = Jwt.withTokenValue("mock-customer-jwt")
                .header("alg", "RS256")
                .subject(userId)
                .claim("email", "customer@example.com")
                .claim("resource_access", Map.of("nextturn-backend", Map.of("roles", List.of("END_USER"))))
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600))
                .build();
        return authenticationConverter.convert(jwt);
    }

    @Test
    @Order(1)
    @DisplayName("Phase 1 (REQ-APT-01): GET /api/v1/public/appointments/slots - Query available slots filtered by facility policy")
    void tcApt01_QueryAvailableSlots_Success() throws Exception {
        LocalDate targetDate = LocalDate.now().plusDays(2);
        TimeSlotResponse slot = TimeSlotResponse.builder()
                .id("slot-0900")
                .serviceId("srv-001")
                .serviceName("Khám Nội Tổng Quát")
                .workDate(targetDate)
                .slotStart(LocalTime.of(9, 0))
                .slotEnd(LocalTime.of(9, 30))
                .capacityPerSlot(2)
                .bookedCount(0)
                .availableCount(2)
                .status(TimeSlotStatus.AVAILABLE)
                .build();

        when(appointmentService.getAvailableSlotsForBooking("srv-001", targetDate)).thenReturn(List.of(slot));

        mockMvc.perform(get("/api/v1/public/appointments/slots")
                        .header("X-Tenant-Code", TENANT_CODE)
                        .param("serviceId", "srv-001")
                        .param("appointmentDate", targetDate.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(200))
                .andExpect(jsonPath("$.data[0].id").value("slot-0900"))
                .andExpect(jsonPath("$.data[0].availableCount").value(2));
    }

    @Test
    @Order(2)
    @DisplayName("Phase 1 Exception (REQ-APT-01): GET /api/v1/public/appointments/slots - 404 when no slots remain on selected date")
    void tcApt01_NoAvailableSlots_Returns404() throws Exception {
        LocalDate targetDate = LocalDate.now().plusDays(2);
        when(appointmentService.getAvailableSlotsForBooking("srv-001", targetDate))
                .thenThrow(new AppointmentException(AppointmentErrorCode.NO_AVAILABLE_SLOTS));

        mockMvc.perform(get("/api/v1/public/appointments/slots")
                        .header("X-Tenant-Code", TENANT_CODE)
                        .param("serviceId", "srv-001")
                        .param("appointmentDate", targetDate.toString()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("APPT_009"))
                .andExpect(jsonPath("$.message").value("Dịch vụ trong ngày đã chọn hiện không còn khung giờ trống khả dụng. Quý khách vui lòng chọn ngày khác trong lịch mở hẹn."));
    }

    @Test
    @Order(3)
    @DisplayName("Phase 2 (REQ-APT-02): POST /api/v1/public/appointments/slots/{slotId}/hold - Temporarily hold slot for 10 minutes")
    void tcApt02_HoldSlot_Success() throws Exception {
        SlotHoldingResponse holdResp = SlotHoldingResponse.builder()
                .slotId("slot-0900")
                .status(TimeSlotStatus.HOLDING)
                .holdingToken("hold-token-xyz-999")
                .holdingExpiresAt(LocalDateTime.now().plusMinutes(10))
                .message("Đã giữ chỗ tạm thời trong 10 phút")
                .build();

        when(appointmentService.holdSlotForBooking("slot-0900", 10)).thenReturn(holdResp);

        HoldSlotRequest req = HoldSlotRequest.builder().holdingMinutes(10).build();

        mockMvc.perform(post("/api/v1/public/appointments/slots/slot-0900/hold")
                        .header("X-Tenant-Code", TENANT_CODE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("HOLDING"))
                .andExpect(jsonPath("$.data.holdingToken").value("hold-token-xyz-999"));
    }

    @Test
    @Order(4)
    @DisplayName("Phase 3 (REQ-APT-03): POST /api/v1/public/appointments - Confirm customer details and create appointment with QR token")
    void tcApt03_CreateAppointment_Success() throws Exception {
        LocalDate targetDate = LocalDate.now().plusDays(2);
        AppointmentResponse created = AppointmentResponse.builder()
                .id("apt-uuid-100")
                .appointmentCode("APT-20261124-0001")
                .tenantCode(TENANT_CODE)
                .tenantName("Phòng khám Đa khoa Alpha")
                .serviceId("srv-001")
                .serviceCode("SVC-0001")
                .serviceName("Khám Nội Tổng Quát")
                .slotId("slot-0900")
                .workDate(targetDate)
                .slotStart(LocalTime.of(9, 0))
                .slotEnd(LocalTime.of(9, 30))
                .customerName("Nguyễn Văn Khách")
                .customerPhone("0901234567")
                .customerEmail("khach@example.com")
                .qrToken("NTQR-CLINIC-ALPHA-APT-20261124-0001-ABCD1234EFGH")
                .status(AppointmentStatus.CONFIRMED)
                .canModify(true)
                .build();

        when(appointmentService.createAppointment(any(CreateAppointmentRequest.class), any()))
                .thenReturn(created);

        CreateAppointmentRequest req = CreateAppointmentRequest.builder()
                .slotId("slot-0900")
                .holdingToken("hold-token-xyz-999")
                .customerName("Nguyễn Văn Khách")
                .customerPhone("0901234567")
                .customerEmail("khach@example.com")
                .notes("Khám sức khỏe định kỳ")
                .build();

        mockMvc.perform(post("/api/v1/public/appointments")
                        .header("X-Tenant-Code", TENANT_CODE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value(201))
                .andExpect(jsonPath("$.data.appointmentCode").value("APT-20261124-0001"))
                .andExpect(jsonPath("$.data.qrToken").value("NTQR-CLINIC-ALPHA-APT-20261124-0001-ABCD1234EFGH"))
                .andExpect(jsonPath("$.data.status").value("CONFIRMED"));
    }

    @Test
    @Order(5)
    @DisplayName("Phase 4 (REQ-APT-04): GET /lookup, PUT /reschedule, and POST /cancel - Lookup, reschedule, and cancel appointment")
    void tcApt04_LookupRescheduleAndCancel_Success() throws Exception {
        AppointmentResponse lookedUp = AppointmentResponse.builder()
                .id("apt-uuid-100")
                .appointmentCode("APT-20261124-0001")
                .customerPhone("0901234567")
                .slotId("slot-0900")
                .status(AppointmentStatus.CONFIRMED)
                .canModify(true)
                .build();

        when(appointmentService.lookupAppointment("APT-20261124-0001", "0901234567")).thenReturn(lookedUp);

        mockMvc.perform(get("/api/v1/public/appointments/lookup")
                        .header("X-Tenant-Code", TENANT_CODE)
                        .param("appointmentCode", "APT-20261124-0001")
                        .param("customerPhone", "0901234567"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value("apt-uuid-100"));

        AppointmentResponse rescheduled = AppointmentResponse.builder()
                .id("apt-uuid-100")
                .appointmentCode("APT-20261124-0001")
                .slotId("slot-1000")
                .status(AppointmentStatus.CONFIRMED)
                .canModify(true)
                .build();

        when(appointmentService.rescheduleAppointment(eq("apt-uuid-100"), any(RescheduleAppointmentRequest.class), any(), eq(false)))
                .thenReturn(rescheduled);

        RescheduleAppointmentRequest rescheduleReq = RescheduleAppointmentRequest.builder()
                .customerPhone("0901234567")
                .newSlotId("slot-1000")
                .build();

        mockMvc.perform(put("/api/v1/public/appointments/apt-uuid-100/reschedule")
                        .header("X-Tenant-Code", TENANT_CODE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(rescheduleReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.slotId").value("slot-1000"));

        AppointmentResponse cancelled = AppointmentResponse.builder()
                .id("apt-uuid-100")
                .appointmentCode("APT-20261124-0001")
                .status(AppointmentStatus.CANCELLED)
                .cancellationReason("Bận họp đột xuất")
                .canModify(false)
                .build();

        when(appointmentService.cancelAppointment(eq("apt-uuid-100"), any(CancelAppointmentRequest.class), any(), eq(false)))
                .thenReturn(cancelled);

        CancelAppointmentRequest cancelReq = CancelAppointmentRequest.builder()
                .customerPhone("0901234567")
                .cancellationReason("Bận họp đột xuất")
                .build();

        mockMvc.perform(post("/api/v1/public/appointments/apt-uuid-100/cancel")
                        .header("X-Tenant-Code", TENANT_CODE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(cancelReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CANCELLED"));
    }

    @Test
    @Order(6)
    @DisplayName("Phase 5 (REQ-APT-05): GET /api/v1/appointments/my - Retrieve authenticated customer appointment history at current facility")
    void tcApt05_GetMyAppointments_Success() throws Exception {
        AppointmentResponse historyItem = AppointmentResponse.builder()
                .id("apt-uuid-100")
                .appointmentCode("APT-20261124-0001")
                .tenantCode(TENANT_CODE)
                .tenantName("Phòng khám Đa khoa Alpha")
                .userId("cust-user-999")
                .serviceName("Khám Nội Tổng Quát")
                .workDate(LocalDate.now().plusDays(2))
                .slotStart(LocalTime.of(10, 0))
                .slotEnd(LocalTime.of(10, 30))
                .status(AppointmentStatus.CONFIRMED)
                .canModify(true)
                .build();

        when(appointmentService.getCustomerAppointmentsForCurrentTenant("cust-user-999"))
                .thenReturn(List.of(historyItem));

        mockMvc.perform(get("/api/v1/appointments/my")
                        .with(authentication(customerAuth("cust-user-999")))
                        .header("X-Tenant-Code", TENANT_CODE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(200))
                .andExpect(jsonPath("$.data[0].appointmentCode").value("APT-20261124-0001"))
                .andExpect(jsonPath("$.data[0].tenantName").value("Phòng khám Đa khoa Alpha"))
                .andExpect(jsonPath("$.data[0].status").value("CONFIRMED"));
    }
}
