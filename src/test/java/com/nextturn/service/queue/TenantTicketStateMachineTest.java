package com.nextturn.service.queue;

import com.nextturn.entity.TenantQueueTicket;
import com.nextturn.enums.TicketCustomerType;
import com.nextturn.enums.TicketStatus;
import com.nextturn.exception.queue.QueueErrorCode;
import com.nextturn.exception.queue.QueueException;
import com.nextturn.exception.ticket.TicketErrorCode;
import com.nextturn.exception.ticket.TicketException;
import com.nextturn.repository.ticket.TenantTicketAuditLogRepository;
import com.nextturn.service.queue.impl.TenantTicketStateMachineImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class TenantTicketStateMachineTest {

    @Mock
    private TenantTicketAuditLogRepository auditLogRepository;

    private TenantTicketStateMachineImpl stateMachine;

    @BeforeEach
    void setUp() {
        stateMachine = new TenantTicketStateMachineImpl(auditLogRepository);
    }

    @Test
    @DisplayName("FSM allows valid lifecycle paths and rejects illegal jumps")
    void fsmTransitions_EnforcesStrictGraph() {
        TenantQueueTicket ticket = TenantQueueTicket.builder()
                .id("tkt-1")
                .ticketCode("A-001")
                .status(TicketStatus.WAITING)
                .version(0)
                .build();

        assertThatCode(() -> stateMachine.assertValidTransition(ticket, TicketStatus.CALLING, 0))
                .doesNotThrowAnyException();

        assertThatThrownBy(() -> stateMachine.assertValidTransition(ticket, TicketStatus.COMPLETED, 0))
                .isInstanceOf(TicketException.class)
                .hasFieldOrPropertyWithValue("code", TicketErrorCode.INVALID_TICKET_STATE.code());

        ticket.setStatus(TicketStatus.CALLING);
        assertThatCode(() -> stateMachine.assertValidTransition(ticket, TicketStatus.CALLING, null)).doesNotThrowAnyException();
        assertThatCode(() -> stateMachine.assertValidTransition(ticket, TicketStatus.SERVING, 0)).doesNotThrowAnyException();
        assertThatCode(() -> stateMachine.assertValidTransition(ticket, TicketStatus.NO_SHOW, 0)).doesNotThrowAnyException();

        ticket.setStatus(TicketStatus.SERVING);
        assertThatCode(() -> stateMachine.assertValidTransition(ticket, TicketStatus.COMPLETED, 0)).doesNotThrowAnyException();
        assertThatThrownBy(() -> stateMachine.assertValidTransition(ticket, TicketStatus.CALLING, 0))
                .isInstanceOf(TicketException.class);

        ticket.setStatus(TicketStatus.COMPLETED);
        assertThatThrownBy(() -> stateMachine.assertValidTransition(ticket, TicketStatus.SERVING, 0))
                .isInstanceOf(TicketException.class);
    }

    @Test
    @DisplayName("Optimistic version mismatch throws 409 QUEUE_012 VERSION_CONFLICT")
    void versionMismatch_ThrowsConflict() {
        TenantQueueTicket ticket = TenantQueueTicket.builder()
                .id("tkt-1")
                .ticketCode("A-001")
                .status(TicketStatus.CALLING)
                .version(2)
                .build();

        assertThatThrownBy(() -> stateMachine.assertValidTransition(ticket, TicketStatus.SERVING, 1))
                .isInstanceOf(QueueException.class)
                .hasFieldOrPropertyWithValue("code", QueueErrorCode.VERSION_CONFLICT.code());
    }

    @Test
    @DisplayName("REQ-QC-01 Priority formula prevents walk-in starvation (25-min walk-in 125 > fresh appointment 100)")
    void priorityScore_AntiStarvationAgingWorks() {
        LocalDateTime now = LocalDateTime.of(2026, 11, 22, 10, 30);

        TenantQueueTicket freshAppointment = TenantQueueTicket.builder()
                .customerType(TicketCustomerType.APPOINTMENT)
                .issuedAt(now)
                .build();

        TenantQueueTicket agedWalkIn = TenantQueueTicket.builder()
                .customerType(TicketCustomerType.WALK_IN)
                .issuedAt(now.minusMinutes(25))
                .build();

        long aptScore = stateMachine.computePriorityScore(freshAppointment, now);
        long walkInScore = stateMachine.computePriorityScore(agedWalkIn, now);

        assertThat(aptScore).isEqualTo(100L);
        assertThat(walkInScore).isEqualTo(125L);
        assertThat(walkInScore).isGreaterThan(aptScore);

        stateMachine.recordAuditLog(
                TenantQueueTicket.builder().id("tkt-1").status(TicketStatus.CALLING).build(),
                TicketStatus.WAITING,
                TicketStatus.CALLING,
                "STAFF-0001"
        );
        verify(auditLogRepository).save(any());
    }
}
