package com.nextturn.scheduler;

import com.nextturn.repository.auth.CentralTenantRepository;
import com.nextturn.service.calendar.TenantTimeSlotHoldingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

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
        TenantSchedulerSweepSupport.runAcrossActiveTenants(
                log,
                "slot_hold_cleanup",
                centralTenantRepository,
                tenantCode -> holdingService.releaseExpiredHoldingSlots()
        );
    }
}
