package com.nextturn.exception.appointment;

import com.nextturn.exception.AppException;

/**
 * Exception for appointment reservation and check-in failures.
 */
public class AppointmentException extends AppException {

    public AppointmentException(AppointmentErrorCode errorCode) {
        super(errorCode);
    }

    public AppointmentException(AppointmentErrorCode errorCode, String customMessage) {
        super(errorCode, customMessage);
    }
}
