package com.nextturn.exception.admin;

import com.nextturn.exception.AppException;

/**
 * Exception for administrative operations and security perimeter boundaries.
 */
public class AdminException extends AppException {

    public AdminException(AdminErrorCode errorCode) {
        super(errorCode);
    }

    public AdminException(AdminErrorCode errorCode, String customMessage) {
        super(errorCode, customMessage);
    }
}
