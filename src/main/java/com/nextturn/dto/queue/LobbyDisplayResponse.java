package com.nextturn.dto.queue;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Public Lobby TV / Waiting Room display payload (REQ-QC-01, REQ-QC-04).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "Lobby waiting room display showing currently calling and serving queue tickets")
public class LobbyDisplayResponse {

    private String tenantCode;
    private LocalDate workDate;
    private List<QueueTicketSummaryResponse> callingTickets;
    private List<QueueTicketSummaryResponse> servingTickets;
    private LocalDateTime generatedAt;
}
