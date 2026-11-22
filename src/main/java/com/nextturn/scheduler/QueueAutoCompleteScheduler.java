package com.nextturn.scheduler;

import com.nextturn.entity.CentralTenant;
import com.nextturn.infrastructure.multitenancy.TenantContextHolder;
import com.nextturn.repository.auth.CentralTenantRepository;
import com.nextturn.service.queue.TenantQueueCallingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Scheduled background safety net (REQ-QC-03) checking every 60 seconds for {@code SERVING} tickets
 * exceeding {@code 1.5 * standard_duration} and auto-completing them so specialists are freed cleanly.
 */
@Component
@EnableScheduling
@ConditionalOnProperty(name = "nextturn.scheduler.queue-autocomplete.enabled", havingValue = "true", matchIfMissing = true)
public class QueueAutoCompleteScheduler {

    private static final Logger log = LoggerFactory.getLogger(QueueAutoCompleteScheduler.class);

    private final CentralTenantRepository centralTenantRepository;
    private final TenantQueueCallingService queueCallingService;

    public QueueAutoCompleteScheduler(
            @Autowired(required = false) CentralTenantRepository centralTenantRepository,
            TenantQueueCallingService queueCallingService
    ) {
        this.centralTenantRepository = centralTenantRepository;
        this.queueCallingService = queueCallingService;
    }

    @Scheduled(fixedRateString = "${nextturn.scheduler.queue-autocomplete.fixed-rate:60000}")
    public void autoCompleteOverdueServingTickets() {
        if (centralTenantRepository == null) {
            log.trace("CentralTenantRepository not available, skipping scheduled queue auto-complete");
            return;
        }

        List<CentralTenant> tenants;
        try {
            tenants = centralTenantRepository.findAll().stream()
                    .filter(t -> !Boolean.TRUE.equals(t.getIsDeleted()))
                    .toList();
        } catch (Exception ex) {
            log.warn("Failed to fetch tenants for queue auto-complete: {}", ex.getMessage());
            return;
        }

        int totalCompleted = 0;
        for (CentralTenant tenant : tenants) {
            String tenantCode = tenant.getTenantCode();
            if (tenantCode == null || tenantCode.isBlank()) {
                continue;
            }
            try {
                TenantContextHolder.setTenantCode(tenantCode.trim());
                int completed = queueCallingService.autoCompleteOverdueTickets();
                if (completed > 0) {
                    totalCompleted += completed;
                    log.info("Auto-completed {} overdue serving tickets for tenant [{}]", completed, tenantCode);
                }
            } catch (Exception ex) {
                log.warn("Error running queue auto-complete for tenant [{}]: {}", tenantCode, ex.getMessage());
            } finally {
                TenantContextHolder.clear();
            }
        }

        if (totalCompleted > 0) {
            log.info("Completed queue auto-complete sweep: {} tickets transitioned across {} tenants",
                    totalCompleted, tenants.size());
        }
    }
}
