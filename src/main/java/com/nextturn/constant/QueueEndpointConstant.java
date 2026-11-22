package com.nextturn.constant;

/**
 * Centralized API endpoint constants for Counter Queue Calling & Lobby Display
 * (REQ-QC-01 to REQ-QC-04).
 */
public final class QueueEndpointConstant {

    private QueueEndpointConstant() {
        // Utility class
    }

    public static final String QUEUE_BASE = "/api/v1/queue";
    public static final String PUBLIC_QUEUE_BASE = "/api/v1/public/queue";

    public static final String CALL_NEXT = "/call-next";
    public static final String BOARD = "/board";

    public static final String TICKET_RECALL = "/tickets/{ticketId}/recall";
    public static final String TICKET_SERVE = "/tickets/{ticketId}/serve";
    public static final String TICKET_COMPLETE = "/tickets/{ticketId}/complete";
    public static final String TICKET_NO_SHOW = "/tickets/{ticketId}/no-show";

    public static final String LOBBY_DISPLAY = "/display";
    public static final String LOBBY_DISPLAY_STREAM = "/display/stream";
}
