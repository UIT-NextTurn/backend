package com.nextturn.integration;

import com.nextturn.constant.AppointmentEndpointConstant;
import com.nextturn.constant.TenantHeaderConstant;
import com.nextturn.dto.appointment.CreateAppointmentRequest;
import com.nextturn.dto.calendar.HoldSlotRequest;
import com.nextturn.entity.TenantTimeSlot;
import com.nextturn.enums.TimeSlotStatus;
import com.nextturn.repository.appointment.TenantAppointmentRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Concurrency integration test proving Zero Double-Booking on Calendar slots (Issue #40)
 * using real Valkey distributed mutex locks and MySQL {@code SELECT ... FOR UPDATE} row locks.
 */
class CalendarDoubleBookingConcurrencyIntegrationTest extends AbstractFlowIntegrationTest {

    @Autowired
    private TenantAppointmentRepository appointmentRepository;

    @Test
    @DisplayName("Parallel Calendar slot holds guarantee Zero Double-Booking (1 winner, N-1 conflicts)")
    void concurrentSlotHolding_ShouldGuaranteeZeroDoubleBookingOnCalendar() throws Exception {
        ClinicFixture fixture = seedStandardClinicFixture(DEFAULT_TENANT_CODE);
        int contenderCount = 8;
        String payload = objectMapper.writeValueAsString(HoldSlotRequest.builder().holdingMinutes(10).build());

        List<MvcResult> results = runConcurrently(contenderCount, index -> () -> mockMvc.perform(
                        post(AppointmentEndpointConstant.PUBLIC_APPOINTMENTS_BASE + "/slots/" + fixture.slotId() + "/hold")
                                .header(TenantHeaderConstant.X_TENANT_CODE_HEADER, fixture.tenantCode())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(payload))
                .andReturn());

        List<String> winningTokens = new ArrayList<>();
        int conflictCount = 0;
        for (MvcResult res : results) {
            int statusCode = res.getResponse().getStatus();
            if (statusCode == 200) {
                winningTokens.add(parseJsonData(res.getResponse().getContentAsString()).get("holdingToken").asText());
            } else {
                assertThat(statusCode).isEqualTo(409);
                conflictCount++;
            }
        }

        assertThat(winningTokens).hasSize(1);
        assertThat(conflictCount).isEqualTo(contenderCount - 1);

        // Winner confirms the single booking on that slot
        String winnerToken = winningTokens.getFirst();
        CreateAppointmentRequest firstBooking = CreateAppointmentRequest.builder()
                .slotId(fixture.slotId())
                .holdingToken(winnerToken)
                .customerName("Khách Thắng Cuộc")
                .customerPhone("0902223344")
                .build();

        mockMvc.perform(post(AppointmentEndpointConstant.PUBLIC_APPOINTMENTS_BASE)
                        .header(TenantHeaderConstant.X_TENANT_CODE_HEADER, fixture.tenantCode())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(firstBooking)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("CONFIRMED"));

        // Any subsequent booking attempt on the same already-booked slot must be rejected
        CreateAppointmentRequest secondBooking = CreateAppointmentRequest.builder()
                .slotId(fixture.slotId())
                .holdingToken(winnerToken)
                .customerName("Khách Đặt Trùng")
                .customerPhone("0909998877")
                .build();

        mockMvc.perform(post(AppointmentEndpointConstant.PUBLIC_APPOINTMENTS_BASE)
                        .header(TenantHeaderConstant.X_TENANT_CODE_HEADER, fixture.tenantCode())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(secondBooking)))
                .andExpect(status().isConflict());

        executeInTenant(fixture.tenantCode(), () -> {
            TenantTimeSlot slot = timeSlotRepository.findById(fixture.slotId()).orElseThrow();
            assertThat(slot.getStatus()).isEqualTo(TimeSlotStatus.BOOKED);
            assertThat(slot.getBookedCount()).isEqualTo(1);
            assertThat(appointmentRepository.findAll()).hasSize(1);
            return null;
        });
    }
}
