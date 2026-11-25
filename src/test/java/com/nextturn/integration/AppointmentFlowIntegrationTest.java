package com.nextturn.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.nextturn.constant.AppointmentEndpointConstant;
import com.nextturn.constant.CheckInEndpointConstant;
import com.nextturn.constant.QueueEndpointConstant;
import com.nextturn.constant.TenantHeaderConstant;
import com.nextturn.dto.appointment.CreateAppointmentRequest;
import com.nextturn.dto.checkin.AppointmentCheckInRequest;
import com.nextturn.entity.TenantAppointment;
import com.nextturn.entity.TenantQueueTicket;
import com.nextturn.entity.TenantTicketAuditLog;
import com.nextturn.entity.TenantTimeSlot;
import com.nextturn.enums.AppointmentStatus;
import com.nextturn.enums.TicketStatus;
import com.nextturn.enums.TimeSlotStatus;
import com.nextturn.repository.appointment.TenantAppointmentRepository;
import com.nextturn.repository.ticket.TenantQueueTicketRepository;
import com.nextturn.repository.ticket.TenantTicketAuditLogRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end integration test verifying the pre-booked appointment journey (Issue #40):
 * slot lookup -> hold -> booking confirmation -> QR check-in -> call next -> serve -> complete.
 */
class AppointmentFlowIntegrationTest extends AbstractFlowIntegrationTest {

    @Autowired
    private TenantAppointmentRepository appointmentRepository;

    @Autowired
    private TenantQueueTicketRepository ticketRepository;

    @Autowired
    private TenantTicketAuditLogRepository ticketAuditLogRepository;

    @Test
    @DisplayName("Pre-booked appointment -> QR check-in -> call next -> serve -> complete persists MySQL & Valkey state")
    void appointmentEndToEndFlow_BookCheckInCallServeComplete() throws Exception {
        ClinicFixture fixture = seedStandardClinicFixture(DEFAULT_TENANT_CODE);
        String counterAuth = registerStaffBearerToken(
                "COUNTER_STAFF", fixture.counterSub(), "counter@" + fixture.tenantCode() + ".internal", fixture.tenantCode());

        // 1. Lookup available slots filtered by clinic policy
        mockMvc.perform(get(AppointmentEndpointConstant.PUBLIC_APPOINTMENTS_BASE + "/slots")
                        .header(TenantHeaderConstant.X_TENANT_CODE_HEADER, fixture.tenantCode())
                        .param("serviceId", fixture.serviceId())
                        .param("appointmentDate", fixture.workDate().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(fixture.slotId()))
                .andExpect(jsonPath("$.data[0].status").value("AVAILABLE"));

        // 2. Hold slot via Valkey distributed lock + MySQL FOR UPDATE
        String holdingToken = holdSlotAndExtractToken(fixture.tenantCode(), fixture.slotId());
        assertThat(holdingToken).isNotBlank();

        // 3. Confirm appointment booking
        CreateAppointmentRequest createReq = CreateAppointmentRequest.builder()
                .slotId(fixture.slotId())
                .holdingToken(holdingToken)
                .customerName("Nguyễn Minh Hẹn")
                .customerPhone("0901112233")
                .customerEmail("minh.hen@example.com")
                .notes("Kiểm tra sức khỏe tổng quát")
                .build();

        MvcResult bookingRes = mockMvc.perform(post(AppointmentEndpointConstant.PUBLIC_APPOINTMENTS_BASE)
                        .header(TenantHeaderConstant.X_TENANT_CODE_HEADER, fixture.tenantCode())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createReq)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.data.qrToken").isNotEmpty())
                .andReturn();

        JsonNode bookingData = parseJsonData(bookingRes.getResponse().getContentAsString());
        String appointmentId = bookingData.get("id").asText();
        String appointmentCode = bookingData.get("appointmentCode").asText();
        String appointmentQrToken = bookingData.get("qrToken").asText();

        executeInTenant(fixture.tenantCode(), () -> {
            TenantTimeSlot bookedSlot = timeSlotRepository.findById(fixture.slotId()).orElseThrow();
            assertThat(bookedSlot.getStatus()).isEqualTo(TimeSlotStatus.BOOKED);
            assertThat(bookedSlot.getBookedCount()).isEqualTo(1);
            return null;
        });

        // 4. Customer checks in at lobby with appointment QR token
        AppointmentCheckInRequest checkInReq = AppointmentCheckInRequest.builder()
                .qrToken(appointmentQrToken)
                .build();

        MvcResult checkInRes = mockMvc.perform(post(CheckInEndpointConstant.PUBLIC_CHECKIN_BASE + CheckInEndpointConstant.APPOINTMENT_CHECKIN)
                        .header(TenantHeaderConstant.X_TENANT_CODE_HEADER, fixture.tenantCode())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(checkInReq)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.ticketCode").value("A-001"))
                .andExpect(jsonPath("$.data.sequenceNumber").value(1))
                .andExpect(jsonPath("$.data.customerType").value("APPOINTMENT"))
                .andExpect(jsonPath("$.data.status").value("WAITING"))
                .andReturn();

        JsonNode ticketData = parseJsonData(checkInRes.getResponse().getContentAsString());
        String ticketId = ticketData.get("id").asText();
        String ticketCode = ticketData.get("ticketCode").asText();

        // 5. Counter calls next priority ticket (WAITING -> CALLING) and lobby display reflects it
        JsonNode calledData = callNextTicket(fixture.tenantCode(), counterAuth, fixture.serviceId(), "Quầy 01");
        assertThat(calledData.get("id").asText()).isEqualTo(ticketId);
        assertThat(calledData.get("status").asText()).isEqualTo("CALLING");

        mockMvc.perform(get(QueueEndpointConstant.PUBLIC_QUEUE_BASE + QueueEndpointConstant.LOBBY_DISPLAY)
                        .header(TenantHeaderConstant.X_TENANT_CODE_HEADER, fixture.tenantCode()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.callingTickets[0].ticketCode").value(ticketCode));

        // 6. Customer arrives at counter -> assign active specialist in shift (CALLING -> SERVING)
        JsonNode servingData = serveTicket(
                fixture.tenantCode(), counterAuth, ticketId, fixture.specialistStaffId(), calledData.get("version").asInt());
        assertThat(servingData.get("status").asText()).isEqualTo("SERVING");
        assertThat(servingData.get("providerId").asText()).isEqualTo(fixture.specialistStaffId());

        // 7. Complete service -> issues feedback token, clears active lobby display, records audit trail
        JsonNode completedData = completeTicket(
                fixture.tenantCode(), counterAuth, ticketId, servingData.get("version").asInt());
        assertThat(completedData.get("status").asText()).isEqualTo("COMPLETED");
        assertThat(completedData.get("feedbackToken").asText()).isNotBlank();

        mockMvc.perform(get(QueueEndpointConstant.PUBLIC_QUEUE_BASE + QueueEndpointConstant.LOBBY_DISPLAY)
                        .header(TenantHeaderConstant.X_TENANT_CODE_HEADER, fixture.tenantCode()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.callingTickets").isEmpty())
                .andExpect(jsonPath("$.data.servingTickets").isEmpty());

        executeInTenant(fixture.tenantCode(), () -> {
            TenantAppointment checkedInAppt = appointmentRepository.findByAppointmentCodeIgnoreCase(appointmentCode).orElseThrow();
            assertThat(checkedInAppt.getId()).isEqualTo(appointmentId);
            assertThat(checkedInAppt.getStatus()).isEqualTo(AppointmentStatus.CHECKED_IN);

            TenantQueueTicket persistedTicket = ticketRepository.findById(ticketId).orElseThrow();
            assertThat(persistedTicket.getStatus()).isEqualTo(TicketStatus.COMPLETED);
            assertThat(persistedTicket.getFeedbackToken()).isNotBlank();

            List<TenantTicketAuditLog> audits = ticketAuditLogRepository.findByTicketIdOrderByCreatedAtAsc(ticketId);
            assertThat(audits)
                    .extracting(TenantTicketAuditLog::getNewState)
                    .containsExactly(TicketStatus.WAITING, TicketStatus.CALLING, TicketStatus.SERVING, TicketStatus.COMPLETED);
            return null;
        });
    }
}
