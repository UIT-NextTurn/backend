package com.nextturn.controller.appointment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nextturn.config.CorsConfig;
import com.nextturn.config.RestClientConfig;
import com.nextturn.config.ScalarConfig;
import com.nextturn.dto.appointment.AppointmentResponse;
import com.nextturn.dto.appointment.CancelAppointmentRequest;
import com.nextturn.dto.appointment.CreateAppointmentRequest;
import com.nextturn.dto.appointment.RescheduleAppointmentRequest;
import com.nextturn.enums.AppointmentStatus;
import com.nextturn.exception.GlobalExceptionHandler;
import com.nextturn.infrastructure.multitenancy.TenantContextFilter;
import com.nextturn.infrastructure.security.TestSecurityConfig;
import com.nextturn.infrastructure.security.config.SecurityConfig;
import com.nextturn.infrastructure.security.keycloak.KeycloakJwtAuthenticationConverter;
import com.nextturn.infrastructure.security.keycloak.KeycloakJwtGrantedAuthoritiesConverter;
import com.nextturn.infrastructure.security.principal.CurrentUserArgumentResolver;
import com.nextturn.infrastructure.security.service.SecurityContextHelper;
import com.nextturn.service.appointment.TenantAppointmentService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.time.LocalTime;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(PublicAppointmentController.class)
@EnableMethodSecurity(prePostEnabled = true)
@Import({
        ScalarConfig.class,
        RestClientConfig.class,
        SecurityConfig.class,
        TestSecurityConfig.class,
        CorsConfig.class,
        TenantContextFilter.class,
        GlobalExceptionHandler.class,
        KeycloakJwtAuthenticationConverter.class,
        KeycloakJwtGrantedAuthoritiesConverter.class,
        CurrentUserArgumentResolver.class,
        SecurityContextHelper.class
})
class PublicAppointmentControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private TenantAppointmentService appointmentService;

    @Test
    @DisplayName("POST /api/v1/public/appointments - Should create appointment and return 201 Created (REQ-APT-03)")
    void shouldCreateAppointmentSuccessfully() throws Exception {
        AppointmentResponse mockResponse = AppointmentResponse.builder()
                .id("apt-uuid-01")
                .appointmentCode("APT-20261125-0001")
                .tenantCode("clinic-alpha")
                .tenantName("Phòng khám Đa khoa Alpha")
                .serviceId("srv-1")
                .serviceName("Khám Nội")
                .slotId("slot-1")
                .workDate(LocalDate.of(2026, 11, 25))
                .slotStart(LocalTime.of(9, 0))
                .slotEnd(LocalTime.of(9, 30))
                .customerName("Nguyễn Văn Khách")
                .customerPhone("0901234567")
                .qrToken("NTQR-CLINIC-ALPHA-APT-20261125-0001")
                .status(AppointmentStatus.CONFIRMED)
                .canModify(true)
                .build();

        when(appointmentService.createAppointment(any(CreateAppointmentRequest.class), any()))
                .thenReturn(mockResponse);

        CreateAppointmentRequest request = CreateAppointmentRequest.builder()
                .slotId("slot-1")
                .holdingToken("hold-tok-123")
                .customerName("Nguyễn Văn Khách")
                .customerPhone("0901234567")
                .customerEmail("khach@example.com")
                .build();

        mockMvc.perform(post("/api/v1/public/appointments")
                        .header("X-Tenant-Code", "clinic-alpha")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value(201))
                .andExpect(jsonPath("$.data.appointmentCode").value("APT-20261125-0001"))
                .andExpect(jsonPath("$.data.status").value("CONFIRMED"));
    }

    @Test
    @DisplayName("GET /api/v1/public/appointments/lookup - Should lookup appointment by code and phone (REQ-APT-04)")
    void shouldLookupAppointmentSuccessfully() throws Exception {
        AppointmentResponse mockResponse = AppointmentResponse.builder()
                .id("apt-uuid-01")
                .appointmentCode("APT-20261125-0001")
                .customerPhone("0901234567")
                .status(AppointmentStatus.CONFIRMED)
                .build();

        when(appointmentService.lookupAppointment("APT-20261125-0001", "0901234567"))
                .thenReturn(mockResponse);

        mockMvc.perform(get("/api/v1/public/appointments/lookup")
                        .header("X-Tenant-Code", "clinic-alpha")
                        .param("appointmentCode", "APT-20261125-0001")
                        .param("customerPhone", "0901234567"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.appointmentCode").value("APT-20261125-0001"));
    }

    @Test
    @DisplayName("POST /api/v1/public/appointments/{id}/cancel - Should cancel appointment (REQ-APT-04)")
    void shouldCancelAppointmentSuccessfully() throws Exception {
        AppointmentResponse mockResponse = AppointmentResponse.builder()
                .id("apt-uuid-01")
                .appointmentCode("APT-20261125-0001")
                .status(AppointmentStatus.CANCELLED)
                .cancellationReason("Bận công tác")
                .build();

        when(appointmentService.cancelAppointment(eq("apt-uuid-01"), any(CancelAppointmentRequest.class), any(), eq(false)))
                .thenReturn(mockResponse);

        CancelAppointmentRequest request = CancelAppointmentRequest.builder()
                .customerPhone("0901234567")
                .cancellationReason("Bận công tác")
                .build();

        mockMvc.perform(post("/api/v1/public/appointments/apt-uuid-01/cancel")
                        .header("X-Tenant-Code", "clinic-alpha")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CANCELLED"));
    }

    @Test
    @DisplayName("PUT /api/v1/public/appointments/{id}/reschedule - Should reschedule appointment (REQ-APT-04)")
    void shouldRescheduleAppointmentSuccessfully() throws Exception {
        AppointmentResponse mockResponse = AppointmentResponse.builder()
                .id("apt-uuid-01")
                .appointmentCode("APT-20261125-0001")
                .slotId("slot-new-99")
                .status(AppointmentStatus.CONFIRMED)
                .build();

        when(appointmentService.rescheduleAppointment(eq("apt-uuid-01"), any(RescheduleAppointmentRequest.class), any(), eq(false)))
                .thenReturn(mockResponse);

        RescheduleAppointmentRequest request = RescheduleAppointmentRequest.builder()
                .customerPhone("0901234567")
                .newSlotId("slot-new-99")
                .build();

        mockMvc.perform(put("/api/v1/public/appointments/apt-uuid-01/reschedule")
                        .header("X-Tenant-Code", "clinic-alpha")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.slotId").value("slot-new-99"));
    }
}
