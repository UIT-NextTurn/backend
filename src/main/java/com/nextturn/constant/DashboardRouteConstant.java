package com.nextturn.constant;

/**
 * Constants representing frontend dashboard navigation routes by user role
 * (*đường dẫn điều hướng màn hình dashboard theo vai trò người dùng*).
 */
public final class DashboardRouteConstant {

    private DashboardRouteConstant() {
        // Prevent instantiation
    }

    public static final String SUPERADMIN_DASHBOARD = "/admin/dashboard";
    public static final String TENANT_ADMIN_DASHBOARD = "/tenant/dashboard";
    public static final String COUNTER_STAFF_DASHBOARD = "/staff/counter";
    public static final String CUSTOMER_DASHBOARD = "/customer/home";
}
