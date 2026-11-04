package com.nextturn.exception.staff;

import com.nextturn.exception.AppException;

/**
 * Domain exception for Tenant Staff operations.
 */
public class StaffException extends AppException {

    public StaffException(StaffErrorCode errorCode) {
        super(errorCode);
    }

    public StaffException(StaffErrorCode errorCode, String customMessage) {
        super(errorCode, customMessage);
    }
}

