package com.nextturn.dto.queue;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request payload for ticket FSM state transitions supporting optimistic locking (REQ-QC-03, REQ-QC-04).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Request payload for ticket recall, completion, or no-show marking with optional optimistic lock version")
public class TicketTransitionRequest {

    @Schema(description = "Expected optimistic lock version (returns 409 Conflict if ticket was already modified)")
    private Integer expectedVersion;

    @Schema(description = "Optional counter desk label override when recalling a ticket", example = "Quầy 01")
    private String counterLabel;
}
