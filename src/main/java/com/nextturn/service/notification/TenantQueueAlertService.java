package com.nextturn.service.notification;

import com.nextturn.entity.TenantQueueTicket;

import java.time.LocalDate;

/**
 * Contract for real-time queue alert dispatch:
 * <ul>
 *     <li>REQ-NOTI-02: Approaching-turn alert when people ahead {@code <= 2} or estimated wait {@code < 5} min
 *         (guarded by {@code approaching_alert_sent})</li>
 *     <li>REQ-NOTI-03: High-priority counter call alert when a ticket transitions to {@code CALLING}</li>
 * </ul>
 */
public interface TenantQueueAlertService {

    int processWaitingTurnAlerts(String serviceId, LocalDate workDate);

    void dispatchCounterCallNotification(TenantQueueTicket calledTicket);
}
