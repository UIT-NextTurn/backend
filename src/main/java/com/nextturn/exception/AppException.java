package com.nextturn.exception;

import com.nextturn.exception.system.SystemErrorCode;
import org.springframework.http.HttpStatus;

/**
 * Base custom runtime exception for NextTurn application errors.
 * Bridges domain-specific error codes with HTTP response codes while maintaining backwards compatibility.
 */
public class AppException extends BaseException {

    public AppException(BaseErrorCode errorCode) {
        super(errorCode);
    }

    public AppException(BaseErrorCode errorCode, String customMessage) {
        super(errorCode, customMessage);
    }

    public AppException(String message) {
        super(SystemErrorCode.INTERNAL_ERROR, message);
    }

    public AppException(int status, String message) {
        super(resolveFallbackErrorCode(status), message);
    }

    public AppException(int status, String message, Throwable cause) {
        super(resolveFallbackErrorCode(status), message);
        if (cause != null) {
            initCause(cause);
        }
    }

    private static BaseErrorCode resolveFallbackErrorCode(int status) {
        HttpStatus httpStatus = HttpStatus.resolve(status);
        if (httpStatus == null) {
            httpStatus = HttpStatus.INTERNAL_SERVER_ERROR;
        }
        return switch (httpStatus) {
            case BAD_REQUEST -> SystemErrorCode.INVALID_PARAMETER;
            case UNAUTHORIZED -> SystemErrorCode.UNAUTHORIZED;
            case FORBIDDEN -> SystemErrorCode.FORBIDDEN;
            case NOT_FOUND -> SystemErrorCode.RESOURCE_NOT_FOUND;
            case METHOD_NOT_ALLOWED -> SystemErrorCode.METHOD_NOT_ALLOWED;
            case TOO_MANY_REQUESTS -> SystemErrorCode.RATE_LIMIT_EXCEEDED;
            default -> SystemErrorCode.INTERNAL_ERROR;
        };
    }
}
