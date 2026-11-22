package com.nextturn.service.ticket;

import com.nextturn.dto.ticket.TicketResponse;
import com.nextturn.entity.TenantQueueTicket;

import java.time.LocalDate;
import java.util.List;

/**
 * Read-only query contract for virtual queue ticket status, live waiting metrics
 * ({@code current_serving_number}, {@code queue_position}, {@code people_ahead_count},
 * {@code estimated_wait_minutes}), and ticket recovery by phone number (REQ-TICKET-02, REQ-TICKET-04).
 */
public interface TenantTicketQueryService {

    /**
     * Retrieves live virtual queue ticket details by ticket UUID or ticket code for today (REQ-TICKET-02).
     */
    TicketResponse getTicketDetails(String ticketIdOrCode);

    /**
     * Enriches a ticket entity with real-time queue metrics (position, people ahead, current serving number, estimated wait).
     */
    TicketResponse enrichWithQueueMetrics(TenantQueueTicket ticket);

    /**
     * Recovers active tickets ({@code WAITING}, {@code CALLING}, {@code SERVING}) issued today
     * for the given customer phone number (REQ-TICKET-04).
     */
    List<TicketResponse> recoverTicketsByPhoneToday(String customerPhone);

    /**
     * Lists all queue tickets for the specified date and optional service filter (for staff/admin console).
     */
    List<TicketResponse> getTicketsForDate(LocalDate workDate, String serviceId);
}
