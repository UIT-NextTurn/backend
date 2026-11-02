package com.nextturn.exception.ticket;

import com.nextturn.exception.BaseErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;
import org.springframework.http.HttpStatus;

/**
 * Queue ticket lifecycle and state machine transition error codes.
 */
@Getter
@Accessors(fluent = true)
@RequiredArgsConstructor
public enum TicketErrorCode implements BaseErrorCode {

    TICKET_NOT_FOUND("TICKET_001", "Ticket not found", HttpStatus.NOT_FOUND),
    INVALID_TICKET_STATE("TICKET_002", "Requested ticket action is invalid for current ticket state", HttpStatus.BAD_REQUEST),
    TICKET_ALREADY_CALLED("TICKET_003", "Ticket is already being called or served", HttpStatus.CONFLICT),
    TICKET_ALREADY_COMPLETED("TICKET_004", "Ticket has already been completed or closed", HttpStatus.BAD_REQUEST),
    TICKET_ALREADY_CANCELLED("TICKET_005", "Ticket was cancelled and cannot be recalled", HttpStatus.BAD_REQUEST),
    TICKET_MAX_RECALLS_EXCEEDED("TICKET_006", "Maximum recall attempts reached; ticket must be marked NO-SHOW", HttpStatus.BAD_REQUEST),
    TICKET_TRANSFER_FAILED("TICKET_007", "Unable to transfer ticket to target queue or counter", HttpStatus.INTERNAL_SERVER_ERROR),
    TICKET_HOLD_LIMIT_REACHED("TICKET_008", "Hold duration limit exceeded for ticket", HttpStatus.BAD_REQUEST);

    private final String code;
    private final String message;
    private final HttpStatus httpStatus;
}
