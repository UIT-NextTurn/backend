package com.nextturn.service.ticket;

import com.nextturn.dto.ticket.IssueTicketRequest;
import com.nextturn.dto.ticket.TicketResponse;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.LocalDate;
import java.util.List;

/**
 * Primary service contract for Electronic Queue Ticket Issuance, Real-Time Tracking,
 * SSE Progress Updates, and Ticket Recovery (REQ-TICKET-01 to REQ-TICKET-04, REQ-CHECK-IN-01, REQ-CHECK-IN-02).
 */
public interface TenantTicketService {

    /**
     * Issues a strictly monotonic, unique daily electronic queue ticket protected by
     * a Valkey distributed mutex lock and atomic Lua sequence counter (REQ-TICKET-01).
     * Supports both pre-booked appointment QR check-in and walk-in registration.
     *
     * @param request ticket issuance request
     * @return issued ticket response with live queue position and wait estimate
     */
    TicketResponse issueTicket(IssueTicketRequest request);

    /**
     * Retrieves live virtual queue ticket details and wait metrics (REQ-TICKET-02).
     */
    TicketResponse getTicketDetails(String ticketIdOrCode);

    /**
     * Subscribes to live SSE updates for a virtual ticket screen (REQ-TICKET-03).
     */
    SseEmitter subscribeToTicketStream(String ticketIdOrCode);

    /**
     * Subscribes to live SSE updates with optional {@code Last-Event-ID} replay support.
     */
    default SseEmitter subscribeToTicketStream(String ticketIdOrCode, String lastEventId) {
        return subscribeToTicketStream(ticketIdOrCode);
    }

    /**
     * Recovers active tickets issued today for the given customer phone number (REQ-TICKET-04).
     */
    List<TicketResponse> recoverTicketsByPhoneToday(String customerPhone);

    /**
     * Lists tickets for a specific date and optional service filter.
     */
    List<TicketResponse> getTicketsForDate(LocalDate workDate, String serviceId);
}
