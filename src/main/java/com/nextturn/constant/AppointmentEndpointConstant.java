package com.nextturn.constant;

/**
 * Centralized API endpoint path constants for the Appointment module (REQ-APT-01 to REQ-APT-05).
 */
public final class AppointmentEndpointConstant {

    private AppointmentEndpointConstant() {
        // Prevent instantiation
    }

    public static final String APPOINTMENTS_BASE = "/api/v1/appointments";
    public static final String PUBLIC_APPOINTMENTS_BASE = "/api/v1/public/appointments";
    public static final String CUSTOMER_APPOINTMENTS_BASE = "/api/v1/customer/appointments";

    public static final String APPOINTMENT_BY_ID = "/{appointmentId}";
    public static final String APPOINTMENT_LOOKUP = "/lookup";
    public static final String APPOINTMENT_CANCEL = "/{appointmentId}/cancel";
    public static final String APPOINTMENT_RESCHEDULE = "/{appointmentId}/reschedule";
    public static final String MY_APPOINTMENTS = "/my";
}
