package com.nextturn.service.dashboard.impl;

import com.nextturn.dto.dashboard.DashboardSseEventResponse;
import com.nextturn.dto.dashboard.PlatformDashboardOverviewResponse;
import com.nextturn.dto.dashboard.TenantDashboardOverviewResponse;
import com.nextturn.infrastructure.logging.LogContract;
import com.nextturn.infrastructure.logging.LogDomain;
import com.nextturn.infrastructure.logging.LogOutcome;
import com.nextturn.infrastructure.multitenancy.TenantContextHolder;
import com.nextturn.infrastructure.sse.SseEventReplayBuffer;
import com.nextturn.infrastructure.sse.SseEventReplayBuffer.BufferedEvent;
import com.nextturn.infrastructure.sse.TenantSseConnectionLimiter;
import com.nextturn.service.dashboard.DashboardSseBroadcaster;
import com.nextturn.service.dashboard.PlatformDashboardService;
import com.nextturn.service.dashboard.TenantDashboardService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

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
    private static final String PLATFORM_CHANNEL_KEY = "platform";

    private final ConcurrentHashMap<String, Set<SseEmitter>> tenantEmitters = new ConcurrentHashMap<>();
    private final Set<SseEmitter> platformEmitters = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<SseEmitter, AtomicLong> lastDeliveredSeq = new ConcurrentHashMap<>();

    private final TenantDashboardService tenantDashboardService;
    private final PlatformDashboardService platformDashboardService;
    private TenantSseConnectionLimiter connectionLimiter;
    private SseEventReplayBuffer<DashboardSseEventResponse> tenantReplayBuffer = new SseEventReplayBuffer<>();
    private SseEventReplayBuffer<DashboardSseEventResponse> platformReplayBuffer = new SseEventReplayBuffer<>();

    @Autowired
    public DashboardSseBroadcasterImpl(
            @Autowired(required = false) TenantDashboardService tenantDashboardService,
            @Autowired(required = false) PlatformDashboardService platformDashboardService
    ) {
        this.tenantDashboardService = tenantDashboardService;
        this.platformDashboardService = platformDashboardService;
    }

    @Autowired(required = false)
    public void setConnectionLimiter(TenantSseConnectionLimiter connectionLimiter) {
        this.connectionLimiter = connectionLimiter;
    }

    @Autowired(required = false)
    public void setReplayBufferSize(@Value("${nextturn.sse.replay.buffer-size:100}") int replayBufferSize) {
        this.tenantReplayBuffer = new SseEventReplayBuffer<>(replayBufferSize);
        this.platformReplayBuffer = new SseEventReplayBuffer<>(replayBufferSize);
    }

    @Override
    public SseEmitter subscribeTenantOverview(String tenantCode) {
        return subscribeTenantOverview(tenantCode, null);
    }

    @Override
    public SseEmitter subscribeTenantOverview(String tenantCode, String lastEventId) {
        String key = tenantCode != null ? tenantCode.trim().toLowerCase() : "default";
        if (connectionLimiter != null) {
            connectionLimiter.acquireTenantSlot(key);
        }

        long seqBeforeSnapshot = tenantReplayBuffer.currentSequence(key);
        TenantDashboardOverviewResponse snapshot = null;
        if (tenantDashboardService != null) {
            try {
                snapshot = tenantDashboardService.getOverview();
            } catch (RuntimeException ex) {
                if (connectionLimiter != null) {
                    connectionLimiter.releaseTenantSlot(key);
                }
                throw ex;
            }
        }

        SseEmitter emitter = createEmitter();
        Runnable cleanup = () -> removeTenantEmitter(key, emitter);
        registerCleanup(emitter, cleanup);

        synchronized (emitter) {
            tenantEmitters.compute(key, (k, existing) -> {
                Set<SseEmitter> set = existing != null ? existing : ConcurrentHashMap.newKeySet();
                set.add(emitter);
                return set;
            });
            AtomicLong deliveredTracker = new AtomicLong(0L);
            lastDeliveredSeq.put(emitter, deliveredTracker);

            if (tenantDashboardService != null) {
                DashboardSseEventResponse initialEvent = DashboardSseEventResponse.builder()
                        .eventType("INITIAL_SNAPSHOT")
                        .scope("TENANT")
                        .tenantCode(key)
                        .occurredAt(LocalDateTime.now())
                        .tenantOverview(snapshot)
                        .build();
                if (!sendEvent(emitter, initialEvent, tenantReplayBuffer.currentEventId(key), true, key)) {
                    cleanup.run();
                    return emitter;
                }

                long clientSeq = tenantReplayBuffer.parseSequenceIfSameEpoch(lastEventId);
                long replayBaseline = clientSeq >= 0L
                        ? Math.min(clientSeq, seqBeforeSnapshot)
                        : seqBeforeSnapshot;
                long maxSentSeq = seqBeforeSnapshot;
                for (BufferedEvent<DashboardSseEventResponse> missed : tenantReplayBuffer.eventsAfter(key, replayBaseline)) {
                    if (!sendEvent(emitter, missed.payload(), missed.eventId(), false, key)) {
                        cleanup.run();
                        return emitter;
                    }
                    maxSentSeq = Math.max(maxSentSeq, missed.sequence());
                }
                deliveredTracker.set(maxSentSeq);
            }
        }
        return emitter;
    }

    @Override
    public SseEmitter subscribePlatformOverview() {
        return subscribePlatformOverview(null);
    }

    @Override
    public SseEmitter subscribePlatformOverview(String lastEventId) {
        if (connectionLimiter != null) {
            connectionLimiter.acquirePlatformSlot();
        }

        long seqBeforeSnapshot = platformReplayBuffer.currentSequence(PLATFORM_CHANNEL_KEY);
        PlatformDashboardOverviewResponse snapshot = null;
        if (platformDashboardService != null) {
            try {
                snapshot = platformDashboardService.getPlatformOverview(7);
            } catch (RuntimeException ex) {
                if (connectionLimiter != null) {
                    connectionLimiter.releasePlatformSlot();
                }
                throw ex;
            }
        }

        SseEmitter emitter = createEmitter();
        Runnable cleanup = () -> removePlatformEmitter(emitter);
        registerCleanup(emitter, cleanup);

        synchronized (emitter) {
            platformEmitters.add(emitter);
            AtomicLong deliveredTracker = new AtomicLong(0L);
            lastDeliveredSeq.put(emitter, deliveredTracker);

            if (platformDashboardService != null) {
                DashboardSseEventResponse initialEvent = DashboardSseEventResponse.builder()
                        .eventType("INITIAL_SNAPSHOT")
                        .scope("PLATFORM")
                        .occurredAt(LocalDateTime.now())
                        .platformOverview(snapshot)
                        .build();
                if (!sendEvent(emitter, initialEvent, platformReplayBuffer.currentEventId(PLATFORM_CHANNEL_KEY), true, null)) {
                    cleanup.run();
                    return emitter;
                }

                long clientSeq = platformReplayBuffer.parseSequenceIfSameEpoch(lastEventId);
                long replayBaseline = clientSeq >= 0L
                        ? Math.min(clientSeq, seqBeforeSnapshot)
                        : seqBeforeSnapshot;
                long maxSentSeq = seqBeforeSnapshot;
                for (BufferedEvent<DashboardSseEventResponse> missed : platformReplayBuffer.eventsAfter(PLATFORM_CHANNEL_KEY, replayBaseline)) {
                    if (!sendEvent(emitter, missed.payload(), missed.eventId(), false, null)) {
                        cleanup.run();
                        return emitter;
                    }
                    maxSentSeq = Math.max(maxSentSeq, missed.sequence());
                }
                deliveredTracker.set(maxSentSeq);
            }
        }
        return emitter;
    }

    @Override
    public void broadcastTenantOverviewUpdate(String tenantCode, String eventType) {
        if (tenantDashboardService == null || tenantCode == null || tenantCode.isBlank()) {
            return;
        }
        String key = tenantCode.trim().toLowerCase();
        Set<SseEmitter> listeners = tenantEmitters.get(key);
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
            BufferedEvent<DashboardSseEventResponse> recorded = tenantReplayBuffer.record(key, payload);
            for (SseEmitter emitter : listeners) {
                if (!sendLiveEventIfNewer(emitter, recorded, key)) {
                    removeTenantEmitter(key, emitter);
                }
            }
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
            BufferedEvent<DashboardSseEventResponse> recorded = platformReplayBuffer.record(PLATFORM_CHANNEL_KEY, payload);
            for (SseEmitter emitter : platformEmitters) {
                if (!sendLiveEventIfNewer(emitter, recorded, null)) {
                    removePlatformEmitter(emitter);
                }
            }
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

    private boolean sendLiveEventIfNewer(
            SseEmitter emitter,
            BufferedEvent<DashboardSseEventResponse> event,
            String tenantKey
    ) {
        synchronized (emitter) {
            AtomicLong tracker = lastDeliveredSeq.get(emitter);
            if (tracker != null && tracker.get() >= event.sequence()) {
                return true;
            }
            boolean ok = sendEvent(emitter, event.payload(), event.eventId(), false, tenantKey);
            if (ok && tracker != null) {
                tracker.set(event.sequence());
            }
            return ok;
        }
    }

    @Override
    public int sendHeartbeats() {
        int sent = 0;
        for (Map.Entry<String, Set<SseEmitter>> entry : tenantEmitters.entrySet()) {
            String key = entry.getKey();
            Set<SseEmitter> listeners = entry.getValue();
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
            if (sendHeartbeat(emitter, PLATFORM_CHANNEL_KEY)) {
                sent++;
            } else {
                removePlatformEmitter(emitter);
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

    private boolean sendEvent(
            SseEmitter emitter,
            DashboardSseEventResponse event,
            String eventId,
            boolean includeReconnectHint,
            String tenantKey
    ) {
        try {
            SseEmitter.SseEventBuilder eventBuilder = SseEmitter.event()
                    .id(eventId)
                    .name(event.getEventType())
                    .data(event);
            if (includeReconnectHint) {
                eventBuilder.reconnectTime(SSE_RECONNECT_TIME_MS);
            }
            synchronized (emitter) {
                emitter.send(eventBuilder);
            }
            return true;
        } catch (Exception ex) {
            LogContract.warn(log, LogDomain.DASHBOARD, "dashboard_sse_push")
                    .outcome(LogOutcome.DEGRADED)
                    .tenant(tenantKey)
                    .cause(ex)
                    .emit();
            safeCompleteEmitter(emitter);
            return false;
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
        boolean[] removed = {false};
        tenantEmitters.computeIfPresent(key, (k, existing) -> {
            removed[0] = existing.remove(emitter);
            return existing.isEmpty() ? null : existing;
        });
        lastDeliveredSeq.remove(emitter);
        if (removed[0] && connectionLimiter != null) {
            connectionLimiter.releaseTenantSlot(key);
        }
    }

    private void removePlatformEmitter(SseEmitter emitter) {
        boolean removed = platformEmitters.remove(emitter);
        lastDeliveredSeq.remove(emitter);
        if (removed && connectionLimiter != null) {
            connectionLimiter.releasePlatformSlot();
        }
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
