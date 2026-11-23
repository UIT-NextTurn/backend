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
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe SSE connection registry backed by {@link ConcurrentHashMap} for pushing
 * real-time queue progress updates to virtual ticket screens without polling (REQ-TICKET-03).
 */
@Component
public class TenantTicketSseBroadcasterImpl implements TenantTicketSseBroadcaster {

    private static final Logger log = LoggerFactory.getLogger(TenantTicketSseBroadcasterImpl.class);
    private static final long SSE_TIMEOUT_MS = 30L * 60L * 1000L; // 30 minutes

    /**
     * Maps {@code queueKey (tenantCode:serviceId:workDate)} -> ({@code ticketId} -> active {@link SseEmitter}).
     */
    private final ConcurrentHashMap<String, ConcurrentHashMap<String, SseEmitter>> queueSubscribers =
            new ConcurrentHashMap<>();

    private final TenantTicketQueryService ticketQueryService;

    @Autowired
    public TenantTicketSseBroadcasterImpl(@Autowired(required = false) TenantTicketQueryService ticketQueryService) {
        this.ticketQueryService = ticketQueryService;
    }

    @Override
    public SseEmitter subscribe(String tenantCode, String ticketId) {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        if (ticketQueryService == null) {
            emitter.complete();
            return emitter;
        }

        TicketResponse snapshot = ticketQueryService.getTicketDetails(ticketId);
        String resolvedTicketId = snapshot.getId();
        String queueKey = buildQueueKey(tenantCode, snapshot.getServiceId(), snapshot.getWorkDate());

        ConcurrentHashMap<String, SseEmitter> channel = queueSubscribers.computeIfAbsent(
                queueKey, k -> new ConcurrentHashMap<>());
        channel.put(resolvedTicketId, emitter);

        Runnable cleanup = () -> {
            ConcurrentHashMap<String, SseEmitter> map = queueSubscribers.get(queueKey);
            if (map != null) {
                map.remove(resolvedTicketId, emitter);
                if (map.isEmpty()) {
                    queueSubscribers.remove(queueKey, map);
                }
            }
        };
        emitter.onCompletion(cleanup);
        emitter.onTimeout(cleanup);
        emitter.onError(ex -> cleanup.run());

        // Emit initial snapshot immediately upon subscription
        sendEvent(emitter, toEvent("INITIAL_SNAPSHOT", snapshot));
        return emitter;
    }

    @Override
    public void broadcastQueueUpdate(String tenantCode, String serviceId, LocalDate workDate, String eventType) {
        if (ticketQueryService == null || serviceId == null || workDate == null) {
            return;
        }
        String queueKey = buildQueueKey(tenantCode, serviceId, workDate);
        ConcurrentHashMap<String, SseEmitter> channel = queueSubscribers.get(queueKey);
        if (channel == null || channel.isEmpty()) {
            return;
        }

        String previousTenant = TenantContextHolder.getTenantCode();
        try {
            if (tenantCode != null && !tenantCode.isBlank()) {
                TenantContextHolder.setTenantCode(tenantCode);
            }
            for (Map.Entry<String, SseEmitter> entry : channel.entrySet()) {
                String ticketId = entry.getKey();
                SseEmitter emitter = entry.getValue();
                try {
                    TicketResponse updated = ticketQueryService.getTicketDetails(ticketId);
                    sendEvent(emitter, toEvent(eventType, updated));
                } catch (Exception ex) {
                    channel.remove(ticketId, emitter);
                    emitter.complete();
                }
            }
        } finally {
            if (previousTenant != null) {
                TenantContextHolder.setTenantCode(previousTenant);
            } else {
                TenantContextHolder.clear();
            }
        }
    }

    private void sendEvent(SseEmitter emitter, TicketQueueEventResponse payload) {
        try {
            emitter.send(SseEmitter.event()
                    .name("queue-progress")
                    .data(payload));
        } catch (IOException ex) {
            LogContract.warn(log, LogDomain.TICKET, "sse_push")
                    .outcome(LogOutcome.DEGRADED)
                    .entity("ticket", payload.getTicketCode())
                    .cause(ex)
                    .emit();
            emitter.completeWithError(ex);
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
                .timestamp(LocalDateTime.now())
                .build();
    }

    private String buildQueueKey(String tenantCode, String serviceId, LocalDate workDate) {
        String cleanTenant = tenantCode != null ? tenantCode.trim().toLowerCase() : "";
        return cleanTenant + ":" + serviceId + ":" + workDate;
    }
}
