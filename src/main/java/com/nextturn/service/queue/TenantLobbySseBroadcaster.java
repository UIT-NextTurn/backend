package com.nextturn.service.queue;

import com.nextturn.entity.TenantQueueTicket;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Contract for broadcasting real-time Lobby Display & Kanban Board SSE events (REQ-QC-01..04).
 */
public interface TenantLobbySseBroadcaster {

    SseEmitter subscribe(String tenantCode);

    void broadcastLobbyEvent(String tenantCode, String eventType, TenantQueueTicket changedTicket);

    /**
     * Sends an SSE comment heartbeat to all active lobby emitters and evicts dead connections.
     *
     * @return number of healthy emitters successfully pinged
     */
    default int sendHeartbeats() {
        return 0;
    }
}
