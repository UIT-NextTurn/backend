package com.nextturn.enums;

/**
 * Lifecycle states of an electronic queue ticket ({@code tenant__queue_tickets.status}).
 */
public enum TicketStatus {
    WAITING,
    CALLING,
    SERVING,
    COMPLETED,
    NO_SHOW
}
