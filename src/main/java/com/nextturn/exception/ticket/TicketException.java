package com.nextturn.exception.ticket;

import com.nextturn.exception.AppException;

/**
 * Exception for ticket operations and invalid lifecycle transitions.
 */
public class TicketException extends AppException {

    public TicketException(TicketErrorCode errorCode) {
        super(errorCode);
    }

    public TicketException(TicketErrorCode errorCode, String customMessage) {
        super(errorCode, customMessage);
    }
}
