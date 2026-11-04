package com.nextturn.service.tenant;

import com.nextturn.dto.calendar.CalendarSettingsResponse;
import com.nextturn.dto.calendar.TimeSlotResponse;
import com.nextturn.dto.service.ServiceResponse;
import com.nextturn.dto.tenant.TenantResponse;

import java.time.Duration;
import java.time.LocalDate;
import java.util.List;

/**
 * Service contract managing Valkey/Redis caching and cache eviction for tenant configurations,
 * services catalog, and operational telemetry metrics.
 */
public interface TenantCacheService {

    void cacheTenantConfig(TenantResponse response);

    TenantResponse getCachedTenantConfig(String tenantIdentifier);

    void evictTenantConfigCache(String tenantId, String tenantCode);

    void evictTenantServiceCache(String tenantCode);

    void evictServiceStaffCache(String tenantCode, String serviceId);

    void cacheTenantServices(String tenantCode, List<ServiceResponse> services);

    List<ServiceResponse> getCachedTenantServices(String tenantCode);

    Long getCachedCount(String key);

    void setCachedCount(String key, long count, Duration ttl);

    void cacheTenantSlots(String tenantCode, String serviceId, LocalDate workDate, List<TimeSlotResponse> slots);

    List<TimeSlotResponse> getCachedTenantSlots(String tenantCode, String serviceId, LocalDate workDate);

    void evictTenantSlots(String tenantCode, String serviceId, LocalDate workDate);

    void cacheTenantCalendarSettings(String tenantCode, CalendarSettingsResponse settings);

    CalendarSettingsResponse getCachedTenantCalendarSettings(String tenantCode);

    void evictTenantCalendarSettings(String tenantCode);
}
