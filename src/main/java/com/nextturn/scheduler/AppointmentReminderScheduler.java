package com.nextturn.scheduler;

import com.nextturn.dto.notification.ReminderDispatchSummaryResponse;
import com.nextturn.entity.CentralTenant;
import com.nextturn.infrastructure.multitenancy.TenantContextHolder;
import com.nextturn.repository.auth.CentralTenantRepository;
import com.nextturn.service.notification.TenantAppointmentReminderService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Scheduled background task running every 5 minutes (REQ-NOTI-01) across all active tenants
 * to dispatch 24-hour and 1-hour pre-booked appointment reminders.
 */
@Component
@EnableScheduling
@ConditionalOnProperty(name = "nextturn.scheduler.appointment-reminder.enabled", havingValue = "true", matchIfMissing = true)
public class AppointmentReminderScheduler {

    private static final Logger log = LoggerFactory.getLogger(AppointmentReminderScheduler.class);

    private final CentralTenantRepository centralTenantRepository;
    private final TenantAppointmentReminderService reminderService;

    public AppointmentReminderScheduler(
            @Autowired(required = false) CentralTenantRepository centralTenantRepository,
            TenantAppointmentReminderService reminderService
    ) {
        this.centralTenantRepository = centralTenantRepository;
        this.reminderService = reminderService;
    }

    @Scheduled(fixedRateString = "${nextturn.scheduler.appointment-reminder.fixed-rate:300000}")
    public void sendUpcomingAppointmentReminders() {
        if (centralTenantRepository == null) {
            log.trace("CentralTenantRepository not available, skipping scheduled appointment reminders");
            return;
        }

        List<CentralTenant> tenants;
        try {
            tenants = centralTenantRepository.findAll().stream()
                    .filter(t -> !Boolean.TRUE.equals(t.getIsDeleted()))
                    .toList();
        } catch (Exception ex) {
            log.warn("Failed to fetch tenants for appointment reminders: {}", ex.getMessage());
            return;
        }

        for (CentralTenant tenant : tenants) {
            String tenantCode = tenant.getTenantCode();
            if (tenantCode == null || tenantCode.isBlank()) {
                continue;
            }
            try {
                TenantContextHolder.setTenantCode(tenantCode.trim());
                ReminderDispatchSummaryResponse summary = reminderService.dispatchUpcomingReminders();
                if (summary.getReminders24hSent() > 0 || summary.getReminders1hSent() > 0 || summary.getFailedCount() > 0) {
                    log.info("Appointment reminder sweep for tenant [{}]: 24h={}, 1h={}, failed={}",
                            tenantCode, summary.getReminders24hSent(), summary.getReminders1hSent(), summary.getFailedCount());
                }
            } catch (Exception ex) {
                log.warn("Error dispatching appointment reminders for tenant [{}]: {}", tenantCode, ex.getMessage());
            } finally {
                TenantContextHolder.clear();
            }
        }
    }
}
