package com.nextturn.dto.queue;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request payload for confirming customer arrival at counter and transferring to a Specialist (REQ-QC-02).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Request payload to confirm customer arrival and assign a specialist (CALLING -> SERVING)")
public class ServeTicketRequest {

    @Schema(description = "Optional target Specialist staff UUID; if omitted, system auto-assigns the least-loaded active specialist in shift")
    private String providerId;

    @Schema(description = "Expected optimistic lock version for concurrency control (returns 409 Conflict if stale)")
    private Integer expectedVersion;
}
