package com.nextturn.service.ticket.impl;

import com.nextturn.dto.ticket.TicketQueueEventResponse;
import com.nextturn.dto.ticket.TicketResponse;
import com.nextturn.enums.TicketStatus;
import com.nextturn.infrastructure.logging.LogContract;
import com.nextturn.infrastructure.logging.LogDomain;
import com.nextturn.infrastructure.logging.LogOutcome;
import com.nextturn.infrastructure.multitenancy.TenantContextHolder;
import com.nextturn.infrastructure.sse.SseEventReplayBuffer;
import com.nextturn.infrastructure.sse.SseEventReplayBuffer.BufferedEvent;
import com.nextturn.infrastructure.sse.TenantSseConnectionLimiter;
import com.nextturn.service.ticket.TenantTicketQueryService;
import com.nextturn.service.ticket.TenantTicketSseBroadcaster;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Thread-safe SSE connection registry backed by {@link ConcurrentHashMap} for pushing
 * real-time queue progress updates to virtual ticket screens without polling (REQ-TICKET-03).
 */
@Component
public class TenantTicketSseBroadcasterImpl implements TenantTicketSseBroadcaster {

    private static final Logger log = LoggerFactory.getLogger(TenantTicketSseBroadcasterImpl.class);
    /**
     * Long-lived emitter (no artificial 30-minute cutoff); dead peers are evicted by periodic heartbeats.
     */
    private static final long SSE_TIMEOUT_MS = 0L;
    private static final long SSE_RECONNECT_TIME_MS = 3000L;

    /**
     * Maps {@code queueKey (tenantCode:serviceId:workDate)} -> ({@code ticketId} -> lock-free set of active {@link SseEmitter}s).
     * Supports multiple concurrent devices/tabs watching the same ticket with O(1) add/remove.
     */
    private final ConcurrentHashMap<String, ConcurrentHashMap<String, Set<SseEmitter>>> queueSubscribers =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<SseEmitter, AtomicLong> lastDeliveredSeq = new ConcurrentHashMap<>();
    private final AtomicLong globalBroadcastCounter = new AtomicLong(0L);

    private final TenantTicketQueryService ticketQueryService;
    private TenantSseConnectionLimiter connectionLimiter;
    private SseEventReplayBuffer<TicketQueueEventResponse> replayBuffer = new SseEventReplayBuffer<>();

    @Autowired
    public TenantTicketSseBroadcasterImpl(@Autowired(required = false) TenantTicketQueryService ticketQueryService) {
        this.ticketQueryService = ticketQueryService;
    }

    @Autowired(required = false)
    public void setConnectionLimiter(TenantSseConnectionLimiter connectionLimiter) {
        this.connectionLimiter = connectionLimiter;
    }

    @Autowired(required = false)
    public void setReplayBufferSize(@Value("${nextturn.sse.replay.buffer-size:100}") int replayBufferSize) {
        this.replayBuffer = new SseEventReplayBuffer<>(replayBufferSize);
    }

    @Override
    public SseEmitter subscribe(String tenantCode, String ticketId) {
        return subscribe(tenantCode, ticketId, null);
    }

    @Override
    public SseEmitter subscribe(String tenantCode, String ticketId, String lastEventId) {
        if (ticketQueryService == null) {
            SseEmitter emitter = createEmitter();
            emitter.complete();
            return emitter;
        }

        String tenantKey = normalizeTenant(tenantCode);
        if (connectionLimiter != null) {
            connectionLimiter.acquireTenantSlot(tenantKey);
        }

        long broadcastEpochBeforeRead = globalBroadcastCounter.get();
        TicketResponse snapshot;
        try {
            snapshot = ticketQueryService.getTicketDetails(ticketId);
        } catch (RuntimeException ex) {
            if (connectionLimiter != null) {
                connectionLimiter.releaseTenantSlot(tenantKey);
            }
            throw ex;
        }

        String resolvedTicketId = snapshot.getId();
        String queueKey = buildQueueKey(tenantKey, snapshot.getServiceId(), snapshot.getWorkDate());
        String ticketChannelKey = buildTicketChannelKey(queueKey, resolvedTicketId);
        SseEmitter emitter = createEmitter();

        Runnable cleanup = () -> removeTicketEmitter(tenantKey, queueKey, resolvedTicketId, emitter);
        emitter.onCompletion(cleanup);
        emitter.onTimeout(cleanup);
        emitter.onError(ex -> cleanup.run());

        synchronized (emitter) {
            queueSubscribers.compute(queueKey, (k, existingChannel) -> {
                ConcurrentHashMap<String, Set<SseEmitter>> channel =
                        existingChannel != null ? existingChannel : new ConcurrentHashMap<>();
                channel.compute(resolvedTicketId, (tk, existingSet) -> {
                    Set<SseEmitter> set = existingSet != null ? existingSet : ConcurrentHashMap.newKeySet();
                    set.add(emitter);
                    return set;
                });
                return channel;
            });
            AtomicLong deliveredTracker = new AtomicLong(0L);
            lastDeliveredSeq.put(emitter, deliveredTracker);

            // Close snapshot-vs-registration race: if a broadcast occurred during initial DB read, refresh snapshot once
            if (globalBroadcastCounter.get() != broadcastEpochBeforeRead) {
                try {
                    snapshot = ticketQueryService.getTicketDetails(resolvedTicketId);
                } catch (Exception ignored) {
                    // Fall back to already fetched snapshot
                }
            }

            long seqBeforeSend = replayBuffer.currentSequence(ticketChannelKey);
            String snapshotEventId = replayBuffer.currentEventId(ticketChannelKey);

            if (!sendEvent(emitter, toEvent("INITIAL_SNAPSHOT", snapshot), snapshotEventId, true)) {
                cleanup.run();
                return emitter;
            }

            long clientSeq = replayBuffer.parseSequenceIfSameEpoch(lastEventId);
            long maxSentSeq = seqBeforeSend;
            if (clientSeq >= 0L) {
                for (BufferedEvent<TicketQueueEventResponse> missed : replayBuffer.eventsAfter(ticketChannelKey, clientSeq)) {
                    if (!sendEvent(emitter, missed.payload(), missed.eventId(), false)) {
                        cleanup.run();
                        return emitter;
                    }
                    maxSentSeq = Math.max(maxSentSeq, missed.sequence());
                }
            }
            deliveredTracker.set(maxSentSeq);
        }
        return emitter;
    }

    private void removeTicketEmitter(String tenantKey, String queueKey, String ticketId, SseEmitter emitter) {
        boolean[] removed = {false};
        queueSubscribers.computeIfPresent(queueKey, (k, channel) -> {
            channel.computeIfPresent(ticketId, (tk, set) -> {
                removed[0] = set.remove(emitter);
                return set.isEmpty() ? null : set;
            });
            return channel.isEmpty() ? null : channel;
        });
        lastDeliveredSeq.remove(emitter);
        if (removed[0] && connectionLimiter != null) {
            connectionLimiter.releaseTenantSlot(tenantKey);
        }
    }

    @Override
    public void broadcastQueueUpdate(String tenantCode, String serviceId, LocalDate workDate, String eventType) {
        if (ticketQueryService == null || serviceId == null || workDate == null) {
            return;
        }
        globalBroadcastCounter.incrementAndGet();
        String tenantKey = normalizeTenant(tenantCode);
        String queueKey = buildQueueKey(tenantKey, serviceId, workDate);
        ConcurrentHashMap<String, Set<SseEmitter>> channel = queueSubscribers.get(queueKey);
        if (channel == null || channel.isEmpty()) {
            return;
        }

        String previousTenant = TenantContextHolder.getTenantCode();
        try {
            if (tenantCode != null && !tenantCode.isBlank()) {
                TenantContextHolder.setTenantCode(tenantCode);
            }

            Set<String> subscribedTicketIds = new HashSet<>(channel.keySet());
            if (subscribedTicketIds.isEmpty()) {
                return;
            }

            // Bounded O(1) batch query for all subscribed tickets in this service queue
            Map<String, TicketResponse> snapshots = ticketQueryService.getTicketDetailsBatch(
                    serviceId, workDate, subscribedTicketIds
            );
            if (snapshots == null || snapshots.isEmpty()) {
                return;
            }

            for (Map.Entry<String, Set<SseEmitter>> entry : channel.entrySet()) {
                String ticketId = entry.getKey();
                Set<SseEmitter> emitters = entry.getValue();
                if (emitters == null || emitters.isEmpty()) {
                    continue;
                }
                TicketResponse updated = snapshots.get(ticketId);
                if (updated == null) {
                    continue;
                }
                TicketQueueEventResponse payload = toEvent(eventType, updated);
                BufferedEvent<TicketQueueEventResponse> recorded =
                        replayBuffer.record(buildTicketChannelKey(queueKey, ticketId), payload);

                for (SseEmitter emitter : emitters) {
                    if (!sendLiveEventIfNewer(emitter, recorded)) {
                        removeTicketEmitter(tenantKey, queueKey, ticketId, emitter);
                    }
                }
            }
        } catch (Exception ex) {
            LogContract.warn(log, LogDomain.TICKET, "ticket_sse_broadcast")
                    .outcome(LogOutcome.DEGRADED)
                    .tenant(tenantCode)
                    .field("serviceId", serviceId)
                    .cause(ex)
                    .emit();
        } finally {
            if (previousTenant != null) {
                TenantContextHolder.setTenantCode(previousTenant);
            } else {
                TenantContextHolder.clear();
            }
        }
    }

    private boolean sendLiveEventIfNewer(SseEmitter emitter, BufferedEvent<TicketQueueEventResponse> event) {
        synchronized (emitter) {
            AtomicLong tracker = lastDeliveredSeq.get(emitter);
            if (tracker != null && tracker.get() >= event.sequence()) {
                return true;
            }
            boolean ok = sendEvent(emitter, event.payload(), event.eventId(), false);
            if (ok && tracker != null) {
                tracker.set(event.sequence());
            }
            return ok;
        }
    }

    @Override
    public int sendHeartbeats() {
        int sent = 0;
        for (Map.Entry<String, ConcurrentHashMap<String, Set<SseEmitter>>> queueEntry : queueSubscribers.entrySet()) {
            String queueKey = queueEntry.getKey();
            String tenantKey = extractTenantFromQueueKey(queueKey);
            ConcurrentHashMap<String, Set<SseEmitter>> channel = queueEntry.getValue();
            if (channel == null || channel.isEmpty()) {
                continue;
            }
            for (Map.Entry<String, Set<SseEmitter>> ticketEntry : channel.entrySet()) {
                String ticketId = ticketEntry.getKey();
                Set<SseEmitter> emitters = ticketEntry.getValue();
                if (emitters == null || emitters.isEmpty()) {
                    continue;
                }
                for (SseEmitter emitter : emitters) {
                    if (sendHeartbeat(emitter, ticketId)) {
                        sent++;
                    } else {
                        removeTicketEmitter(tenantKey, queueKey, ticketId, emitter);
                    }
                }
            }
        }
        return sent;
    }

    private boolean sendHeartbeat(SseEmitter emitter, String ticketId) {
        try {
            synchronized (emitter) {
                emitter.send(SseEmitter.event().comment("heartbeat"));
            }
            return true;
        } catch (Exception ex) {
            LogContract.debug(log, LogDomain.TICKET, "ticket_sse_heartbeat_evict")
                    .outcome(LogOutcome.DEGRADED)
                    .entity("ticket", ticketId)
                    .cause(ex)
                    .emit();
            safeCompleteEmitter(emitter, ex);
            return false;
        }
    }

    private boolean sendEvent(SseEmitter emitter, TicketQueueEventResponse payload, String eventId, boolean includeReconnectHint) {
        try {
            SseEmitter.SseEventBuilder eventBuilder = SseEmitter.event()
                    .id(eventId)
                    .name("queue-progress")
                    .data(payload);
            if (includeReconnectHint) {
                eventBuilder.reconnectTime(SSE_RECONNECT_TIME_MS);
            }
            synchronized (emitter) {
                emitter.send(eventBuilder);
            }
            return true;
        } catch (Exception ex) {
            LogContract.warn(log, LogDomain.TICKET, "sse_push")
                    .outcome(LogOutcome.DEGRADED)
                    .entity("ticket", payload.getTicketCode())
                    .cause(ex)
                    .emit();
            safeCompleteEmitter(emitter, ex);
            return false;
        }
    }

    private void safeCompleteEmitter(SseEmitter emitter, Exception cause) {
        try {
            if (cause instanceof IOException) {
                emitter.completeWithError(cause);
            } else {
                emitter.complete();
            }
        } catch (Exception ignored) {
            // Emitter is already completed or response stream is already closed
        }
    }

    private TicketQueueEventResponse toEvent(String eventType, TicketResponse snapshot) {
        boolean isCalling = TicketStatus.CALLING.equals(snapshot.getStatus());
        boolean isApproaching = !isCalling && Boolean.TRUE.equals(snapshot.getApproachingTurnAlert());
        String highlightColor = isCalling ? "#EF4444" : (isApproaching ? "#F97316" : null);
        String counterDesk = snapshot.getCounterLabel() != null && !snapshot.getCounterLabel().isBlank()
                ? snapshot.getCounterLabel()
                : snapshot.getCounterStaffName();
        String alertMessage = null;
        if (isCalling) {
            alertMessage = "Số thứ tự [" + snapshot.getTicketCode() + "] xin mời đến "
                    + (counterDesk != null && !counterDesk.isBlank() ? counterDesk : "quầy tiếp nhận");
        } else if (isApproaching) {
            long ahead = snapshot.getPeopleAheadCount() != null ? snapshot.getPeopleAheadCount() : 0L;
            alertMessage = "Sắp đến lượt của bạn! Còn " + ahead + " khách phía trước.";
        }

        return TicketQueueEventResponse.builder()
                .eventType(eventType)
                .ticketId(snapshot.getId())
                .ticketCode(snapshot.getTicketCode())
                .status(snapshot.getStatus())
                .currentServingNumber(snapshot.getCurrentServingNumber())
                .queuePosition(snapshot.getQueuePosition())
                .peopleAheadCount(snapshot.getPeopleAheadCount())
                .estimatedWaitMinutes(snapshot.getEstimatedWaitMinutes())
                .approachingTurnAlert(snapshot.getApproachingTurnAlert())
                .highPriorityCall(isCalling)
                .vibrateAlert(isCalling || isApproaching)
                .highlightColor(highlightColor)
                .counterLabel(counterDesk)
                .alertMessage(alertMessage)
                .feedbackToken(snapshot.getFeedbackToken())
                .feedbackExpiresAt(snapshot.getFeedbackExpiresAt())
                .timestamp(LocalDateTime.now())
                .build();
    }

    protected SseEmitter createEmitter() {
        return new SseEmitter(SSE_TIMEOUT_MS);
    }

    private String normalizeTenant(String tenantCode) {
        return tenantCode != null ? tenantCode.trim().toLowerCase() : "";
    }

    private String buildQueueKey(String cleanTenant, String serviceId, LocalDate workDate) {
        return cleanTenant + ":" + serviceId + ":" + workDate;
    }

    private String buildTicketChannelKey(String queueKey, String ticketId) {
        return queueKey + ":" + ticketId;
    }

    private String extractTenantFromQueueKey(String queueKey) {
        if (queueKey == null) {
            return "";
        }
        int sep = queueKey.indexOf(':');
        return sep >= 0 ? queueKey.substring(0, sep) : queueKey;
    }
}
