package com.nextturn.scheduler;

import com.nextturn.entity.CentralTenant;
import com.nextturn.infrastructure.multitenancy.TenantContextHolder;
import com.nextturn.repository.auth.CentralTenantRepository;
import com.nextturn.service.calendar.TenantTimeSlotHoldingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Scheduled background task for automatically releasing expired temporary slot reservations (HOLDING status)
 * back to AVAILABLE status in both MySQL and Valkey.
 */
@Component
@EnableScheduling
@ConditionalOnProperty(name = "nextturn.scheduler.slot-cleanup.enabled", havingValue = "true", matchIfMissing = true)
public class TimeSlotHoldingCleanupScheduler {

    private static final Logger log = LoggerFactory.getLogger(TimeSlotHoldingCleanupScheduler.class);

    private final CentralTenantRepository centralTenantRepository;
    private final TenantTimeSlotHoldingService holdingService;

    public TimeSlotHoldingCleanupScheduler(
            @Autowired(required = false) CentralTenantRepository centralTenantRepository,
            TenantTimeSlotHoldingService holdingService
    ) {
        this.centralTenantRepository = centralTenantRepository;
        this.holdingService = holdingService;
    }

    /**
     * Executes periodically (default every 60 seconds) to find and release expired holding slots across all active tenants.
     */
    @Scheduled(fixedRateString = "${nextturn.scheduler.slot-cleanup.fixed-rate:60000}")
    public void cleanupExpiredHoldingSlots() {
        if (centralTenantRepository == null) {
            log.trace("CentralTenantRepository not available, skipping scheduled slot cleanup");
            return;
        }

        List<CentralTenant> tenants;
        try {
            tenants = centralTenantRepository.findAll().stream()
                    .filter(t -> !Boolean.TRUE.equals(t.getIsDeleted()))
                    .toList();
        } catch (Exception ex) {
            log.warn("Failed to fetch tenants for expired holding cleanup: {}", ex.getMessage());
            return;
        }

        int totalReleased = 0;
        for (CentralTenant tenant : tenants) {
            String tenantCode = tenant.getTenantCode();
            if (tenantCode == null || tenantCode.isBlank()) {
                continue;
            }

            try {
                TenantContextHolder.setTenantCode(tenantCode.trim());
                int released = holdingService.releaseExpiredHoldingSlots();
                if (released > 0) {
                    totalReleased += released;
                    log.info("Released {} expired holding slots for tenant [{}]", released, tenantCode);
                }
            } catch (Exception ex) {
                log.warn("Error releasing expired holding slots for tenant [{}]: {}", tenantCode, ex.getMessage());
            } finally {
                TenantContextHolder.clear();
            }
        }

        if (totalReleased > 0) {
            log.info("Completed expired holding slots cleanup: {} slots released across {} tenants",
                    totalReleased, tenants.size());
        }
    }
}

