package com.nextturn.exception.admin;

import com.nextturn.exception.BaseErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;
import org.springframework.http.HttpStatus;

/**
 * Super Admin command portal and Tenant Admin operations error codes.
 */
@Getter
@Accessors(fluent = true)
@RequiredArgsConstructor
public enum AdminErrorCode implements BaseErrorCode {

    IMPERSONATION_DENIED("ADMIN_001", "Tenant impersonation not permitted without valid reason or active session", HttpStatus.FORBIDDEN),
    CROSS_TENANT_ACCESS_DENIED("ADMIN_002", "Cross-tenant data manipulation is strictly prohibited", HttpStatus.FORBIDDEN),
    BRANCH_NOT_FOUND("ADMIN_003", "Tenant branch not found", HttpStatus.NOT_FOUND),
    STAFF_USER_NOT_FOUND("ADMIN_004", "Staff user not found in tenant organization", HttpStatus.NOT_FOUND),
    ROLE_ASSIGNMENT_INVALID("ADMIN_005", "Invalid staff role assignment for tenant context", HttpStatus.BAD_REQUEST),
    STAFF_ALREADY_EXISTS("ADMIN_006", "Staff member with this email, code, or phone number already exists", HttpStatus.CONFLICT),
    IDP_CONFIGURATION_FAILED("ADMIN_007", "Failed to configure external identity provider in Keycloak", HttpStatus.INTERNAL_SERVER_ERROR),
    IDP_NOT_FOUND("ADMIN_008", "Identity provider not found for this tenant organization", HttpStatus.NOT_FOUND);

    private final String code;
    private final String message;
    private final HttpStatus httpStatus;
}
