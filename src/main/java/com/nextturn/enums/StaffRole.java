package com.nextturn.enums;

/**
 * Functional staff roles within a tenant facility (clinic/hospital).
 * Mapped to the SQL enum check constraint on {@code tenant__staffs.role}.
 */
public enum StaffRole {
    ADMIN,
    COUNTER,
    SPECIALIST;

    public static final String ADMIN_VALUE = "ADMIN";
    public static final String COUNTER_VALUE = "COUNTER";
    public static final String SPECIALIST_VALUE = "SPECIALIST";
}
