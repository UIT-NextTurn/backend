package com.nextturn.exception.counter;

import com.nextturn.exception.AppException;

/**
 * Exception for counter operations and assignments.
 */
public class CounterException extends AppException {

    public CounterException(CounterErrorCode errorCode) {
        super(errorCode);
    }

    public CounterException(CounterErrorCode errorCode, String customMessage) {
        super(errorCode, customMessage);
    }
}
