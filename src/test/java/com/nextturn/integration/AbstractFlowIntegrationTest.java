package com.nextturn.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.nextturn.constant.AppointmentEndpointConstant;
import com.nextturn.constant.QueueEndpointConstant;
import com.nextturn.constant.TenantHeaderConstant;
import com.nextturn.dto.calendar.HoldSlotRequest;
import com.nextturn.dto.queue.CallNextTicketRequest;
import com.nextturn.dto.queue.ServeTicketRequest;
import com.nextturn.dto.queue.TicketTransitionRequest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Shared step and concurrency helpers reused across decoupled end-to-end integration test classes
 * while inheriting the single cached Spring context and Testcontainers lifecycle from {@link BaseIntegrationTest}.
 */
abstract class AbstractFlowIntegrationTest extends BaseIntegrationTest {

    protected String holdSlotAndExtractToken(String tenantCode, String slotId) throws Exception {
        HoldSlotRequest req = HoldSlotRequest.builder().holdingMinutes(10).build();
        MvcResult result = mockMvc.perform(post(AppointmentEndpointConstant.PUBLIC_APPOINTMENTS_BASE + "/slots/" + slotId + "/hold")
                        .header(TenantHeaderConstant.X_TENANT_CODE_HEADER, tenantCode)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("HOLDING"))
                .andReturn();
        return parseJsonData(result.getResponse().getContentAsString()).get("holdingToken").asText();
    }

    protected JsonNode callNextTicket(
            String tenantCode,
            String authHeader,
            String serviceId,
            String counterLabel
    ) throws Exception {
        CallNextTicketRequest req = CallNextTicketRequest.builder()
                .serviceId(serviceId)
                .counterLabel(counterLabel)
                .build();
        MvcResult res = mockMvc.perform(post(QueueEndpointConstant.QUEUE_BASE + QueueEndpointConstant.CALL_NEXT)
                        .header(TenantHeaderConstant.X_TENANT_CODE_HEADER, tenantCode)
                        .header("Authorization", authHeader)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andReturn();
        return parseJsonData(res.getResponse().getContentAsString());
    }

    protected JsonNode serveTicket(
            String tenantCode,
            String authHeader,
            String ticketId,
            String providerId,
            int expectedVersion
    ) throws Exception {
        ServeTicketRequest req = ServeTicketRequest.builder()
                .providerId(providerId)
                .expectedVersion(expectedVersion)
                .build();
        MvcResult res = mockMvc.perform(post(QueueEndpointConstant.QUEUE_BASE + "/tickets/" + ticketId + "/serve")
                        .header(TenantHeaderConstant.X_TENANT_CODE_HEADER, tenantCode)
                        .header("Authorization", authHeader)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andReturn();
        return parseJsonData(res.getResponse().getContentAsString());
    }

    protected JsonNode completeTicket(
            String tenantCode,
            String authHeader,
            String ticketId,
            int expectedVersion
    ) throws Exception {
        TicketTransitionRequest req = TicketTransitionRequest.builder()
                .expectedVersion(expectedVersion)
                .build();
        MvcResult res = mockMvc.perform(post(QueueEndpointConstant.QUEUE_BASE + "/tickets/" + ticketId + "/complete")
                        .header(TenantHeaderConstant.X_TENANT_CODE_HEADER, tenantCode)
                        .header("Authorization", authHeader)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andReturn();
        return parseJsonData(res.getResponse().getContentAsString());
    }

    protected <T> List<T> runConcurrently(int threads, IntTaskFactory<T> taskFactory) throws Exception {
        CountDownLatch readyLatch = new CountDownLatch(threads);
        CountDownLatch startGate = new CountDownLatch(1);
        List<Future<T>> futures = new ArrayList<>(threads);
        try (ExecutorService executor = Executors.newFixedThreadPool(threads)) {
            for (int i = 0; i < threads; i++) {
                Callable<T> work = taskFactory.create(i);
                futures.add(executor.submit(() -> {
                    readyLatch.countDown();
                    assertThat(startGate.await(10, TimeUnit.SECONDS)).isTrue();
                    return work.call();
                }));
            }
            assertThat(readyLatch.await(10, TimeUnit.SECONDS)).isTrue();
            startGate.countDown();

            List<T> results = new ArrayList<>(threads);
            for (Future<T> future : futures) {
                results.add(future.get(30, TimeUnit.SECONDS));
            }
            return results;
        }
    }

    @FunctionalInterface
    protected interface IntTaskFactory<T> {
        Callable<T> create(int index);
    }
}
