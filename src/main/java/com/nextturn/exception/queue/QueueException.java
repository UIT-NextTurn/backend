package com.nextturn.exception.queue;

import com.nextturn.exception.AppException;

/**
 * Exception for queue management and intake errors.
 */
public class QueueException extends AppException {

    public QueueException(QueueErrorCode errorCode) {
        super(errorCode);
    }

    public QueueException(QueueErrorCode errorCode, String customMessage) {
        super(errorCode, customMessage);
    }
}
