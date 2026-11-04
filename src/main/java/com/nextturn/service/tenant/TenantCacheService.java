package com.nextturn.service.tenant;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nextturn.constant.ValkeyKeyConstant;
import com.nextturn.dto.service.ServiceResponse;
import com.nextturn.dto.calendar.CalendarSettingsResponse;
import com.nextturn.dto.calendar.TimeSlotResponse;
import com.nextturn.dto.tenant.TenantResponse;
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
public class TenantCacheService {

    private static final Logger log = LoggerFactory.getLogger(TenantCacheService.class);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    @Autowired
    public TenantCacheService(
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
            log.debug("Refreshed Valkey tenant config cache for [{}] / [{}]", response.getId(), response.getTenantCode());
        } catch (Exception ex) {
            log.warn("Failed to cache tenant config in Valkey: {}", ex.getMessage());
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
            log.warn("Failed to read cached tenant config from Valkey for [{}]: {}", tenantIdentifier, ex.getMessage());
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
                log.debug("Evicted Valkey cache keys: {}", keys);
            } catch (Exception ex) {
                log.warn("Valkey cache eviction failed for tenant [{}] / [{}]: {}", tenantId, tenantCode, ex.getMessage());
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
                log.debug("Evicted Valkey service catalog cache for tenant [{}]: key [{}]", tenantCode, key);
            } catch (Exception ex) {
                log.warn("Valkey service cache eviction failed for tenant [{}]: {}", tenantCode, ex.getMessage());
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
                log.debug("Evicted service staff cache for tenant [{}] service [{}]: key [{}]", tenantCode, serviceId, key);
            } catch (Exception ex) {
                log.warn("Valkey service staff cache eviction failed for tenant [{}] service [{}]: {}", tenantCode, serviceId, ex.getMessage());
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
            log.debug("Cached {} services in Valkey for tenant [{}]: key [{}]", services.size(), tenantCode, key);
        } catch (Exception ex) {
            log.warn("Failed to cache services in Valkey for tenant [{}]: {}", tenantCode, ex.getMessage());
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
            log.warn("Failed to read cached services from Valkey for tenant [{}]: {}", tenantCode, ex.getMessage());
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
            log.trace("Cache read error for count key [{}]: {}", key, ex.getMessage());
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
            log.trace("Cache write error for count key [{}]: {}", key, ex.getMessage());
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
            log.debug("Cached {} time slots in Valkey for tenant [{}], service [{}], date [{}]: key [{}]", slots.size(), tenantCode, serviceId, workDate, key);
        } catch (Exception ex) {
            log.warn("Failed to cache time slots in Valkey for tenant [{}], service [{}], date [{}]: {}", tenantCode, serviceId, workDate, ex.getMessage());
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
            log.warn("Failed to read cached time slots from Valkey for tenant [{}], service [{}], date [{}]: {}", tenantCode, serviceId, workDate, ex.getMessage());
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
                log.debug("Evicted Valkey time slots cache for tenant [{}], service [{}], date [{}]: key [{}]", tenantCode, serviceId, workDate, key);
            } catch (Exception ex) {
                log.warn("Valkey time slots cache eviction failed for tenant [{}], service [{}], date [{}]: {}", tenantCode, serviceId, workDate, ex.getMessage());
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
            log.debug("Cached calendar settings in Valkey for tenant [{}]: key [{}]", tenantCode, key);
        } catch (Exception ex) {
            log.warn("Failed to cache calendar settings in Valkey for tenant [{}]: {}", tenantCode, ex.getMessage());
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
            log.warn("Failed to read cached calendar settings from Valkey for tenant [{}]: {}", tenantCode, ex.getMessage());
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
                log.debug("Evicted Valkey calendar settings cache for tenant [{}]: key [{}]", tenantCode, key);
            } catch (Exception ex) {
                log.warn("Valkey calendar settings cache eviction failed for tenant [{}]: {}", tenantCode, ex.getMessage());
            }
        }
    }
}
