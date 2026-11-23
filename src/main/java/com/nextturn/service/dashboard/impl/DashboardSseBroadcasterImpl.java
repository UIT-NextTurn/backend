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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Thread-safe SSE registry pushing real-time KPI snapshots to Tenant Operations Dashboards
 * and Central Superadmin Platform Dashboards without page reload (REQ-DASHBOARD-01, REQ-DASHBOARD-05).
 */
@Component
public class DashboardSseBroadcasterImpl implements DashboardSseBroadcaster {

    private static final Logger log = LoggerFactory.getLogger(DashboardSseBroadcasterImpl.class);
    private static final long SSE_TIMEOUT_MS = 30L * 60L * 1000L;

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
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
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
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
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

    private void emitToList(List<SseEmitter> targets, DashboardSseEventResponse event, Runnable extraCleanup) {
        emitToList(targets, event, extraCleanup, null);
    }

    private void emitToList(
            List<SseEmitter> targets,
            DashboardSseEventResponse event,
            Runnable extraCleanup,
            String tenantKey
    ) {
        for (SseEmitter emitter : targets) {
            try {
                emitter.send(SseEmitter.event().name(event.getEventType()).data(event));
            } catch (Exception ex) {
                if (tenantKey != null) {
                    removeTenantEmitter(tenantKey, emitter);
                } else if (targets instanceof CopyOnWriteArrayList<SseEmitter> cow) {
                    cow.remove(emitter);
                }
                if (extraCleanup != null) {
                    extraCleanup.run();
                }
                emitter.complete();
            }
        }
    }

    private void removeTenantEmitter(String key, SseEmitter emitter) {
        tenantEmitters.computeIfPresent(key, (k, existing) -> {
            existing.remove(emitter);
            return existing.isEmpty() ? null : existing;
        });
    }

    private void registerCleanup(SseEmitter emitter, Runnable cleanup) {
        emitter.onCompletion(cleanup);
        emitter.onTimeout(cleanup);
        emitter.onError(ex -> cleanup.run());
    }
}
