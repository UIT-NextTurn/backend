package com.nextturn.exception.notification;

import com.nextturn.exception.AppException;

/**
 * Exception for notification gateway and delivery failures.
 */
public class NotificationException extends AppException {

    public NotificationException(NotificationErrorCode errorCode) {
        super(errorCode);
    }

    public NotificationException(NotificationErrorCode errorCode, String customMessage) {
        super(errorCode, customMessage);
    }
}
