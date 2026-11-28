package com.nextturn.service.dashboard.impl;

import com.nextturn.dto.dashboard.DashboardSseEventResponse;
import com.nextturn.dto.dashboard.PlatformDashboardOverviewResponse;
import com.nextturn.dto.dashboard.TenantDashboardOverviewResponse;
import com.nextturn.infrastructure.logging.LogContract;
import com.nextturn.infrastructure.logging.LogDomain;
import com.nextturn.infrastructure.logging.LogOutcome;
import com.nextturn.infrastructure.multitenancy.TenantContextHolder;
import com.nextturn.service.dashboard.DashboardSseBroadcaster;
import com.nextturn.service.dashboard.PlatformDashboardService;
import com.nextturn.service.dashboard.TenantDashboardService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Thread-safe SSE registry pushing real-time KPI snapshots to Tenant Operations Dashboards
 * and Central Superadmin Platform Dashboards without page reload (REQ-DASHBOARD-01, REQ-DASHBOARD-05).
 */
@Component
public class DashboardSseBroadcasterImpl implements DashboardSseBroadcaster {

    private static final Logger log = LoggerFactory.getLogger(DashboardSseBroadcasterImpl.class);
    /**
     * Long-lived emitter (no artificial 30-minute cutoff); dead peers are evicted by periodic heartbeats.
     */
    private static final long SSE_TIMEOUT_MS = 0L;
    private static final long SSE_RECONNECT_TIME_MS = 3000L;

    private final ConcurrentHashMap<String, CopyOnWriteArrayList<SseEmitter>> tenantEmitters =
            new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<SseEmitter> platformEmitters = new CopyOnWriteArrayList<>();

    private final TenantDashboardService tenantDashboardService;
    private final PlatformDashboardService platformDashboardService;

    @Autowired
    public DashboardSseBroadcasterImpl(
            @Autowired(required = false) TenantDashboardService tenantDashboardService,
            @Autowired(required = false) PlatformDashboardService platformDashboardService
    ) {
        this.tenantDashboardService = tenantDashboardService;
        this.platformDashboardService = platformDashboardService;
    }

    @Override
    public SseEmitter subscribeTenantOverview(String tenantCode) {
        String key = tenantCode != null ? tenantCode.trim().toLowerCase() : "default";
        SseEmitter emitter = createEmitter();
        // Serialize add/remove per key inside ConcurrentHashMap.compute to prevent empty-list eviction races
        tenantEmitters.compute(key, (k, existing) -> {
            CopyOnWriteArrayList<SseEmitter> list = existing != null ? existing : new CopyOnWriteArrayList<>();
            list.add(emitter);
            return list;
        });
        Runnable cleanup = () -> removeTenantEmitter(key, emitter);
        registerCleanup(emitter, cleanup);

        if (tenantDashboardService != null) {
            TenantDashboardOverviewResponse snapshot = tenantDashboardService.getOverview();
            emitToList(List.of(emitter), DashboardSseEventResponse.builder()
                    .eventType("INITIAL_SNAPSHOT")
                    .scope("TENANT")
                    .tenantCode(key)
                    .occurredAt(LocalDateTime.now())
                    .tenantOverview(snapshot)
                    .build(), cleanup);
        }
        return emitter;
    }

    @Override
    public SseEmitter subscribePlatformOverview() {
        SseEmitter emitter = createEmitter();
        platformEmitters.add(emitter);
        registerCleanup(emitter, () -> platformEmitters.remove(emitter));

        if (platformDashboardService != null) {
            PlatformDashboardOverviewResponse snapshot = platformDashboardService.getPlatformOverview(7);
            emitToList(List.of(emitter), DashboardSseEventResponse.builder()
                    .eventType("INITIAL_SNAPSHOT")
                    .scope("PLATFORM")
                    .occurredAt(LocalDateTime.now())
                    .platformOverview(snapshot)
                    .build(), () -> platformEmitters.remove(emitter));
        }
        return emitter;
    }

    @Override
    public void broadcastTenantOverviewUpdate(String tenantCode, String eventType) {
        if (tenantDashboardService == null || tenantCode == null || tenantCode.isBlank()) {
            return;
        }
        String key = tenantCode.trim().toLowerCase();
        CopyOnWriteArrayList<SseEmitter> listeners = tenantEmitters.get(key);
        if (listeners == null || listeners.isEmpty()) {
            return;
        }
        String prevTenant = TenantContextHolder.getTenantCode();
        try {
            TenantContextHolder.setTenantCode(key);
            TenantDashboardOverviewResponse snapshot = tenantDashboardService.getOverview();
            DashboardSseEventResponse payload = DashboardSseEventResponse.builder()
                    .eventType(eventType)
                    .scope("TENANT")
                    .tenantCode(key)
                    .occurredAt(LocalDateTime.now())
                    .tenantOverview(snapshot)
                    .build();
            emitToList(listeners, payload, null, key);
        } catch (Exception ex) {
            LogContract.warn(log, LogDomain.DASHBOARD, "tenant_sse_broadcast")
                    .outcome(LogOutcome.DEGRADED)
                    .tenant(key)
                    .cause(ex)
                    .emit();
        } finally {
            if (prevTenant != null) {
                TenantContextHolder.setTenantCode(prevTenant);
            } else {
                TenantContextHolder.clear();
            }
        }
    }

    @Override
    public void broadcastPlatformOverviewUpdate(String eventType) {
        if (platformDashboardService == null || platformEmitters.isEmpty()) {
            return;
        }
        String prevTenant = TenantContextHolder.getTenantCode();
        try {
            TenantContextHolder.clear();
            PlatformDashboardOverviewResponse snapshot = platformDashboardService.getPlatformOverview(7);
            DashboardSseEventResponse payload = DashboardSseEventResponse.builder()
                    .eventType(eventType)
                    .scope("PLATFORM")
                    .occurredAt(LocalDateTime.now())
                    .platformOverview(snapshot)
                    .build();
            emitToList(platformEmitters, payload, null);
        } catch (Exception ex) {
            LogContract.warn(log, LogDomain.DASHBOARD, "platform_sse_broadcast")
                    .outcome(LogOutcome.DEGRADED)
                    .cause(ex)
                    .emit();
        } finally {
            if (prevTenant != null) {
                TenantContextHolder.setTenantCode(prevTenant);
            } else {
                TenantContextHolder.clear();
            }
        }
    }

    @Override
    public int sendHeartbeats() {
        int sent = 0;
        for (Map.Entry<String, CopyOnWriteArrayList<SseEmitter>> entry : tenantEmitters.entrySet()) {
            String key = entry.getKey();
            CopyOnWriteArrayList<SseEmitter> listeners = entry.getValue();
            if (listeners == null || listeners.isEmpty()) {
                continue;
            }
            for (SseEmitter emitter : listeners) {
                if (sendHeartbeat(emitter, key)) {
                    sent++;
                } else {
                    removeTenantEmitter(key, emitter);
                }
            }
        }
        for (SseEmitter emitter : platformEmitters) {
            if (sendHeartbeat(emitter, "platform")) {
                sent++;
            } else {
                platformEmitters.remove(emitter);
            }
        }
        return sent;
    }

    private boolean sendHeartbeat(SseEmitter emitter, String scopeKey) {
        try {
            synchronized (emitter) {
                emitter.send(SseEmitter.event().comment("heartbeat"));
            }
            return true;
        } catch (Exception ex) {
            LogContract.debug(log, LogDomain.DASHBOARD, "dashboard_sse_heartbeat_evict")
                    .outcome(LogOutcome.DEGRADED)
                    .tenant(scopeKey)
                    .cause(ex)
                    .emit();
            safeCompleteEmitter(emitter);
            return false;
        }
    }

    private void emitToList(List<SseEmitter> targets, DashboardSseEventResponse event, Runnable extraCleanup) {
        emitToList(targets, event, extraCleanup, null);
    }

    private void emitToList(
            List<SseEmitter> targets,
            DashboardSseEventResponse event,
            Runnable extraCleanup,
            String tenantKey
    ) {
        boolean includeReconnectHint = "INITIAL_SNAPSHOT".equals(event.getEventType());
        for (SseEmitter emitter : targets) {
            try {
                SseEmitter.SseEventBuilder eventBuilder = SseEmitter.event()
                        .name(event.getEventType())
                        .data(event);
                if (includeReconnectHint) {
                    eventBuilder.reconnectTime(SSE_RECONNECT_TIME_MS);
                }
                synchronized (emitter) {
                    emitter.send(eventBuilder);
                }
            } catch (Exception ex) {
                LogContract.warn(log, LogDomain.DASHBOARD, "dashboard_sse_push")
                        .outcome(LogOutcome.DEGRADED)
                        .tenant(tenantKey)
                        .cause(ex)
                        .emit();
                if (tenantKey != null) {
                    removeTenantEmitter(tenantKey, emitter);
                } else if (targets instanceof CopyOnWriteArrayList<SseEmitter> cow) {
                    cow.remove(emitter);
                }
                if (extraCleanup != null) {
                    extraCleanup.run();
                }
                safeCompleteEmitter(emitter);
            }
        }
    }

    private void safeCompleteEmitter(SseEmitter emitter) {
        try {
            emitter.complete();
        } catch (Exception ignored) {
            // Emitter is already completed or response stream is already closed
        }
    }

    private void removeTenantEmitter(String key, SseEmitter emitter) {
        tenantEmitters.computeIfPresent(key, (k, existing) -> {
            existing.remove(emitter);
            return existing.isEmpty() ? null : existing;
        });
    }

    protected SseEmitter createEmitter() {
        return new SseEmitter(SSE_TIMEOUT_MS);
    }

    private void registerCleanup(SseEmitter emitter, Runnable cleanup) {
        emitter.onCompletion(cleanup);
        emitter.onTimeout(cleanup);
        emitter.onError(ex -> cleanup.run());
    }
}
