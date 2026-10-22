package com.nextturn.exception.auth;

import com.nextturn.exception.BaseErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;
import org.springframework.http.HttpStatus;

/**
 * Authentication and Keycloak identity routing error codes.
 */
@Getter
@Accessors(fluent = true)
@RequiredArgsConstructor
public enum AuthErrorCode implements BaseErrorCode {

    TENANT_CODE_REQUIRED("AUTH_001", "Tenant code is required", HttpStatus.BAD_REQUEST),
    INVALID_TENANT_CODE_FORMAT("AUTH_002", "Tenant code must contain only lowercase alphanumeric characters and underscores", HttpStatus.BAD_REQUEST),
    TENANT_NOT_FOUND("AUTH_003", "Tenant not found with provided code", HttpStatus.NOT_FOUND),
    TENANT_INACTIVE("AUTH_004", "Tenant facility is not active", HttpStatus.FORBIDDEN),
    INVALID_CREDENTIALS("AUTH_005", "Invalid username or password", HttpStatus.UNAUTHORIZED),
    TOKEN_EXPIRED("AUTH_006", "Authentication token has expired", HttpStatus.UNAUTHORIZED),
    INVALID_TOKEN("AUTH_007", "Malformed or invalid authentication token", HttpStatus.UNAUTHORIZED),
    REFRESH_TOKEN_EXPIRED("AUTH_008", "Refresh token has expired or been revoked", HttpStatus.UNAUTHORIZED),
    TENANT_MISMATCH("AUTH_009", "Authenticated user tenant claim does not match requested tenant", HttpStatus.FORBIDDEN),
    ROLE_NOT_PERMITTED("AUTH_010", "User role is not permitted for this action or portal", HttpStatus.FORBIDDEN),
    KEYCLOAK_UNAVAILABLE("AUTH_011", "Keycloak identity provider service is currently unavailable", HttpStatus.SERVICE_UNAVAILABLE),
    LOGIN_URL_BUILD_FAILED("AUTH_012", "Failed to generate staff login redirect URL", HttpStatus.INTERNAL_SERVER_ERROR);

    private final String code;
    private final String message;
    private final HttpStatus httpStatus;
}
