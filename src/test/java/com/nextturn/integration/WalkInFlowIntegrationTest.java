package com.nextturn.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.nextturn.constant.CheckInEndpointConstant;
import com.nextturn.constant.TenantHeaderConstant;
import com.nextturn.constant.TicketEndpointConstant;
import com.nextturn.dto.checkin.CreateReceptionPointRequest;
import com.nextturn.dto.checkin.WalkInCheckInRequest;
import com.nextturn.entity.TenantQueueTicket;
import com.nextturn.enums.ReceptionQrMode;
import com.nextturn.enums.TicketCustomerType;
import com.nextturn.enums.TicketStatus;
import com.nextturn.repository.ticket.TenantQueueTicketRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end integration test verifying the walk-in customer queue lifecycle (Issue #40):
 * reception QR creation/resolve -> walk-in ticket issuance -> phone recovery -> call next -> serve -> complete.
 */
class WalkInFlowIntegrationTest extends AbstractFlowIntegrationTest {

    @Autowired
    private TenantQueueTicketRepository ticketRepository;

    @Test
    @DisplayName("Walk-in QR check-in -> issue ticket -> recover -> call -> serve -> complete")
    void walkInEndToEndFlow_ResolveQrIssueTicketAndComplete() throws Exception {
        ClinicFixture fixture = seedStandardClinicFixture(DEFAULT_TENANT_CODE);
        String adminAuth = registerStaffBearerToken(
                "TENANT_ADMIN", fixture.adminSub(), "admin@" + fixture.tenantCode() + ".internal", fixture.tenantCode());
        String counterAuth = registerStaffBearerToken(
                "COUNTER_STAFF", fixture.counterSub(), "counter@" + fixture.tenantCode() + ".internal", fixture.tenantCode());

        // 1. Admin creates a reception point (STATIC avoids 60s epoch rollover flake) & reads QR token
        CreateReceptionPointRequest createPointReq = CreateReceptionPointRequest.builder()
                .pointCode("REC-LOBBY-01")
                .pointName("Quầy Tiếp Đón Sảnh Chính")
                .locationLabel("Tầng 1")
                .serviceId(fixture.serviceId())
                .qrMode(ReceptionQrMode.STATIC)
                .rotationSeconds(60)
                .build();

        MvcResult pointRes = mockMvc.perform(post(CheckInEndpointConstant.ADMIN_RECEPTION_BASE)
                        .header(TenantHeaderConstant.X_TENANT_CODE_HEADER, fixture.tenantCode())
                        .header("Authorization", adminAuth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createPointReq)))
                .andExpect(status().isCreated())
                .andReturn();

        String receptionPointId = parseJsonData(pointRes.getResponse().getContentAsString()).get("id").asText();

        MvcResult qrRes = mockMvc.perform(get(CheckInEndpointConstant.ADMIN_RECEPTION_BASE + "/" + receptionPointId + "/qr")
                        .header(TenantHeaderConstant.X_TENANT_CODE_HEADER, fixture.tenantCode())
                        .header("Authorization", adminAuth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.qrToken").isNotEmpty())
                .andReturn();

        String receptionQrToken = parseJsonData(qrRes.getResponse().getContentAsString()).get("qrToken").asText();

        // 2. Walk-in customer scans QR to resolve scoped service options
        mockMvc.perform(get(CheckInEndpointConstant.PUBLIC_CHECKIN_BASE + CheckInEndpointConstant.RESOLVE_QR)
                        .header(TenantHeaderConstant.X_TENANT_CODE_HEADER, fixture.tenantCode())
                        .param("token", receptionQrToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.pointCode").value("REC-LOBBY-01"))
                .andExpect(jsonPath("$.data.scopedService.id").value(fixture.serviceId()))
                .andExpect(jsonPath("$.data.supportsWalkIn").value(true));

        // 3. Walk-in customer checks in using reception QR -> verifies sequence resets to A-001 per test
        String customerPhone = "0912345678";
        WalkInCheckInRequest walkInReq = WalkInCheckInRequest.builder()
                .serviceId(fixture.serviceId())
                .customerPhone(customerPhone)
                .customerName("Trần Vãng Lai")
                .receptionQrToken(receptionQrToken)
                .build();

        MvcResult walkInRes = mockMvc.perform(post(CheckInEndpointConstant.PUBLIC_CHECKIN_BASE + CheckInEndpointConstant.WALKIN_CHECKIN)
                        .header(TenantHeaderConstant.X_TENANT_CODE_HEADER, fixture.tenantCode())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(walkInReq)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.ticketCode").value("A-001"))
                .andExpect(jsonPath("$.data.sequenceNumber").value(1))
                .andExpect(jsonPath("$.data.customerType").value("WALK_IN"))
                .andExpect(jsonPath("$.data.status").value("WAITING"))
                .andReturn();

        String ticketId = parseJsonData(walkInRes.getResponse().getContentAsString()).get("id").asText();

        // 4. Customer recovers their active ticket by phone number
        mockMvc.perform(get(TicketEndpointConstant.PUBLIC_TICKETS_BASE + TicketEndpointConstant.TICKET_RECOVER)
                        .header(TenantHeaderConstant.X_TENANT_CODE_HEADER, fixture.tenantCode())
                        .param("customerPhone", customerPhone))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(ticketId))
                .andExpect(jsonPath("$.data[0].ticketCode").value("A-001"));

        // 5. Counter calls next -> auto-assigns specialist -> completes walk-in service
        JsonNode called = callNextTicket(fixture.tenantCode(), counterAuth, fixture.serviceId(), "Quầy 02");
        assertThat(called.get("customerType").asText()).isEqualTo("WALK_IN");

        JsonNode serving = serveTicket(fixture.tenantCode(), counterAuth, ticketId, null, called.get("version").asInt());
        assertThat(serving.get("providerId").asText()).isEqualTo(fixture.specialistStaffId());

        JsonNode completed = completeTicket(fixture.tenantCode(), counterAuth, ticketId, serving.get("version").asInt());
        assertThat(completed.get("status").asText()).isEqualTo("COMPLETED");

        executeInTenant(fixture.tenantCode(), () -> {
            TenantQueueTicket ticket = ticketRepository.findById(ticketId).orElseThrow();
            assertThat(ticket.getCustomerType()).isEqualTo(TicketCustomerType.WALK_IN);
            assertThat(ticket.getStatus()).isEqualTo(TicketStatus.COMPLETED);
            assertThat(ticket.getReceptionPoint()).isNotNull();
            return null;
        });
    }
}
