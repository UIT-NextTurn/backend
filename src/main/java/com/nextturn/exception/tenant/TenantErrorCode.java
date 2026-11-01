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
    TENANT_QUOTA_EXCEEDED("TENANT_009", "Monthly ticket or active counter quota exceeded for tenant tier", HttpStatus.TOO_MANY_REQUESTS),
    TENANT_OR_EMAIL_ALREADY_EXISTS("TENANT_010", "Mã định danh tổ chức hoặc email quản trị này đã được đăng ký trong hệ thống. Vui lòng kiểm tra lại.", HttpStatus.CONFLICT),
    AUTH_SERVICE_SYNC_FAILED("TENANT_011", "Quá trình đồng bộ tài khoản sang Keycloak thất bại. Vui lòng kiểm tra dịch vụ xác thực.", HttpStatus.INTERNAL_SERVER_ERROR),
    STAFF_QUOTA_BELOW_CURRENT("TENANT_012", "Hạn ngạch nhân sự mới không được nhỏ hơn số lượng nhân viên hiện có của cơ sở.", HttpStatus.BAD_REQUEST),
    TENANT_NOT_ACTIVE("TENANT_013", "Tổ chức không ở trạng thái hoạt động (ACTIVE).", HttpStatus.BAD_REQUEST),
    INVALID_QUOTA_VALUE("TENANT_014", "Giá trị hạn ngạch không hợp lệ. Phải là số nguyên dương >= 1 hoặc -1 (không giới hạn).", HttpStatus.BAD_REQUEST),
    INVALID_ADMIN_EMAIL_FORMAT("TENANT_015", "Email không hợp lệ: không được chứa ký tự '+', không bắt đầu/kết thúc bằng dấu '.', không chứa dấu chấm liên tiếp '..', và phải có tên miền hợp lệ.", HttpStatus.BAD_REQUEST);

    private final String code;
    private final String message;
    private final HttpStatus httpStatus;
}
