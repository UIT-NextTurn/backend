package com.nextturn.dto.queue;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Real-time SSE event payload pushed to Lobby TV screens and Counter Kanban dashboards (REQ-QC-01..04).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "Real-time SSE event payload for Lobby Display and Counter Kanban updates")
public class LobbyEventResponse {

    private String eventType;
    private QueueTicketSummaryResponse ticket;
    private LobbyDisplayResponse display;
    private String announcementMessage;
    private LocalDateTime timestamp;
}
