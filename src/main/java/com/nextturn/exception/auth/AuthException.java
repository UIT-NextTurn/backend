package com.nextturn.exception.auth;

import com.nextturn.exception.AppException;

/**
 * Exception for authentication, token validation, and tenant identification failures.
 */
public class AuthException extends AppException {

    public AuthException(AuthErrorCode errorCode) {
        super(errorCode);
    }

    public AuthException(AuthErrorCode errorCode, String customMessage) {
        super(errorCode, customMessage);
    }
}
