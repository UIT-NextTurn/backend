package com.nextturn.constant;

/**
 * Centralized API endpoint constants for Notification Templates, Delivery Logs,
 * and Appointment Reminder dispatch (REQ-NOTI-01 to REQ-NOTI-04).
 */
public final class NotificationEndpointConstant {

    private NotificationEndpointConstant() {
        // Utility class
    }

    public static final String NOTIFICATION_BASE = "/api/v1/notifications";
    public static final String TEMPLATES = "/templates";
    public static final String TEMPLATE_BY_CODE = "/templates/{templateCode}";
    public static final String LOGS = "/logs";
    public static final String TRIGGER_REMINDERS = "/reminders/run";
}
