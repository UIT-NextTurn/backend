package com.nextturn.scheduler;

import com.nextturn.repository.auth.CentralTenantRepository;
import com.nextturn.service.queue.TenantQueueCallingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

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
        TenantSchedulerSweepSupport.runAcrossActiveTenants(
                log,
                "queue_autocomplete",
                centralTenantRepository,
                tenantCode -> queueCallingService.autoCompleteOverdueTickets()
        );
    }
}
