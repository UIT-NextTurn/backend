package com.nextturn.service.dashboard;

import com.nextturn.entity.TenantAppointment;
import com.nextturn.entity.TenantFeedback;
import com.nextturn.entity.TenantQueueTicket;

import java.time.LocalDate;

/**
 * Non-blocking write-path analytics synchronization service updating partitioned rollup tables,
 * refreshing/evicting Valkey caches, evaluating quota thresholds, and pushing dashboard SSE updates
 * whenever queue, ticket, appointment, or feedback domain events occur.
 */
public interface DashboardAnalyticsSyncService {

    void onTicketIssued(String tenantCode, TenantQueueTicket ticket);

    void onTicketServing(String tenantCode, TenantQueueTicket ticket);

    void onTicketCompleted(String tenantCode, TenantQueueTicket ticket);

    void onTicketNoShow(String tenantCode, TenantQueueTicket ticket);

    void onAppointmentCancelled(String tenantCode, TenantAppointment appointment);

    void onFeedbackSubmitted(String tenantCode, TenantFeedback feedback);

    void onTenantLifecycleChanged(String tenantCode, String eventType);

    int syncPlatformDailySnapshot(LocalDate reportDate);
}
