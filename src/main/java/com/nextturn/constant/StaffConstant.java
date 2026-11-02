package com.nextturn.constant;

/**
 * Standard constants for tenant staff identification, badge code formats, and initial seeding.
 * Compliant with REQ-STAFF-01 specification (STAFF-xxxx format).
 */
public final class StaffConstant {

    private StaffConstant() {
        // Prevent instantiation
    }

    /**
     * Standard prefix for staff member identifier badge code (REQ-STAFF-01).
     * Format: STAFF-xxxx (e.g. STAFF-0001, STAFF-000123).
     */
    public static final String STAFF_CODE_PREFIX = "STAFF-";

    /**
     * Standard badge code assigned to the initial facility administrator upon database provisioning.
     */
    public static final String INITIAL_ADMIN_STAFF_CODE = "STAFF-0001";
}
