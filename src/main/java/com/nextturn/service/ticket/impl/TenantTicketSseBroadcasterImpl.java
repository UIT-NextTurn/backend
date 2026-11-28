package com.nextturn.service.ticket.impl;

import com.nextturn.dto.ticket.TicketQueueEventResponse;
import com.nextturn.dto.ticket.TicketResponse;
import com.nextturn.enums.TicketStatus;
import com.nextturn.infrastructure.multitenancy.TenantContextHolder;
import com.nextturn.service.ticket.TenantTicketQueryService;
import com.nextturn.service.ticket.TenantTicketSseBroadcaster;
import com.nextturn.infrastructure.logging.LogContract;
import com.nextturn.infrastructure.logging.LogDomain;
import com.nextturn.infrastructure.logging.LogOutcome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

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
     * Maps {@code queueKey (tenantCode:serviceId:workDate)} -> ({@code ticketId} -> list of active {@link SseEmitter}s).
     * Supports multiple concurrent devices/tabs watching the same ticket.
     */
    private final ConcurrentHashMap<String, ConcurrentHashMap<String, CopyOnWriteArrayList<SseEmitter>>> queueSubscribers =
            new ConcurrentHashMap<>();

    private final TenantTicketQueryService ticketQueryService;

    @Autowired
    public TenantTicketSseBroadcasterImpl(@Autowired(required = false) TenantTicketQueryService ticketQueryService) {
        this.ticketQueryService = ticketQueryService;
    }

    @Override
    public SseEmitter subscribe(String tenantCode, String ticketId) {
        SseEmitter emitter = createEmitter();
        if (ticketQueryService == null) {
            emitter.complete();
            return emitter;
        }

        TicketResponse snapshot = ticketQueryService.getTicketDetails(ticketId);
        String resolvedTicketId = snapshot.getId();
        String queueKey = buildQueueKey(tenantCode, snapshot.getServiceId(), snapshot.getWorkDate());

        queueSubscribers.compute(queueKey, (k, existingChannel) -> {
            ConcurrentHashMap<String, CopyOnWriteArrayList<SseEmitter>> channel =
                    existingChannel != null ? existingChannel : new ConcurrentHashMap<>();
            channel.compute(resolvedTicketId, (tk, existingList) -> {
                CopyOnWriteArrayList<SseEmitter> list =
                        existingList != null ? existingList : new CopyOnWriteArrayList<>();
                list.add(emitter);
                return list;
            });
            return channel;
        });

        Runnable cleanup = () -> removeTicketEmitter(queueKey, resolvedTicketId, emitter);
        emitter.onCompletion(cleanup);
        emitter.onTimeout(cleanup);
        emitter.onError(ex -> cleanup.run());

        // Emit initial snapshot immediately upon subscription with explicit retry: hint
        if (!sendEvent(emitter, toEvent("INITIAL_SNAPSHOT", snapshot), true)) {
            cleanup.run();
        }
        return emitter;
    }

    private void removeTicketEmitter(String queueKey, String ticketId, SseEmitter emitter) {
        queueSubscribers.computeIfPresent(queueKey, (k, channel) -> {
            channel.computeIfPresent(ticketId, (tk, list) -> {
                list.remove(emitter);
                return list.isEmpty() ? null : list;
            });
            return channel.isEmpty() ? null : channel;
        });
    }

    @Override
    public void broadcastQueueUpdate(String tenantCode, String serviceId, LocalDate workDate, String eventType) {
        if (ticketQueryService == null || serviceId == null || workDate == null) {
            return;
        }
        String queueKey = buildQueueKey(tenantCode, serviceId, workDate);
        ConcurrentHashMap<String, CopyOnWriteArrayList<SseEmitter>> channel = queueSubscribers.get(queueKey);
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

            for (Map.Entry<String, CopyOnWriteArrayList<SseEmitter>> entry : channel.entrySet()) {
                String ticketId = entry.getKey();
                CopyOnWriteArrayList<SseEmitter> emitters = entry.getValue();
                if (emitters == null || emitters.isEmpty()) {
                    continue;
                }
                TicketResponse updated = snapshots.get(ticketId);
                if (updated == null) {
                    continue;
                }
                TicketQueueEventResponse payload = toEvent(eventType, updated);
                for (SseEmitter emitter : emitters) {
                    if (!sendEvent(emitter, payload, false)) {
                        removeTicketEmitter(queueKey, ticketId, emitter);
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

    @Override
    public int sendHeartbeats() {
        int sent = 0;
        for (Map.Entry<String, ConcurrentHashMap<String, CopyOnWriteArrayList<SseEmitter>>> queueEntry : queueSubscribers.entrySet()) {
            String queueKey = queueEntry.getKey();
            ConcurrentHashMap<String, CopyOnWriteArrayList<SseEmitter>> channel = queueEntry.getValue();
            if (channel == null || channel.isEmpty()) {
                continue;
            }
            for (Map.Entry<String, CopyOnWriteArrayList<SseEmitter>> ticketEntry : channel.entrySet()) {
                String ticketId = ticketEntry.getKey();
                CopyOnWriteArrayList<SseEmitter> emitters = ticketEntry.getValue();
                if (emitters == null || emitters.isEmpty()) {
                    continue;
                }
                for (SseEmitter emitter : emitters) {
                    if (sendHeartbeat(emitter, ticketId)) {
                        sent++;
                    } else {
                        removeTicketEmitter(queueKey, ticketId, emitter);
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

    private boolean sendEvent(SseEmitter emitter, TicketQueueEventResponse payload, boolean includeReconnectHint) {
        try {
            SseEmitter.SseEventBuilder eventBuilder = SseEmitter.event()
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

    private String buildQueueKey(String tenantCode, String serviceId, LocalDate workDate) {
        String cleanTenant = tenantCode != null ? tenantCode.trim().toLowerCase() : "";
        return cleanTenant + ":" + serviceId + ":" + workDate;
    }
}
