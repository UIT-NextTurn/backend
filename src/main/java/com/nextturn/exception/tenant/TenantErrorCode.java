package com.nextturn.exception.tenant;

import com.nextturn.exception.BaseErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;
import org.springframework.http.HttpStatus;

/**
 * Multi-tenancy isolation and lifecycle error codes.
 */
@Getter
@Accessors(fluent = true)
@RequiredArgsConstructor
public enum TenantErrorCode implements BaseErrorCode {

    TENANT_NOT_FOUND("TENANT_001", "Tenant not found", HttpStatus.NOT_FOUND),
    TENANT_CODE_ALREADY_EXISTS("TENANT_002", "Tenant code is already registered", HttpStatus.CONFLICT),
    TENANT_SLUG_INVALID("TENANT_003", "Tenant slug contains invalid characters", HttpStatus.BAD_REQUEST),
    TENANT_SUSPENDED("TENANT_004", "Tenant operations are paused or suspended", HttpStatus.FORBIDDEN),
    TENANT_SUBSCRIPTION_EXPIRED("TENANT_005", "Tenant subscription tier has expired", HttpStatus.PAYMENT_REQUIRED),
    TENANT_SCHEMA_CREATION_FAILED("TENANT_006", "Failed to provision tenant database schema", HttpStatus.INTERNAL_SERVER_ERROR),
    TENANT_SCHEMA_MIGRATION_FAILED("TENANT_007", "Flyway migration failed for tenant schema", HttpStatus.INTERNAL_SERVER_ERROR),
    TENANT_CONTEXT_MISSING("TENANT_008", "Multi-tenant routing context is missing or could not be resolved", HttpStatus.BAD_REQUEST),
    TENANT_QUOTA_EXCEEDED("TENANT_009", "Monthly ticket or active counter quota exceeded for tenant tier", HttpStatus.TOO_MANY_REQUESTS);

    private final String code;
    private final String message;
    private final HttpStatus httpStatus;
}
