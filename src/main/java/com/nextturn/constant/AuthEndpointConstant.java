package com.nextturn.constant;

/**
 * Centralized API endpoints for authentication controllers
 * (*các hằng số đường dẫn API xác thực*).
 */
public final class AuthEndpointConstant {

    private AuthEndpointConstant() {
        // Prevent instantiation
    }

    public static final String AUTH_BASE = "/api/v1/auth";
    public static final String CUSTOMER_AUTH_BASE = "/api/v1/auth/customer";

    // Customer OTP endpoints
    public static final String OTP_SEND = "/otp/send";
    public static final String OTP_REQUEST = "/otp/request";
    public static final String OTP_VERIFY = "/otp/verify";

    // Tenant authentication endpoints
    public static final String TENANT_VERIFY = "/tenant/verify";
    public static final String TENANT_BY_CODE = "/tenant/{tenantCode}";
    public static final String TENANT_VERIFY_BY_CODE = "/tenant/verify/{tenantCode}";
    public static final String SUPERADMIN_LOGIN_URL = "/superadmin/login-url";
    public static final String TOKEN_EXCHANGE = "/token";
    public static final String TOKEN_EXCHANGE_ALIAS = "/exchange";
    public static final String TOKEN_REFRESH = "/refresh";

    // Tenant Admin auth management endpoints
    public static final String TENANT_ADMIN_STAFF_BASE = "/api/v1/auth/staff";
    public static final String TENANT_ADMIN_IDP_BASE = "/api/v1/auth/idp";
}
