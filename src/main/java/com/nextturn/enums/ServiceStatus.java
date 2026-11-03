package com.nextturn.enums;

/**
 * Operating status for tenant services.
 * Mapped to the SQL enum check constraint on {@code tenant__services.status}.
 */
public enum ServiceStatus {
    ACTIVE,
    INACTIVE;

    public static final String ACTIVE_VALUE = "ACTIVE";
    public static final String INACTIVE_VALUE = "INACTIVE";
}
