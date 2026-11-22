package com.nextturn.constant;

/**
 * Centralized API endpoint constants for Electronic Queue Ticket Issuance & Real-Time Tracking
 * (REQ-TICKET-01 to REQ-TICKET-04).
 */
public final class TicketEndpointConstant {

    private TicketEndpointConstant() {
        // Prevent instantiation
    }

    public static final String PUBLIC_TICKETS_BASE = "/api/v1/public/tickets";
    public static final String TICKETS_BASE = "/api/v1/tickets";

    public static final String TICKET_BY_ID_OR_CODE = "/{ticketIdOrCode}";
    public static final String TICKET_STREAM = "/{ticketIdOrCode}/stream";
    public static final String TICKET_RECOVER = "/recover";
}
