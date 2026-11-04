package com.nextturn.service.tenant;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nextturn.constant.ValkeyKeyConstant;
import com.nextturn.dto.service.ServiceResponse;
import com.nextturn.dto.tenant.TenantResponse;
import com.nextturn.enums.ServiceStatus;
import com.nextturn.enums.TenantStatus;
import com.nextturn.service.tenant.impl.TenantCacheServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TenantCacheServiceTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    private TenantCacheServiceImpl cacheService;

    @BeforeEach
    void setUp() {
        cacheService = new TenantCacheServiceImpl(redisTemplate, objectMapper);
    }

    @Test
    @DisplayName("cacheTenantConfig should store config under both ID and code keys")
    void cacheTenantConfig_success() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        TenantResponse response = TenantResponse.builder()
                .id("t-123")
                .tenantCode("tenant-alpha")
                .status(TenantStatus.ACTIVE)
                .build();

        cacheService.cacheTenantConfig(response);

        verify(valueOperations).set(eq(ValkeyKeyConstant.tenantConfigKey("t-123")), any(), eq(Duration.ofHours(24)));
        verify(valueOperations).set(eq(ValkeyKeyConstant.tenantConfigKey("tenant-alpha")), any(), eq(Duration.ofHours(24)));
    }

    @Test
    @DisplayName("getCachedTenantConfig should deserialize cached json")
    void getCachedTenantConfig_hit() throws Exception {
        TenantResponse original = TenantResponse.builder()
                .id("t-123")
                .tenantCode("tenant-alpha")
                .status(TenantStatus.ACTIVE)
                .build();
        String json = objectMapper.writeValueAsString(original);

        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(ValkeyKeyConstant.tenantConfigKey("t-123"))).thenReturn(json);

        TenantResponse cached = cacheService.getCachedTenantConfig("t-123");

        assertThat(cached).isNotNull();
        assertThat(cached.getId()).isEqualTo("t-123");
        assertThat(cached.getTenantCode()).isEqualTo("tenant-alpha");
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
    @DisplayName("evictTenantConfigCache should delete config, ticket count, staff count, and service keys")
    void evictTenantConfigCache_success() {
        cacheService.evictTenantConfigCache("t-123", "tenant-alpha");

        verify(redisTemplate).delete(List.of(
                ValkeyKeyConstant.tenantConfigKey("t-123"),
                ValkeyKeyConstant.tenantConfigKey("tenant-alpha"),
                ValkeyKeyConstant.tenantTodayTicketsKey("tenant-alpha"),
                ValkeyKeyConstant.tenantActiveStaffKey("tenant-alpha"),
                ValkeyKeyConstant.tenantServicesKey("tenant-alpha")
        ));
    }

    @Test
    @DisplayName("evictTenantServiceCache should delete tenant services catalog key")
    void evictTenantServiceCache_success() {
        cacheService.evictTenantServiceCache("tenant-alpha");

        verify(redisTemplate).delete(ValkeyKeyConstant.tenantServicesKey("tenant-alpha"));
    }

    @Test
    @DisplayName("cacheTenantServices should serialize and store active services in Valkey")
    void cacheTenantServices_success() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        List<ServiceResponse> services = List.of(
                ServiceResponse.builder()
                        .id("svc-1")
                        .serviceCode("SVC-0001")
                        .serviceName("Tư Vấn Khách Hàng")
                        .standardDuration(15)
                        .price(new BigDecimal("150000.00"))
                        .status(ServiceStatus.ACTIVE)
                        .build()
        );

        cacheService.cacheTenantServices("tenant-alpha", services);

        verify(valueOperations).set(eq(ValkeyKeyConstant.tenantServicesKey("tenant-alpha")), any(), eq(Duration.ofHours(24)));
    }

    @Test
    @DisplayName("getCachedTenantServices should deserialize cached json list of services")
    void getCachedTenantServices_hit() throws Exception {
        List<ServiceResponse> services = List.of(
                ServiceResponse.builder()
                        .id("svc-1")
                        .serviceCode("SVC-0001")
                        .serviceName("Tư Vấn Khách Hàng")
                        .standardDuration(15)
                        .price(new BigDecimal("150000.00"))
                        .status(ServiceStatus.ACTIVE)
                        .build()
        );
        String json = objectMapper.writeValueAsString(services);

        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(ValkeyKeyConstant.tenantServicesKey("tenant-alpha"))).thenReturn(json);

        List<ServiceResponse> cached = cacheService.getCachedTenantServices("tenant-alpha");

        assertThat(cached).isNotNull();
        assertThat(cached).hasSize(1);
        assertThat(cached.get(0).getServiceCode()).isEqualTo("SVC-0001");
        assertThat(cached.get(0).getServiceName()).isEqualTo("Tư Vấn Khách Hàng");
    }

    @Test
    @DisplayName("getCachedTenantServices should return null when cache key is absent")
    void getCachedTenantServices_miss() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(ValkeyKeyConstant.tenantServicesKey("tenant-alpha"))).thenReturn(null);

        List<ServiceResponse> cached = cacheService.getCachedTenantServices("tenant-alpha");

        assertThat(cached).isNull();
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
