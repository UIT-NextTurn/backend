package com.nextturn.scheduler;

import com.nextturn.dto.notification.ReminderDispatchSummaryResponse;
import com.nextturn.infrastructure.logging.LogContract;
import com.nextturn.infrastructure.logging.LogDomain;
import com.nextturn.infrastructure.logging.LogOutcome;
import com.nextturn.repository.auth.CentralTenantRepository;
import com.nextturn.service.notification.TenantAppointmentReminderService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

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
        TenantSchedulerSweepSupport.runAcrossActiveTenants(
                log,
                "appointment_reminder",
                centralTenantRepository,
                tenantCode -> {
                    ReminderDispatchSummaryResponse summary = reminderService.dispatchUpcomingReminders();
                    if (summary.getFailedCount() > 0) {
                        LogContract.warn(log, LogDomain.SCHEDULER, "appointment_reminder_partial")
                                .outcome(LogOutcome.DEGRADED)
                                .tenant(tenantCode)
                                .field("sent24h", summary.getReminders24hSent())
                                .field("sent1h", summary.getReminders1hSent())
                                .field("failed", summary.getFailedCount())
                                .emit();
                    }
                    return summary.getReminders24hSent() + summary.getReminders1hSent();
                }
        );
    }
}
