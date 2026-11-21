package com.nextturn.enums;

/**
 * Lifecycle states of a customer appointment booking (REQ-APT-03, REQ-APT-04, REQ-APT-05).
 */
public enum AppointmentStatus {
    PENDING,
    CONFIRMED,
    CHECKED_IN,
    COMPLETED,
    CANCELLED,
    NO_SHOW
}
