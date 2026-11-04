package com.nextturn.constant;

/**
 * Route endpoint constants for Calendar, staff shift scheduling, capacity slot operations,
 * and tenant booking policies.
 */
public final class CalendarEndpointConstant {

    private CalendarEndpointConstant() {
        // Prevent instantiation
    }

    public static final String CALENDAR_BASE = "/api/v1/calendar";
    public static final String PUBLIC_CALENDAR_BASE = "/api/v1/public/calendar";

    public static final String SHIFTS = "/shifts";
    public static final String SHIFT_BY_ID = "/shifts/{shiftId}";
    public static final String SHIFT_CANCEL = "/shifts/{shiftId}/cancel";

    public static final String SLOTS = "/slots";
    public static final String SLOT_HOLD = "/slots/{slotId}/hold";
    public static final String SLOT_RELEASE = "/slots/{slotId}/release";
    public static final String SLOT_CONFIRM = "/slots/{slotId}/confirm";

    public static final String SETTINGS = "/settings";
}


