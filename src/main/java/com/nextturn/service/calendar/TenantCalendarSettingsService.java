package com.nextturn.service.calendar;

import com.nextturn.dto.calendar.CalendarSettingsRequest;
import com.nextturn.dto.calendar.CalendarSettingsResponse;

/**
 * Service contract for configuring facility-level advance booking rules, lead times,
 * and cancellation policies.
 */
public interface TenantCalendarSettingsService {

    /**
     * Retrieves current tenant booking policy settings.
     *
     * @return calendar settings response
     */
    CalendarSettingsResponse getSettings();

    /**
     * Updates tenant booking policy settings and synchronizes cache with Valkey.
     *
     * @param request settings update request
     * @return updated calendar settings response
     */
    CalendarSettingsResponse updateSettings(CalendarSettingsRequest request);
}

