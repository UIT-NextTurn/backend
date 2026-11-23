package com.nextturn.service.notification;

import com.nextturn.dto.notification.ReminderDispatchSummaryResponse;

/**
 * Contract for scanning CONFIRMED appointments and dispatching 24-hour and 1-hour
 * reminder notifications with up to 3 retry attempts on delivery failure (REQ-NOTI-01).
 */
public interface TenantAppointmentReminderService {

    ReminderDispatchSummaryResponse dispatchUpcomingReminders();
}
