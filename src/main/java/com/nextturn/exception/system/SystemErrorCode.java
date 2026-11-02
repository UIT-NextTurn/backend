package com.nextturn.exception.system;

import com.nextturn.exception.BaseErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;
import org.springframework.http.HttpStatus;

/**
 * System-level technical and infrastructure error codes.
 */
@Getter
@Accessors(fluent = true)
@RequiredArgsConstructor
public enum SystemErrorCode implements BaseErrorCode {

    INTERNAL_ERROR("SYS_001", "An unexpected error occurred", HttpStatus.INTERNAL_SERVER_ERROR),
    VALIDATION_ERROR("SYS_002", "Validation failed", HttpStatus.BAD_REQUEST),
    RESOURCE_NOT_FOUND("SYS_003", "Resource not found", HttpStatus.NOT_FOUND),
    METHOD_NOT_ALLOWED("SYS_004", "HTTP method not supported", HttpStatus.METHOD_NOT_ALLOWED),
    DATABASE_ERROR("SYS_005", "Database error occurred", HttpStatus.INTERNAL_SERVER_ERROR),
    INVALID_PARAMETER("SYS_006", "Invalid parameter provided", HttpStatus.BAD_REQUEST),
    INVALID_MULTIPART("SYS_007", "Invalid multipart request. Please check your file upload.", HttpStatus.BAD_REQUEST),
    UNAUTHORIZED("SYS_008", "Unauthorized access", HttpStatus.UNAUTHORIZED),
    FORBIDDEN("SYS_009", "Forbidden access by user role", HttpStatus.FORBIDDEN),
    RATE_LIMIT_EXCEEDED("SYS_010", "Too many requests. Please slow down.", HttpStatus.TOO_MANY_REQUESTS);

    private final String code;
    private final String message;
    private final HttpStatus httpStatus;
}
