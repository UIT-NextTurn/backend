package com.nextturn.exception.system;

import com.nextturn.exception.BaseException;

/**
 * Exception for system-level infrastructure failures.
 */
public class SystemException extends BaseException {

    public SystemException(SystemErrorCode errorCode) {
        super(errorCode);
    }

    public SystemException(SystemErrorCode errorCode, String customMessage) {
        super(errorCode, customMessage);
    }
}
