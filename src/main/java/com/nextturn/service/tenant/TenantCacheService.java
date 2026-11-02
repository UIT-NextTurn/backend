package com.nextturn.service.tenant;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nextturn.constant.ValkeyKeyConstant;
import com.nextturn.dto.tenant.TenantResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Service managing Valkey/Redis caching and cache eviction for tenant configurations
 * and operational telemetry metrics (REQ-TNT-02, REQ-TNT-04).
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
            Duration ttl = Duration.ofHours(24);
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
                }
                redisTemplate.delete(keys);
                log.debug("Evicted Valkey cache keys: {}", keys);
            } catch (Exception ex) {
                log.warn("Valkey cache eviction failed for tenant [{}] / [{}]: {}", tenantId, tenantCode, ex.getMessage());
            }
        }
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
}
