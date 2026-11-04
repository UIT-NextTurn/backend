package com.nextturn.scheduler;

import com.nextturn.entity.CentralTenant;
import com.nextturn.repository.auth.CentralTenantRepository;
import com.nextturn.service.calendar.TenantTimeSlotHoldingService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TimeSlotHoldingCleanupSchedulerTest {

    @Mock
    private CentralTenantRepository centralTenantRepository;

    @Mock
    private TenantTimeSlotHoldingService holdingService;

    @InjectMocks
    private TimeSlotHoldingCleanupScheduler scheduler;

    @Test
    @DisplayName("cleanupExpiredHoldingSlots: iterates active tenants and triggers expired slot cleanup")
    void cleanupExpiredHoldingSlots_IteratesTenantsAndReleases() {
        CentralTenant tenant1 = CentralTenant.builder()
                .tenantCode("tenant-a")
                .isDeleted(false)
                .build();
        CentralTenant tenant2 = CentralTenant.builder()
                .tenantCode("tenant-b")
                .isDeleted(false)
                .build();
        CentralTenant deletedTenant = CentralTenant.builder()
                .tenantCode("tenant-deleted")
                .isDeleted(true)
                .build();

        when(centralTenantRepository.findAll()).thenReturn(List.of(tenant1, tenant2, deletedTenant));
        when(holdingService.releaseExpiredHoldingSlots()).thenReturn(2, 0);

        scheduler.cleanupExpiredHoldingSlots();

        verify(holdingService, org.mockito.Mockito.times(2)).releaseExpiredHoldingSlots();
    }

    @Test
    @DisplayName("cleanupExpiredHoldingSlots: skips execution when repository is null")
    void cleanupExpiredHoldingSlots_RepoNull_SkipsExecution() {
        TimeSlotHoldingCleanupScheduler noRepoScheduler = new TimeSlotHoldingCleanupScheduler(null, holdingService);
        noRepoScheduler.cleanupExpiredHoldingSlots();

        verify(holdingService, never()).releaseExpiredHoldingSlots();
    }
}

