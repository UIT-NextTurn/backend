package com.nextturn.dto.ticket;

import com.nextturn.enums.TicketStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Real-time SSE event payload emitted when queue progress changes (REQ-TICKET-03).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "SSE payload pushed to subscribed virtual ticket views on queue state updates")
public class TicketQueueEventResponse {

    private String eventType;
    private String ticketId;
    private String ticketCode;
    private TicketStatus status;
    private String currentServingNumber;
    private Long queuePosition;
    private Long peopleAheadCount;
    private Long estimatedWaitMinutes;
    private Boolean approachingTurnAlert;
    private LocalDateTime timestamp;
}
