package com.nextturn.service.queue;

import com.nextturn.dto.queue.CallNextTicketRequest;
import com.nextturn.dto.queue.LobbyDisplayResponse;
import com.nextturn.dto.queue.QueueBoardResponse;
import com.nextturn.dto.queue.ServeTicketRequest;
import com.nextturn.dto.queue.TicketTransitionRequest;
import com.nextturn.dto.ticket.TicketResponse;
import com.nextturn.infrastructure.security.principal.AuthenticatedUser;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Primary service contract for Counter Staff Queue Calling FSM workflows (REQ-QC-01 to REQ-QC-04).
 */
public interface TenantQueueCallingService {

    TicketResponse callNextTicket(CallNextTicketRequest request, AuthenticatedUser caller);

    TicketResponse recallTicket(String ticketId, TicketTransitionRequest request, AuthenticatedUser caller);

    TicketResponse serveTicket(String ticketId, ServeTicketRequest request, AuthenticatedUser caller);

    TicketResponse completeTicket(String ticketId, TicketTransitionRequest request, AuthenticatedUser caller);

    TicketResponse markNoShow(String ticketId, TicketTransitionRequest request, AuthenticatedUser caller);

    int autoCompleteOverdueTickets();

    QueueBoardResponse getQueueBoard(String serviceId);

    LobbyDisplayResponse getLobbyDisplay();

    SseEmitter subscribeToLobbyDisplay();

    default SseEmitter subscribeToLobbyDisplay(String lastEventId) {
        return subscribeToLobbyDisplay();
    }
}
