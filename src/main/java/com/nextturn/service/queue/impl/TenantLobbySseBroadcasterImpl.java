package com.nextturn.service.queue.impl;

import com.nextturn.dto.queue.LobbyDisplayResponse;
import com.nextturn.dto.queue.LobbyEventResponse;
import com.nextturn.dto.queue.QueueTicketSummaryResponse;
import com.nextturn.entity.TenantQueueTicket;
import com.nextturn.enums.TicketStatus;
import com.nextturn.infrastructure.logging.LogContract;
import com.nextturn.infrastructure.logging.LogDomain;
import com.nextturn.infrastructure.logging.LogOutcome;
import com.nextturn.infrastructure.multitenancy.TenantContextHolder;
import com.nextturn.infrastructure.sse.SseEventReplayBuffer;
import com.nextturn.infrastructure.sse.SseEventReplayBuffer.BufferedEvent;
import com.nextturn.infrastructure.sse.TenantSseConnectionLimiter;
import com.nextturn.repository.ticket.TenantQueueTicketRepository;
import com.nextturn.service.queue.TenantLobbySseBroadcaster;
import com.nextturn.service.queue.TenantTicketStateMachine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Thread-safe SSE broadcaster pushing live Lobby TV announcements and Counter Kanban updates
 * whenever queue ticket states advance (REQ-QC-01..04).
 */
@Component
public class TenantLobbySseBroadcasterImpl implements TenantLobbySseBroadcaster {

    private static final Logger log = LoggerFactory.getLogger(TenantLobbySseBroadcasterImpl.class);
    /**
     * Long-lived emitter (no artificial 30-minute cutoff); dead peers are evicted by periodic heartbeats.
     */
    private static final long SSE_TIMEOUT_MS = 0L;
    private static final long SSE_RECONNECT_TIME_MS = 3000L;

    private final ConcurrentHashMap<String, Set<SseEmitter>> lobbySubscribers = new ConcurrentHashMap<>();
    private final Set<String> observedTenants = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<SseEmitter, AtomicLong> lastDeliveredSeq = new ConcurrentHashMap<>();

    private final TenantQueueTicketRepository ticketRepository;
    private final TenantTicketStateMachine stateMachine;
    private TenantSseConnectionLimiter connectionLimiter;
    private SseEventReplayBuffer<LobbyEventResponse> replayBuffer = new SseEventReplayBuffer<>();

    @Autowired
    public TenantLobbySseBroadcasterImpl(
            @Autowired(required = false) TenantQueueTicketRepository ticketRepository,
            @Autowired(required = false) TenantTicketStateMachine stateMachine
    ) {
        this.ticketRepository = ticketRepository;
        this.stateMachine = stateMachine;
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
    public SseEmitter subscribe(String tenantCode) {
        return subscribe(tenantCode, null);
    }

    @Override
    public SseEmitter subscribe(String tenantCode, String lastEventId) {
        String key = normalizeTenant(tenantCode);
        if (connectionLimiter != null) {
            connectionLimiter.acquireTenantSlot(key);
        }

        SseEmitter emitter = createEmitter();
        observedTenants.add(key);

        long seqBeforeSnapshot = replayBuffer.currentSequence(key);
        LobbyDisplayResponse snapshot;
        try {
            snapshot = buildLobbySnapshot(key);
        } catch (RuntimeException ex) {
            if (connectionLimiter != null) {
                connectionLimiter.releaseTenantSlot(key);
            }
            throw ex;
        }

        Runnable cleanup = () -> removeLobbyEmitter(key, emitter);
        emitter.onCompletion(cleanup);
        emitter.onTimeout(cleanup);
        emitter.onError(ex -> cleanup.run());

        synchronized (emitter) {
            lobbySubscribers.compute(key, (k, existing) -> {
                Set<SseEmitter> set = existing != null ? existing : ConcurrentHashMap.newKeySet();
                set.add(emitter);
                return set;
            });
            AtomicLong deliveredTracker = new AtomicLong(0L);
            lastDeliveredSeq.put(emitter, deliveredTracker);

            long clientSeq = replayBuffer.parseSequenceIfSameEpoch(lastEventId);
            String snapshotEventId = replayBuffer.currentEventId(key);

            if (!sendEvent(emitter, LobbyEventResponse.builder()
                    .eventType("INITIAL_DISPLAY")
                    .display(snapshot)
                    .timestamp(LocalDateTime.now())
                    .build(), snapshotEventId, true)) {
                cleanup.run();
                return emitter;
            }

            long replayBaseline = clientSeq >= 0L
                    ? Math.min(clientSeq, seqBeforeSnapshot)
                    : seqBeforeSnapshot;
            long maxSentSeq = seqBeforeSnapshot;
            for (BufferedEvent<LobbyEventResponse> missed : replayBuffer.eventsAfter(key, replayBaseline)) {
                if (!sendEvent(emitter, missed.payload(), missed.eventId(), false)) {
                    cleanup.run();
                    return emitter;
                }
                maxSentSeq = Math.max(maxSentSeq, missed.sequence());
            }
            deliveredTracker.set(maxSentSeq);
        }
        return emitter;
    }

    private void removeLobbyEmitter(String key, SseEmitter emitter) {
        boolean[] removed = {false};
        lobbySubscribers.computeIfPresent(key, (k, existing) -> {
            removed[0] = existing.remove(emitter);
            return existing.isEmpty() ? null : existing;
        });
        lastDeliveredSeq.remove(emitter);
        if (removed[0] && connectionLimiter != null) {
            connectionLimiter.releaseTenantSlot(key);
        }
    }

    @Override
    public void broadcastLobbyEvent(String tenantCode, String eventType, TenantQueueTicket changedTicket) {
        String key = normalizeTenant(tenantCode);
        Set<SseEmitter> emitters = lobbySubscribers.get(key);
        boolean hasSubscribers = emitters != null && !emitters.isEmpty();
        if (!hasSubscribers && !observedTenants.contains(key)) {
            return;
        }

        String previousTenant = TenantContextHolder.getTenantCode();
        try {
            if (!key.isBlank()) {
                TenantContextHolder.setTenantCode(key);
            }
            LobbyDisplayResponse snapshot = buildLobbySnapshot(key);
            QueueTicketSummaryResponse card = toSummaryCard(changedTicket, LocalDateTime.now());
            String announcement = buildAnnouncement(eventType, changedTicket);

            LobbyEventResponse payload = LobbyEventResponse.builder()
                    .eventType(eventType)
                    .ticket(card)
                    .display(snapshot)
                    .announcementMessage(announcement)
                    .timestamp(LocalDateTime.now())
                    .build();

            BufferedEvent<LobbyEventResponse> recorded = replayBuffer.record(key, payload);
            if (!hasSubscribers) {
                return;
            }

            for (SseEmitter emitter : emitters) {
                if (!sendLiveEventIfNewer(emitter, recorded)) {
                    removeLobbyEmitter(key, emitter);
                }
            }
        } catch (Exception ex) {
            LogContract.warn(log, LogDomain.QUEUE, "lobby_sse_broadcast")
                    .outcome(LogOutcome.DEGRADED)
                    .tenant(key)
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

    private boolean sendLiveEventIfNewer(SseEmitter emitter, BufferedEvent<LobbyEventResponse> event) {
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

    private LobbyDisplayResponse buildLobbySnapshot(String tenantCode) {
        LocalDate today = LocalDate.now();
        if (ticketRepository == null) {
            return LobbyDisplayResponse.builder()
                    .tenantCode(tenantCode)
                    .workDate(today)
                    .callingTickets(List.of())
                    .servingTickets(List.of())
                    .generatedAt(LocalDateTime.now())
                    .build();
        }
        LocalDateTime now = LocalDateTime.now();
        List<TenantQueueTicket> active = ticketRepository.findActiveLobbyTicketsByWorkDate(today);
        List<QueueTicketSummaryResponse> calling = active.stream()
                .filter(t -> t.getStatus() == TicketStatus.CALLING)
                .map(t -> toSummaryCard(t, now))
                .toList();
        List<QueueTicketSummaryResponse> serving = active.stream()
                .filter(t -> t.getStatus() == TicketStatus.SERVING)
                .map(t -> toSummaryCard(t, now))
                .toList();

        return LobbyDisplayResponse.builder()
                .tenantCode(tenantCode)
                .workDate(today)
                .callingTickets(calling)
                .servingTickets(serving)
                .generatedAt(now)
                .build();
    }

    private QueueTicketSummaryResponse toSummaryCard(TenantQueueTicket t, LocalDateTime now) {
        if (t == null) {
            return null;
        }
        long waitMins = stateMachine != null
                ? stateMachine.computeWaitingMinutes(t, now)
                : (t.getIssuedAt() != null ? Math.max(0L, Duration.between(t.getIssuedAt(), now).toMinutes()) : 0L);
        long score = stateMachine != null ? stateMachine.computePriorityScore(t, now) : 0L;

        return QueueTicketSummaryResponse.builder()
                .id(t.getId())
                .ticketCode(t.getTicketCode())
                .sequenceNumber(t.getSequenceNumber())
                .serviceId(t.getService() != null ? t.getService().getId() : null)
                .serviceCode(t.getService() != null ? t.getService().getServiceCode() : null)
                .serviceName(t.getService() != null ? t.getService().getServiceName() : null)
                .customerType(t.getCustomerType())
                .customerName(t.getCustomerName())
                .status(t.getStatus())
                .priorityScore(score)
                .waitingMinutes(waitMins)
                .recallCount(t.getRecallCount())
                .version(t.getVersion())
                .autoCompleted(t.getAutoCompleted())
                .counterStaffId(t.getCounterStaff() != null ? t.getCounterStaff().getId() : null)
                .counterStaffName(t.getCounterStaff() != null ? t.getCounterStaff().getFullName() : null)
                .counterLabel(t.getCounterLabel())
                .providerId(t.getProvider() != null ? t.getProvider().getId() : null)
                .providerName(t.getProvider() != null ? t.getProvider().getFullName() : null)
                .issuedAt(t.getIssuedAt())
                .calledAt(t.getCalledAt())
                .servedAt(t.getServedAt())
                .completedAt(t.getCompletedAt())
                .build();
    }

    private String buildAnnouncement(String eventType, TenantQueueTicket ticket) {
        if (ticket == null) {
            return null;
        }
        if ("TICKET_CALLED".equals(eventType) || "TICKET_RECALLED".equals(eventType)) {
            String desk = (ticket.getCounterLabel() != null && !ticket.getCounterLabel().isBlank())
                    ? ticket.getCounterLabel()
                    : (ticket.getCounterStaff() != null ? ticket.getCounterStaff().getFullName() : "tiếp nhận");
            return String.format("Mời quý khách số %s đến %s để làm thủ tục tiếp nhận.", ticket.getTicketCode(), desk);
        }
        return null;
    }

    @Override
    public int sendHeartbeats() {
        int sent = 0;
        for (Map.Entry<String, Set<SseEmitter>> entry : lobbySubscribers.entrySet()) {
            String key = entry.getKey();
            Set<SseEmitter> emitters = entry.getValue();
            if (emitters == null || emitters.isEmpty()) {
                continue;
            }
            for (SseEmitter emitter : emitters) {
                if (sendHeartbeat(emitter, key)) {
                    sent++;
                } else {
                    removeLobbyEmitter(key, emitter);
                }
            }
        }
        return sent;
    }

    private boolean sendHeartbeat(SseEmitter emitter, String tenantKey) {
        try {
            synchronized (emitter) {
                emitter.send(SseEmitter.event().comment("heartbeat"));
            }
            return true;
        } catch (Exception ex) {
            LogContract.debug(log, LogDomain.QUEUE, "lobby_sse_heartbeat_evict")
                    .outcome(LogOutcome.DEGRADED)
                    .tenant(tenantKey)
                    .cause(ex)
                    .emit();
            safeCompleteEmitter(emitter, ex);
            return false;
        }
    }

    private boolean sendEvent(SseEmitter emitter, LobbyEventResponse payload, String eventId, boolean includeReconnectHint) {
        try {
            SseEmitter.SseEventBuilder eventBuilder = SseEmitter.event()
                    .id(eventId)
                    .name("lobby-update")
                    .data(payload);
            if (includeReconnectHint) {
                eventBuilder.reconnectTime(SSE_RECONNECT_TIME_MS);
            }
            synchronized (emitter) {
                emitter.send(eventBuilder);
            }
            return true;
        } catch (Exception ex) {
            LogContract.warn(log, LogDomain.QUEUE, "lobby_sse_push")
                    .outcome(LogOutcome.DEGRADED)
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

    protected SseEmitter createEmitter() {
        return new SseEmitter(SSE_TIMEOUT_MS);
    }

    private String normalizeTenant(String tenantCode) {
        return tenantCode != null ? tenantCode.trim().toLowerCase() : "";
    }
}
