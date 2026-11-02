package com.nextturn.enums;

/**
 * Functional staff roles within a tenant facility (clinic/hospital).
 * Mapped to the SQL enum check constraint on {@code tenant__staffs.role}.
 * <p>
 * Authentication access:
 * - {@link #ADMIN}: Tenant Administrator (can log in to tenant admin portal)
 * - {@link #COUNTER}: Encounter / Counter Staff (can log in to counter desk dashboard)
 * - {@link #SPECIALIST}: Medical specialist or doctor roster (management only; cannot log in to the application)
 */
public enum StaffRole {
    ADMIN,
    COUNTER,
    SPECIALIST;

    public static final String ADMIN_VALUE = "ADMIN";
    public static final String COUNTER_VALUE = "COUNTER";
    public static final String SPECIALIST_VALUE = "SPECIALIST";
}
