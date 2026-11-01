package com.nextturn.service.tenant;

import com.nextturn.constant.ValkeyKeyConstant;
import com.nextturn.enums.StaffStatus;
import com.nextturn.repository.auth.TenantStaffRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import javax.sql.DataSource;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TenantTelemetryServiceTest {

    @Mock
    private TenantCacheService cacheService;

    @Mock
    private TenantStaffRepository staffRepository;

    @Mock
    private DataSource dataSource;

    private TenantTelemetryService telemetryService;

    @BeforeEach
    void setUp() {
        telemetryService = new TenantTelemetryService(cacheService, staffRepository, dataSource);
    }

    @Test
    @DisplayName("countActiveStaff should return cached count on cache hit")
    void countActiveStaff_cacheHit() {
        when(cacheService.getCachedCount(ValkeyKeyConstant.tenantActiveStaffKey("bv-tam-duc")))
                .thenReturn(15L);

        long count = telemetryService.countActiveStaff("bv-tam-duc");

        assertThat(count).isEqualTo(15L);
    }

    @Test
    @DisplayName("countActiveStaff should query repository on cache miss and set cache")
    void countActiveStaff_cacheMiss_queriesRepository() {
        when(cacheService.getCachedCount(ValkeyKeyConstant.tenantActiveStaffKey("bv-tam-duc")))
                .thenReturn(null);
        when(staffRepository.countByStatusAndIsDeletedFalse(StaffStatus.ACTIVE))
                .thenReturn(8L);

        long count = telemetryService.countActiveStaff("bv-tam-duc");

        assertThat(count).isEqualTo(8L);
        verify(cacheService).setCachedCount(
                eq(ValkeyKeyConstant.tenantActiveStaffKey("bv-tam-duc")),
                eq(8L),
                eq(Duration.ofMinutes(5))
        );
    }

    @Test
    @DisplayName("countTodayTickets should return cached count on cache hit")
    void countTodayTickets_cacheHit() {
        when(cacheService.getCachedCount(ValkeyKeyConstant.tenantTodayTicketsKey("bv-tam-duc")))
                .thenReturn(120L);

        long count = telemetryService.countTodayTickets("bv-tam-duc");

        assertThat(count).isEqualTo(120L);
    }
}
