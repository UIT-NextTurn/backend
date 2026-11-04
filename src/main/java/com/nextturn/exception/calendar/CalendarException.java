package com.nextturn.exception.calendar;

import com.nextturn.exception.AppException;

/**
 * Domain exception for Tenant Calendar and shift capacity scheduling operations.
 */
public class CalendarException extends AppException {

    public CalendarException(CalendarErrorCode errorCode) {
        super(errorCode);
    }

    public CalendarException(CalendarErrorCode errorCode, String customMessage) {
        super(errorCode, customMessage);
    }
}

