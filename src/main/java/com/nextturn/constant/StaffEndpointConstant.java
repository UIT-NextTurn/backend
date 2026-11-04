package com.nextturn.constant;

/**
 * Endpoint route constants for Staff Management module.
 */
public final class StaffEndpointConstant {

    private StaffEndpointConstant() {
        // Prevent instantiation
    }

    public static final String STAFF_BASE = "/api/v1/staff";
    public static final String STAFF_BY_ID = "/{staffId}";
    public static final String STAFF_SERVICES = "/{staffId}/services";
}

