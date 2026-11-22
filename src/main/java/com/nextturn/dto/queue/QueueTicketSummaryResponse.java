package com.nextturn.dto.queue;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.nextturn.enums.TicketCustomerType;
import com.nextturn.enums.TicketStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Kanban & Lobby queue card DTO including dynamic priority score (REQ-QC-01..04).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "Queue calling board / lobby display ticket entry")
public class QueueTicketSummaryResponse {

    private String id;
    private String ticketCode;
    private Integer sequenceNumber;
    private String serviceId;
    private String serviceCode;
    private String serviceName;
    private TicketCustomerType customerType;
    private String customerName;
    private String customerPhone;
    private TicketStatus status;
    private Long priorityScore;
    private Long waitingMinutes;
    private Integer recallCount;
    private Integer version;
    private Boolean autoCompleted;
    private Integer actualDuration;
    private String counterStaffId;
    private String counterStaffName;
    private String counterLabel;
    private String providerId;
    private String providerName;
    private LocalDateTime issuedAt;
    private LocalDateTime calledAt;
    private LocalDateTime servedAt;
    private LocalDateTime completedAt;
}
