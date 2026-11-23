package com.nextturn.service.tenant.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nextturn.constant.ValkeyKeyConstant;
import com.nextturn.dto.service.ServiceResponse;
import com.nextturn.dto.calendar.CalendarSettingsResponse;
import com.nextturn.dto.calendar.TimeSlotResponse;
import com.nextturn.dto.tenant.TenantResponse;
import com.nextturn.service.tenant.TenantCacheService;
import com.nextturn.infrastructure.logging.LogContract;
import com.nextturn.infrastructure.logging.LogDomain;
import com.nextturn.infrastructure.logging.LogOutcome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Service managing Valkey/Redis caching and cache eviction for tenant configurations,
 * services catalog, and operational telemetry metrics.
 */
@Service
public class TenantCacheServiceImpl implements TenantCacheService {

    private static final Logger log = LoggerFactory.getLogger(TenantCacheServiceImpl.class);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    @Autowired
    public TenantCacheServiceImpl(
            @Autowired(required = false) StringRedisTemplate redisTemplate,
            @Autowired(required = false) ObjectMapper objectMapper
    ) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = (objectMapper != null) ? objectMapper : new ObjectMapper().findAndRegisterModules();
    }

    /**
     * Caches tenant detailed configuration in Valkey under both tenantId and tenantCode keys (TTL: 24h).
     */
    public void cacheTenantConfig(TenantResponse response) {
        if (redisTemplate == null || response == null) {
            return;
        }
        try {
            var ops = redisTemplate.opsForValue();
            if (ops == null) {
                return;
            }
            String json = objectMapper.writeValueAsString(response);
            Duration ttl = Duration.ofHours(ValkeyKeyConstant.TENANT_PROFILE_TTL_HOURS);
            if (response.getId() != null) {
                ops.set(ValkeyKeyConstant.tenantConfigKey(response.getId()), json, ttl);
            }
            if (response.getTenantCode() != null) {
                ops.set(ValkeyKeyConstant.tenantConfigKey(response.getTenantCode()), json, ttl);
            }
            LogContract.debug(log, LogDomain.TENANT, "cache_write_config")
                    .entity("tenant", response.getTenantCode())
                    .field("tenantId", response.getId())
                    .emit();
        } catch (Exception ex) {
            LogContract.warn(log, LogDomain.TENANT, "cache_write_config")
                    .outcome(LogOutcome.DEGRADED)
                    .entity("tenant", response.getTenantCode())
                    .cause(ex)
                    .emit();
        }
    }

    /**
     * Reads cached tenant configuration from Valkey by tenant ID or tenant code.
     */
    public TenantResponse getCachedTenantConfig(String tenantIdentifier) {
        if (redisTemplate == null || tenantIdentifier == null || tenantIdentifier.isBlank()) {
            return null;
        }
        try {
            var ops = redisTemplate.opsForValue();
            if (ops == null) {
                return null;
            }
            String key = ValkeyKeyConstant.tenantConfigKey(tenantIdentifier);
            String json = ops.get(key);
            if (json != null && !json.isBlank()) {
                return objectMapper.readValue(json, TenantResponse.class);
            }
        } catch (Exception ex) {
            LogContract.warn(log, LogDomain.TENANT, "cache_read_config")
                    .outcome(LogOutcome.DEGRADED)
                    .field("identifier", tenantIdentifier)
                    .cause(ex)
                    .emit();
        }
        return null;
    }

    /**
     * Evicts cached tenant config and metric keys from Valkey.
     */
    public void evictTenantConfigCache(String tenantId, String tenantCode) {
        if (redisTemplate != null) {
            try {
                List<String> keys = new ArrayList<>();
                if (tenantId != null) {
                    keys.add(ValkeyKeyConstant.tenantConfigKey(tenantId));
                }
                if (tenantCode != null) {
                    keys.add(ValkeyKeyConstant.tenantConfigKey(tenantCode));
                    keys.add(ValkeyKeyConstant.tenantTodayTicketsKey(tenantCode));
                    keys.add(ValkeyKeyConstant.tenantActiveStaffKey(tenantCode));
                    keys.add(ValkeyKeyConstant.tenantServicesKey(tenantCode));
                }
                redisTemplate.delete(keys);
                LogContract.debug(log, LogDomain.TENANT, "cache_evict_config")
                        .field("keyCount", keys.size())
                        .emit();
            } catch (Exception ex) {
                LogContract.warn(log, LogDomain.TENANT, "cache_evict_config")
                        .outcome(LogOutcome.DEGRADED)
                        .tenant(tenantCode)
                        .field("tenantId", tenantId)
                        .cause(ex)
                        .emit();
            }
        }
    }

    /**
     * Invalidate and evict tenant service catalog cache.
     */
    public void evictTenantServiceCache(String tenantCode) {
        if (redisTemplate != null && tenantCode != null && !tenantCode.isBlank()) {
            try {
                String key = ValkeyKeyConstant.tenantServicesKey(tenantCode);
                redisTemplate.delete(key);
                LogContract.debug(log, LogDomain.SERVICE, "cache_evict_services")
                        .tenant(tenantCode)
                        .field("key", key)
                        .emit();
            } catch (Exception ex) {
                LogContract.warn(log, LogDomain.SERVICE, "cache_evict_services")
                        .outcome(LogOutcome.DEGRADED)
                        .tenant(tenantCode)
                        .cause(ex)
                        .emit();
            }
        }
    }

    /**
     * Invalidate and evict cached staff assigned to a specific service.
     *
     * @param tenantCode tenant code
     * @param serviceId service unique ID
     */
    public void evictServiceStaffCache(String tenantCode, String serviceId) {
        if (redisTemplate != null && tenantCode != null && !tenantCode.isBlank() && serviceId != null && !serviceId.isBlank()) {
            try {
                String key = ValkeyKeyConstant.tenantServiceStaffKey(tenantCode, serviceId);
                redisTemplate.delete(key);
                LogContract.debug(log, LogDomain.STAFF, "cache_evict_service_staff")
                        .tenant(tenantCode)
                        .entity("service", serviceId)
                        .field("key", key)
                        .emit();
            } catch (Exception ex) {
                LogContract.warn(log, LogDomain.STAFF, "cache_evict_service_staff")
                        .outcome(LogOutcome.DEGRADED)
                        .tenant(tenantCode)
                        .entity("service", serviceId)
                        .cause(ex)
                        .emit();
            }
        }
    }

    /**
     * Caches active services list in Valkey for a given tenant.
     *
     * @param tenantCode tenant code
     * @param services list of active service responses
     */
    public void cacheTenantServices(String tenantCode, List<ServiceResponse> services) {
        if (redisTemplate == null || tenantCode == null || tenantCode.isBlank() || services == null) {
            return;
        }
        try {
            var ops = redisTemplate.opsForValue();
            if (ops == null) {
                return;
            }
            String key = ValkeyKeyConstant.tenantServicesKey(tenantCode);
            String json = objectMapper.writeValueAsString(services);
            ops.set(key, json, Duration.ofHours(ValkeyKeyConstant.TENANT_PROFILE_TTL_HOURS));
            LogContract.debug(log, LogDomain.SERVICE, "cache_write_services")
                    .tenant(tenantCode)
                    .field("count", services.size())
                    .field("key", key)
                    .emit();
        } catch (Exception ex) {
            LogContract.warn(log, LogDomain.SERVICE, "cache_write_services")
                    .outcome(LogOutcome.DEGRADED)
                    .tenant(tenantCode)
                    .cause(ex)
                    .emit();
        }
    }

    /**
     * Reads cached services list from Valkey for a given tenant.
     *
     * @param tenantCode tenant code
     * @return list of cached service responses, or null if cache miss or error
     */
    public List<ServiceResponse> getCachedTenantServices(String tenantCode) {
        if (redisTemplate == null || tenantCode == null || tenantCode.isBlank()) {
            return null;
        }
        try {
            var ops = redisTemplate.opsForValue();
            if (ops == null) {
                return null;
            }
            String key = ValkeyKeyConstant.tenantServicesKey(tenantCode);
            String json = ops.get(key);
            if (json != null && !json.isBlank()) {
                return objectMapper.readValue(json, new TypeReference<List<ServiceResponse>>() {});
            }
        } catch (Exception ex) {
            LogContract.warn(log, LogDomain.SERVICE, "cache_read_services")
                    .outcome(LogOutcome.DEGRADED)
                    .tenant(tenantCode)
                    .cause(ex)
                    .emit();
        }
        return null;
    }

    /**
     * Retrieves a cached numeric metric count from Valkey.
     */
    public Long getCachedCount(String key) {
        if (redisTemplate == null || key == null || key.isBlank()) {
            return null;
        }
        try {
            var ops = redisTemplate.opsForValue();
            if (ops != null) {
                String val = ops.get(key);
                if (val != null && !val.isBlank()) {
                    return Long.parseLong(val.trim());
                }
            }
        } catch (Exception ex) {
            LogContract.warn(log, LogDomain.TENANT, "cache_read_count")
                    .outcome(LogOutcome.DEGRADED)
                    .field("key", key)
                    .cause(ex)
                    .emit();
        }
        return null;
    }

    /**
     * Stores a numeric metric count into Valkey with an expiration TTL.
     */
    public void setCachedCount(String key, long count, Duration ttl) {
        if (redisTemplate == null || key == null || key.isBlank()) {
            return;
        }
        try {
            var ops = redisTemplate.opsForValue();
            if (ops != null) {
                ops.set(key, String.valueOf(count), ttl);
            }
        } catch (Exception ex) {
            LogContract.warn(log, LogDomain.TENANT, "cache_write_count")
                    .outcome(LogOutcome.DEGRADED)
                    .field("key", key)
                    .cause(ex)
                    .emit();
        }
    }

    /**
     * Caches available booking time slots in Valkey for a specific service and date.
     *
     * @param tenantCode active facility identifier
     * @param serviceId service UUID
     * @param workDate scheduled work date
     * @param slots list of available slot responses
     */
    public void cacheTenantSlots(String tenantCode, String serviceId, LocalDate workDate, List<TimeSlotResponse> slots) {
        if (redisTemplate == null || tenantCode == null || tenantCode.isBlank() || serviceId == null || workDate == null || slots == null) {
            return;
        }
        try {
            var ops = redisTemplate.opsForValue();
            if (ops == null) {
                return;
            }
            String key = ValkeyKeyConstant.tenantSlotsKey(tenantCode, serviceId, workDate);
            String json = objectMapper.writeValueAsString(slots);
            ops.set(key, json, Duration.ofHours(ValkeyKeyConstant.TENANT_PROFILE_TTL_HOURS));
            LogContract.debug(log, LogDomain.CALENDAR, "cache_write_slots")
                    .tenant(tenantCode)
                    .field("count", slots.size())
                    .field("serviceId", serviceId)
                    .field("workDate", workDate)
                    .field("key", key)
                    .emit();
        } catch (Exception ex) {
            LogContract.warn(log, LogDomain.CALENDAR, "cache_write_slots")
                    .outcome(LogOutcome.DEGRADED)
                    .tenant(tenantCode)
                    .field("serviceId", serviceId)
                    .field("workDate", workDate)
                    .cause(ex)
                    .emit();
        }
    }

    /**
     * Reads cached available booking time slots from Valkey for a specific service and date.
     *
     * @param tenantCode active facility identifier
     * @param serviceId service UUID
     * @param workDate scheduled work date
     * @return list of cached time slot responses, or null if cache miss or error
     */
    public List<TimeSlotResponse> getCachedTenantSlots(String tenantCode, String serviceId, LocalDate workDate) {
        if (redisTemplate == null || tenantCode == null || tenantCode.isBlank() || serviceId == null || workDate == null) {
            return null;
        }
        try {
            var ops = redisTemplate.opsForValue();
            if (ops == null) {
                return null;
            }
            String key = ValkeyKeyConstant.tenantSlotsKey(tenantCode, serviceId, workDate);
            String json = ops.get(key);
            if (json != null && !json.isBlank()) {
                return objectMapper.readValue(json, new TypeReference<List<TimeSlotResponse>>() {});
            }
        } catch (Exception ex) {
            LogContract.warn(log, LogDomain.CALENDAR, "cache_read_slots")
                    .outcome(LogOutcome.DEGRADED)
                    .tenant(tenantCode)
                    .field("serviceId", serviceId)
                    .field("workDate", workDate)
                    .cause(ex)
                    .emit();
        }
        return null;
    }

    /**
     * Evicts cached booking time slots from Valkey for a specific service and date.
     *
     * @param tenantCode active facility identifier
     * @param serviceId service UUID
     * @param workDate scheduled work date
     */
    public void evictTenantSlots(String tenantCode, String serviceId, LocalDate workDate) {
        if (redisTemplate != null && tenantCode != null && !tenantCode.isBlank() && serviceId != null && workDate != null) {
            try {
                String key = ValkeyKeyConstant.tenantSlotsKey(tenantCode, serviceId, workDate);
                redisTemplate.delete(key);
                LogContract.debug(log, LogDomain.CALENDAR, "cache_evict_slots")
                        .tenant(tenantCode)
                        .field("serviceId", serviceId)
                        .field("workDate", workDate)
                        .field("key", key)
                        .emit();
            } catch (Exception ex) {
                LogContract.warn(log, LogDomain.CALENDAR, "cache_evict_slots")
                        .outcome(LogOutcome.DEGRADED)
                        .tenant(tenantCode)
                        .field("serviceId", serviceId)
                        .field("workDate", workDate)
                        .cause(ex)
                        .emit();
            }
        }
    }

    /**
     * Caches facility-level calendar advance booking policies in Valkey.
     *
     * @param tenantCode active facility identifier
     * @param settings settings response
     */
    public void cacheTenantCalendarSettings(String tenantCode, CalendarSettingsResponse settings) {
        if (redisTemplate == null || tenantCode == null || tenantCode.isBlank() || settings == null) {
            return;
        }
        try {
            var ops = redisTemplate.opsForValue();
            if (ops == null) {
                return;
            }
            String key = ValkeyKeyConstant.tenantSettingsKey(tenantCode);
            String json = objectMapper.writeValueAsString(settings);
            ops.set(key, json, Duration.ofHours(ValkeyKeyConstant.TENANT_PROFILE_TTL_HOURS));
            LogContract.debug(log, LogDomain.CALENDAR, "cache_write_settings")
                    .tenant(tenantCode)
                    .field("key", key)
                    .emit();
        } catch (Exception ex) {
            LogContract.warn(log, LogDomain.CALENDAR, "cache_write_settings")
                    .outcome(LogOutcome.DEGRADED)
                    .tenant(tenantCode)
                    .cause(ex)
                    .emit();
        }
    }

    /**
     * Reads cached facility-level calendar booking policies from Valkey.
     *
     * @param tenantCode active facility identifier
     * @return cached settings response or null if cache miss
     */
    public CalendarSettingsResponse getCachedTenantCalendarSettings(String tenantCode) {
        if (redisTemplate == null || tenantCode == null || tenantCode.isBlank()) {
            return null;
        }
        try {
            var ops = redisTemplate.opsForValue();
            if (ops == null) {
                return null;
            }
            String key = ValkeyKeyConstant.tenantSettingsKey(tenantCode);
            String json = ops.get(key);
            if (json != null && !json.isBlank()) {
                return objectMapper.readValue(json, CalendarSettingsResponse.class);
            }
        } catch (Exception ex) {
            LogContract.warn(log, LogDomain.CALENDAR, "cache_read_settings")
                    .outcome(LogOutcome.DEGRADED)
                    .tenant(tenantCode)
                    .cause(ex)
                    .emit();
        }
        return null;
    }

    /**
     * Evicts cached facility-level calendar booking policies from Valkey.
     *
     * @param tenantCode active facility identifier
     */
    public void evictTenantCalendarSettings(String tenantCode) {
        if (redisTemplate != null && tenantCode != null && !tenantCode.isBlank()) {
            try {
                String key = ValkeyKeyConstant.tenantSettingsKey(tenantCode);
                redisTemplate.delete(key);
                LogContract.debug(log, LogDomain.CALENDAR, "cache_evict_settings")
                        .tenant(tenantCode)
                        .field("key", key)
                        .emit();
            } catch (Exception ex) {
                LogContract.warn(log, LogDomain.CALENDAR, "cache_evict_settings")
                        .outcome(LogOutcome.DEGRADED)
                        .tenant(tenantCode)
                        .cause(ex)
                        .emit();
            }
        }
    }
}
