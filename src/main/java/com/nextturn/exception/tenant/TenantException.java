package com.nextturn.exception.tenant;

import com.nextturn.exception.AppException;

/**
 * Exception for tenant lifecycle and schema routing failures.
 */
public class TenantException extends AppException {

    public TenantException(TenantErrorCode errorCode) {
        super(errorCode);
    }

    public TenantException(TenantErrorCode errorCode, String customMessage) {
        super(errorCode, customMessage);
    }
}
