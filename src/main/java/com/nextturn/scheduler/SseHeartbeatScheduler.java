package com.nextturn.scheduler;

import com.nextturn.infrastructure.logging.LogContract;
import com.nextturn.infrastructure.logging.LogDomain;
import com.nextturn.infrastructure.logging.LogOutcome;
import com.nextturn.service.dashboard.DashboardSseBroadcaster;
import com.nextturn.service.queue.TenantLobbySseBroadcaster;
import com.nextturn.service.ticket.TenantTicketSseBroadcaster;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Periodic SSE heartbeat scheduler emitting comment keepalives ({@code :heartbeat}) every 20 seconds
 * across all active Lobby, Ticket, and Dashboard SSE broadcasters so proxies keep idle streams alive
 * and dead peers are evicted promptly without an artificial 30-minute timeout.
 */
@Component
@EnableScheduling
@ConditionalOnProperty(name = "nextturn.sse.heartbeat.enabled", havingValue = "true", matchIfMissing = true)
public class SseHeartbeatScheduler {

    private static final Logger log = LoggerFactory.getLogger(SseHeartbeatScheduler.class);

    private final TenantLobbySseBroadcaster lobbySseBroadcaster;
    private final TenantTicketSseBroadcaster ticketSseBroadcaster;
    private final DashboardSseBroadcaster dashboardSseBroadcaster;

    @Autowired
    public SseHeartbeatScheduler(
            @Autowired(required = false) TenantLobbySseBroadcaster lobbySseBroadcaster,
            @Autowired(required = false) TenantTicketSseBroadcaster ticketSseBroadcaster,
            @Autowired(required = false) DashboardSseBroadcaster dashboardSseBroadcaster
    ) {
        this.lobbySseBroadcaster = lobbySseBroadcaster;
        this.ticketSseBroadcaster = ticketSseBroadcaster;
        this.dashboardSseBroadcaster = dashboardSseBroadcaster;
    }

    @Scheduled(fixedRateString = "${nextturn.sse.heartbeat.interval-ms:20000}")
    public void emitHeartbeats() {
        runSafe("lobby", () -> {
            if (lobbySseBroadcaster != null) {
                lobbySseBroadcaster.sendHeartbeats();
            }
        });
        runSafe("ticket", () -> {
            if (ticketSseBroadcaster != null) {
                ticketSseBroadcaster.sendHeartbeats();
            }
        });
        runSafe("dashboard", () -> {
            if (dashboardSseBroadcaster != null) {
                dashboardSseBroadcaster.sendHeartbeats();
            }
        });
    }

    private void runSafe(String channel, Runnable action) {
        try {
            action.run();
        } catch (Exception ex) {
            LogContract.warn(log, LogDomain.SYSTEM, "sse_heartbeat_tick")
                    .outcome(LogOutcome.DEGRADED)
                    .field("channel", channel)
                    .cause(ex)
                    .emit();
        }
    }
}
