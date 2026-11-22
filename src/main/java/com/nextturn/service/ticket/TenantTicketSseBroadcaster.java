package com.nextturn.service.ticket;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.LocalDate;

/**
 * Contract for real-time Server-Sent Events (SSE) queue progress broadcasting
 * to subscribed customer virtual ticket views (REQ-TICKET-03).
 */
public interface TenantTicketSseBroadcaster {

    /**
     * Subscribes a customer's browser to live queue updates for a specific ticket.
     *
     * @param tenantCode tenant code
     * @param ticketId   ticket UUID or code
     * @return configured {@link SseEmitter}
     */
    SseEmitter subscribe(String tenantCode, String ticketId);

    /**
     * Broadcasts updated queue metrics to all active SSE subscribers in the given tenant/service/date queue.
     *
     * @param tenantCode tenant code
     * @param serviceId  service UUID
     * @param workDate   operating work date
     * @param eventType  event identifier (e.g. {@code TICKET_ISSUED}, {@code QUEUE_ADVANCED})
     */
    void broadcastQueueUpdate(String tenantCode, String serviceId, LocalDate workDate, String eventType);
}
