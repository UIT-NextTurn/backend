package com.nextturn.service.tenant;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nextturn.constant.ValkeyKeyConstant;
import com.nextturn.dto.tenant.TenantResponse;
import com.nextturn.enums.TenantStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TenantCacheServiceTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    private TenantCacheService cacheService;

    @BeforeEach
    void setUp() {
        cacheService = new TenantCacheService(redisTemplate, objectMapper);
    }

    @Test
    @DisplayName("cacheTenantConfig should store config under both ID and code keys")
    void cacheTenantConfig_success() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        TenantResponse response = TenantResponse.builder()
                .id("t-123")
                .tenantCode("bv-tam-duc")
                .status(TenantStatus.ACTIVE)
                .build();

        cacheService.cacheTenantConfig(response);

        verify(valueOperations).set(eq(ValkeyKeyConstant.tenantConfigKey("t-123")), any(), eq(Duration.ofHours(24)));
        verify(valueOperations).set(eq(ValkeyKeyConstant.tenantConfigKey("bv-tam-duc")), any(), eq(Duration.ofHours(24)));
    }

    @Test
    @DisplayName("getCachedTenantConfig should deserialize cached json")
    void getCachedTenantConfig_hit() throws Exception {
        TenantResponse original = TenantResponse.builder()
                .id("t-123")
                .tenantCode("bv-tam-duc")
                .status(TenantStatus.ACTIVE)
                .build();
        String json = objectMapper.writeValueAsString(original);

        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(ValkeyKeyConstant.tenantConfigKey("t-123"))).thenReturn(json);

        TenantResponse cached = cacheService.getCachedTenantConfig("t-123");

        assertThat(cached).isNotNull();
        assertThat(cached.getId()).isEqualTo("t-123");
        assertThat(cached.getTenantCode()).isEqualTo("bv-tam-duc");
    }

    @Test
    @DisplayName("getCachedTenantConfig should return null when key not found")
    void getCachedTenantConfig_miss() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(ValkeyKeyConstant.tenantConfigKey("t-123"))).thenReturn(null);

        TenantResponse cached = cacheService.getCachedTenantConfig("t-123");

        assertThat(cached).isNull();
    }

    @Test
    @DisplayName("evictTenantConfigCache should delete config, ticket count, and staff count keys")
    void evictTenantConfigCache_success() {
        cacheService.evictTenantConfigCache("t-123", "bv-tam-duc");

        verify(redisTemplate).delete(List.of(
                ValkeyKeyConstant.tenantConfigKey("t-123"),
                ValkeyKeyConstant.tenantConfigKey("bv-tam-duc"),
                ValkeyKeyConstant.tenantTodayTicketsKey("bv-tam-duc"),
                ValkeyKeyConstant.tenantActiveStaffKey("bv-tam-duc")
        ));
    }

    @Test
    @DisplayName("setCachedCount and getCachedCount should work with numeric values")
    void countOperations_success() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("nt:count:test")).thenReturn("42");

        cacheService.setCachedCount("nt:count:test", 42L, Duration.ofMinutes(5));
        Long result = cacheService.getCachedCount("nt:count:test");

        verify(valueOperations).set("nt:count:test", "42", Duration.ofMinutes(5));
        assertThat(result).isEqualTo(42L);
    }
}
