package com.nextturn.dto.queue;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.util.List;

/**
 * Kanban counter dashboard snapshot showing waiting priority queue, calling, serving, completed tickets,
 * and available in-shift specialists (REQ-QC-01 to REQ-QC-04).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "Counter staff Kanban board snapshot for today's queue")
public class QueueBoardResponse {

    private LocalDate workDate;
    private String serviceId;
    private List<QueueTicketSummaryResponse> waitingTickets;
    private List<QueueTicketSummaryResponse> callingTickets;
    private List<QueueTicketSummaryResponse> servingTickets;
    private List<QueueTicketSummaryResponse> completedTickets;
    private List<SpecialistLoadResponse> specialists;
}
