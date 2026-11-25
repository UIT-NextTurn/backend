package com.nextturn.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.nextturn.constant.TenantHeaderConstant;
import com.nextturn.constant.TicketEndpointConstant;
import com.nextturn.constant.ValkeyKeyConstant;
import com.nextturn.dto.ticket.IssueTicketRequest;
import com.nextturn.entity.TenantQueueTicket;
import com.nextturn.repository.ticket.TenantQueueTicketRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Concurrency integration test proving strictly monotonic, duplicate-free ticket issuance (Issue #40)
 * using real Valkey mutex locking + atomic Lua sequencing and MySQL unique constraints.
 */
class TicketSequenceConcurrencyIntegrationTest extends AbstractFlowIntegrationTest {

    @Autowired
    private TenantQueueTicketRepository ticketRepository;

    @Test
    @DisplayName("Parallel ticket issuance generates strictly monotonic, duplicate-free ticket codes")
    void concurrentTicketIssuance_ShouldProduceStrictlyMonotonicUniqueTicketCodes() throws Exception {
        ClinicFixture fixture = seedStandardClinicFixture(DEFAULT_TENANT_CODE);
        // 5 parallel callers stay well inside TenantTicketSequenceGenerator's 500ms lock-retry budget under Docker I/O
        int requestCount = 5;

        List<MvcResult> results = runConcurrently(requestCount, index -> () -> {
            IssueTicketRequest req = IssueTicketRequest.builder()
                    .serviceId(fixture.serviceId())
                    .customerPhone(String.format("09800000%02d", index))
                    .customerName("Khách Hàng " + index)
                    .build();
            return mockMvc.perform(post(TicketEndpointConstant.PUBLIC_TICKETS_BASE)
                            .header(TenantHeaderConstant.X_TENANT_CODE_HEADER, fixture.tenantCode())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(req)))
                    .andReturn();
        });

        Set<String> issuedCodes = new HashSet<>();
        Set<Integer> issuedSequences = new HashSet<>();

        for (MvcResult res : results) {
            assertThat(res.getResponse().getStatus()).isEqualTo(201);
            JsonNode data = parseJsonData(res.getResponse().getContentAsString());
            issuedCodes.add(data.get("ticketCode").asText());
            issuedSequences.add(data.get("sequenceNumber").asInt());
        }

        assertThat(issuedCodes).hasSize(requestCount);
        assertThat(issuedSequences)
                .hasSize(requestCount)
                .containsExactlyInAnyOrderElementsOf(IntStream.rangeClosed(1, requestCount).boxed().toList());

        String valkeyCounter = redisTemplate.opsForValue().get(
                ValkeyKeyConstant.ticketSequenceKey(fixture.tenantCode(), fixture.serviceId(), fixture.workDate()));
        assertThat(valkeyCounter).isEqualTo(String.valueOf(requestCount));

        executeInTenant(fixture.tenantCode(), () -> {
            List<TenantQueueTicket> persisted = ticketRepository.findAll();
            assertThat(persisted).hasSize(requestCount);
            assertThat(persisted)
                    .extracting(TenantQueueTicket::getTicketCode)
                    .doesNotHaveDuplicates()
                    .containsExactlyInAnyOrder("A-001", "A-002", "A-003", "A-004", "A-005");
            return null;
        });
    }
}
