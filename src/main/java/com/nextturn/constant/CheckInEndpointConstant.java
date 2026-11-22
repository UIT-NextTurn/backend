package com.nextturn.constant;

/**
 * Centralized API endpoint constants for Check-in & Reception Point QR Management
 * (REQ-CHECK-IN-01, REQ-CHECK-IN-02, REQ-CHECK-IN-03).
 */
public final class CheckInEndpointConstant {

    private CheckInEndpointConstant() {
        // Utility class
    }

    public static final String ADMIN_RECEPTION_BASE = "/api/v1/check-in/reception-points";
    public static final String PUBLIC_CHECKIN_BASE = "/api/v1/public/check-in";

    public static final String BY_ID = "/{id}";
    public static final String QR_CODE = "/{id}/qr";
    public static final String ROTATE_STATIC_QR = "/{id}/rotate-static-qr";
    public static final String UPDATE_STATUS = "/{id}/status";

    public static final String RESOLVE_QR = "/resolve";
    public static final String APPOINTMENT_CHECKIN = "/appointment";
    public static final String WALKIN_CHECKIN = "/walk-in";
}
