package com.nextturn.service.queue;

import com.nextturn.entity.TenantQueueTicket;
import com.nextturn.enums.TicketCustomerType;
import com.nextturn.enums.TicketStatus;

import java.time.LocalDateTime;

/**
 * Strict Finite State Machine (FSM) guard for queue ticket lifecycle transitions,
 * optimistic version checks, audit logging, and dynamic priority scoring (REQ-QC-01..04).
 */
public interface TenantTicketStateMachine {

    long APPOINTMENT_BASE_WEIGHT = 100L;
    long WALK_IN_BASE_WEIGHT = 0L;
    long AGING_POINTS_PER_MINUTE = 5L;

    /**
     * Validates that transitioning {@code ticket} from its current status to {@code targetStatus}
     * is permitted by the FSM and checks {@code expectedVersion} if supplied.
     */
    void assertValidTransition(TenantQueueTicket ticket, TicketStatus targetStatus, Integer expectedVersion);

    /**
     * Appends an immutable audit log entry for a ticket state transition (Section 3.16).
     */
    void recordAuditLog(TenantQueueTicket ticket, TicketStatus previousState, TicketStatus newState, String actorId);

    /**
     * Computes live priority score using SRS formula:
     * {@code Priority_Score = W_base + (waitingMinutes * 5)}.
     */
    default long computePriorityScore(TenantQueueTicket ticket, LocalDateTime now) {
        if (ticket == null) {
            return 0L;
        }
        long baseWeight = ticket.getCustomerType() == TicketCustomerType.APPOINTMENT
                ? APPOINTMENT_BASE_WEIGHT
                : WALK_IN_BASE_WEIGHT;
        long waitingMinutes = computeWaitingMinutes(ticket, now);
        return baseWeight + (waitingMinutes * AGING_POINTS_PER_MINUTE);
    }

    /**
     * Computes non-negative waiting minutes elapsed since {@code issuedAt}.
     */
    default long computeWaitingMinutes(TenantQueueTicket ticket, LocalDateTime now) {
        if (ticket == null || ticket.getIssuedAt() == null || now == null || now.isBefore(ticket.getIssuedAt())) {
            return 0L;
        }
        return java.time.Duration.between(ticket.getIssuedAt(), now).toMinutes();
    }
}
