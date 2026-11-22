package com.nextturn.service.queue.impl;

import com.nextturn.entity.TenantQueueTicket;
import com.nextturn.entity.TenantTicketAuditLog;
import com.nextturn.enums.TicketStatus;
import com.nextturn.exception.queue.QueueErrorCode;
import com.nextturn.exception.queue.QueueException;
import com.nextturn.exception.ticket.TicketErrorCode;
import com.nextturn.exception.ticket.TicketException;
import com.nextturn.repository.ticket.TenantTicketAuditLogRepository;
import com.nextturn.service.queue.TenantTicketStateMachine;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Default implementation of {@link TenantTicketStateMachine} enforcing valid ticket FSM transitions,
 * optimistic version matching, and non-destructive audit trail recording (REQ-QC-01..04).
 */
@Component
public class TenantTicketStateMachineImpl implements TenantTicketStateMachine {

    private static final Map<TicketStatus, Set<TicketStatus>> ALLOWED_TRANSITIONS = new EnumMap<>(TicketStatus.class);

    static {
        ALLOWED_TRANSITIONS.put(TicketStatus.WAITING, Set.of(TicketStatus.CALLING));
        ALLOWED_TRANSITIONS.put(TicketStatus.CALLING, Set.of(TicketStatus.CALLING, TicketStatus.SERVING, TicketStatus.NO_SHOW));
        ALLOWED_TRANSITIONS.put(TicketStatus.SERVING, Set.of(TicketStatus.COMPLETED));
        ALLOWED_TRANSITIONS.put(TicketStatus.COMPLETED, Set.of());
        ALLOWED_TRANSITIONS.put(TicketStatus.NO_SHOW, Set.of());
    }

    private final TenantTicketAuditLogRepository auditLogRepository;

    @Autowired
    public TenantTicketStateMachineImpl(
            @Autowired(required = false) TenantTicketAuditLogRepository auditLogRepository
    ) {
        this.auditLogRepository = auditLogRepository;
    }

    @Override
    public void assertValidTransition(TenantQueueTicket ticket, TicketStatus targetStatus, Integer expectedVersion) {
        if (ticket == null) {
            throw new TicketException(TicketErrorCode.TICKET_NOT_FOUND);
        }
        if (expectedVersion != null && !Objects.equals(expectedVersion, ticket.getVersion())) {
            throw new QueueException(QueueErrorCode.VERSION_CONFLICT);
        }

        TicketStatus currentStatus = ticket.getStatus();
        Set<TicketStatus> allowedTargets = ALLOWED_TRANSITIONS.getOrDefault(currentStatus, Set.of());
        if (!allowedTargets.contains(targetStatus)) {
            throw new TicketException(
                    TicketErrorCode.INVALID_TICKET_STATE,
                    String.format("Không thể chuyển trạng thái vé [%s] từ %s sang %s.",
                            ticket.getTicketCode(), currentStatus, targetStatus)
            );
        }
    }

    @Override
    public void recordAuditLog(TenantQueueTicket ticket, TicketStatus previousState, TicketStatus newState, String actorId) {
        if (auditLogRepository == null || ticket == null) {
            return;
        }
        String safeActor = (actorId != null && !actorId.isBlank()) ? actorId.trim() : "SYSTEM";
        TenantTicketAuditLog auditLog = TenantTicketAuditLog.builder()
                .id(UUID.randomUUID().toString())
                .ticket(ticket)
                .actorId(safeActor)
                .previousState(previousState != null ? previousState : ticket.getStatus())
                .newState(newState != null ? newState : ticket.getStatus())
                .build();
        auditLogRepository.save(auditLog);
    }
}
