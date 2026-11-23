package com.nextturn.dto.ticket;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.nextturn.entity.TenantAppointment;
import com.nextturn.entity.TenantQueueTicket;
import com.nextturn.entity.TenantReceptionPoint;
import com.nextturn.entity.TenantService;
import com.nextturn.entity.TenantStaff;
import com.nextturn.enums.TicketCustomerType;
import com.nextturn.enums.TicketStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Response DTO representing an electronic queue ticket and real-time waiting metrics
 * (REQ-TICKET-01, REQ-TICKET-02, REQ-TICKET-04).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "Electronic queue ticket response with live queue position and wait estimates")
public class TicketResponse {

    private String id;
    private String ticketCode;
    private Integer sequenceNumber;
    private LocalDate workDate;

    private String serviceId;
    private String serviceCode;
    private String serviceName;
    private Integer serviceStandardDuration;

    private String appointmentId;
    private String appointmentCode;
    private String receptionPointId;
    private String receptionPointName;

    private String counterStaffId;
    private String counterStaffName;
    private String counterLabel;

    private String providerId;
    private String providerName;

    private Integer recallCount;
    private Integer version;
    private Boolean autoCompleted;
    private Integer actualDuration;

    private TicketCustomerType customerType;
    private String customerName;
    private String customerPhone;
    private TicketStatus status;

    @Schema(description = "Ticket code currently being called or served at counter", example = "A-018")
    private String currentServingNumber;

    @Schema(description = "1-based position of this ticket in the WAITING queue (1 = next to be called)", example = "3")
    private Long queuePosition;

    @Schema(description = "Number of waiting customers ahead of this ticket", example = "2")
    private Long peopleAheadCount;

    @Schema(description = "Estimated waiting time in minutes (peopleAheadCount * serviceStandardDuration)", example = "30")
    private Long estimatedWaitMinutes;

    @Schema(description = "True when peopleAheadCount <= 2 so UI triggers approaching turn alert")
    private Boolean approachingTurnAlert;

    @Schema(description = "One-time CSAT feedback token issued when ticket is COMPLETED (REQ-FEEDBACK-01)")
    private String feedbackToken;

    @Schema(description = "Expiration timestamp (24h after completion) for the feedback token")
    private LocalDateTime feedbackExpiresAt;

    private LocalDateTime issuedAt;
    private LocalDateTime calledAt;
    private LocalDateTime servedAt;
    private LocalDateTime completedAt;

    public static TicketResponse fromEntity(
            TenantQueueTicket ticket,
            String currentServingNumber,
            long peopleAheadCount,
            long queuePosition,
            long estimatedWaitMinutes
    ) {
        if (ticket == null) {
            return null;
        }
        TenantService service = ticket.getService();
        TenantAppointment appointment = ticket.getAppointment();
        TenantReceptionPoint receptionPoint = ticket.getReceptionPoint();
        TenantStaff counterStaff = ticket.getCounterStaff();
        TenantStaff provider = ticket.getProvider();

        return TicketResponse.builder()
                .id(ticket.getId())
                .ticketCode(ticket.getTicketCode())
                .sequenceNumber(ticket.getSequenceNumber())
                .workDate(ticket.getWorkDate())
                .serviceId(service != null ? service.getId() : null)
                .serviceCode(service != null ? service.getServiceCode() : null)
                .serviceName(service != null ? service.getServiceName() : null)
                .serviceStandardDuration(service != null ? service.getStandardDuration() : null)
                .appointmentId(appointment != null ? appointment.getId() : null)
                .appointmentCode(appointment != null ? appointment.getAppointmentCode() : null)
                .receptionPointId(receptionPoint != null ? receptionPoint.getId() : null)
                .receptionPointName(receptionPoint != null ? receptionPoint.getPointName() : null)
                .counterStaffId(counterStaff != null ? counterStaff.getId() : null)
                .counterStaffName(counterStaff != null ? counterStaff.getFullName() : null)
                .counterLabel(ticket.getCounterLabel())
                .providerId(provider != null ? provider.getId() : null)
                .providerName(provider != null ? provider.getFullName() : null)
                .recallCount(ticket.getRecallCount())
                .version(ticket.getVersion())
                .autoCompleted(ticket.getAutoCompleted())
                .actualDuration(ticket.getActualDuration())
                .customerType(ticket.getCustomerType())
                .customerName(ticket.getCustomerName())
                .customerPhone(ticket.getCustomerPhone())
                .status(ticket.getStatus())
                .currentServingNumber(currentServingNumber)
                .queuePosition(queuePosition)
                .peopleAheadCount(peopleAheadCount)
                .estimatedWaitMinutes(estimatedWaitMinutes)
                .approachingTurnAlert(ticket.getStatus() == TicketStatus.WAITING && peopleAheadCount <= 2)
                .feedbackToken(ticket.getFeedbackToken())
                .feedbackExpiresAt(ticket.getFeedbackTokenExpiresAt())
                .issuedAt(ticket.getIssuedAt())
                .calledAt(ticket.getCalledAt())
                .servedAt(ticket.getServedAt())
                .completedAt(ticket.getCompletedAt())
                .build();
    }
}
