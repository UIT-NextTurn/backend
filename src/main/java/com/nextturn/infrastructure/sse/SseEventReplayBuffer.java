package com.nextturn.infrastructure.sse;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Thread-safe bounded per-channel ring buffer assigning monotonic SSE event IDs
 * ({@code <bootEpoch>-<sequence>}) and supporting {@code Last-Event-ID} replay across brief reconnects.
 *
 * @param <T> SSE payload type
 */
public class SseEventReplayBuffer<T> {

    public static final int DEFAULT_MAX_EVENTS_PER_CHANNEL = 100;

    private final String bootEpoch;
    private final int maxEventsPerChannel;
    private final ConcurrentHashMap<String, ChannelBuffer<T>> channels = new ConcurrentHashMap<>();

    public SseEventReplayBuffer() {
        this(DEFAULT_MAX_EVENTS_PER_CHANNEL);
    }

    public SseEventReplayBuffer(int maxEventsPerChannel) {
        this.bootEpoch = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        this.maxEventsPerChannel = Math.max(1, maxEventsPerChannel);
    }

    public record BufferedEvent<T>(long sequence, String eventId, T payload) {
    }

    /**
     * Records a live event for the given channel, returning its monotonic sequence and SSE {@code id:}.
     */
    public BufferedEvent<T> record(String channelKey, T payload) {
        ChannelBuffer<T> buffer = channels.computeIfAbsent(normalizeKey(channelKey), k -> new ChannelBuffer<>());
        return buffer.append(bootEpoch, payload, maxEventsPerChannel);
    }

    /**
     * Returns the current highest monotonic sequence number for the channel (0 if no events yet).
     */
    public long currentSequence(String channelKey) {
        ChannelBuffer<T> buffer = channels.get(normalizeKey(channelKey));
        return buffer != null ? buffer.currentSequence() : 0L;
    }

    /**
     * Formats the latest known SSE event ID for the channel (e.g. {@code a1b2c3d4-0} if no live events yet).
     */
    public String currentEventId(String channelKey) {
        return bootEpoch + "-" + currentSequence(channelKey);
    }

    /**
     * Parses a client-supplied {@code Last-Event-ID} header and returns its sequence number if it
     * belongs to this server boot epoch, or {@code -1} if missing, malformed, or from another boot.
     */
    public long parseSequenceIfSameEpoch(String lastEventId) {
        if (lastEventId == null || lastEventId.isBlank()) {
            return -1L;
        }
        String trimmed = lastEventId.trim();
        int dash = trimmed.lastIndexOf('-');
        if (dash <= 0 || dash >= trimmed.length() - 1) {
            return -1L;
        }
        String prefix = trimmed.substring(0, dash);
        if (!bootEpoch.equals(prefix)) {
            return -1L;
        }
        try {
            long seq = Long.parseLong(trimmed.substring(dash + 1));
            return seq >= 0L ? seq : -1L;
        } catch (NumberFormatException ex) {
            return -1L;
        }
    }

    /**
     * Returns all buffered events with {@code sequence > afterSequence} in chronological order.
     * Returns an empty list if no newer events exist or if {@code afterSequence < 0}.
     */
    public List<BufferedEvent<T>> eventsAfter(String channelKey, long afterSequence) {
        if (afterSequence < 0L) {
            return List.of();
        }
        ChannelBuffer<T> buffer = channels.get(normalizeKey(channelKey));
        return buffer != null ? buffer.eventsAfter(afterSequence) : List.of();
    }

    /**
     * Convenience helper parsing {@code Last-Event-ID} and returning all missed events in order.
     */
    public List<BufferedEvent<T>> replayAfterHeader(String channelKey, String lastEventId) {
        long seq = parseSequenceIfSameEpoch(lastEventId);
        if (seq < 0L) {
            return List.of();
        }
        return eventsAfter(channelKey, seq);
    }

    private static String normalizeKey(String key) {
        return (key != null && !key.isBlank()) ? key.trim().toLowerCase() : "default";
    }

    private static final class ChannelBuffer<T> {
        private final AtomicLong sequence = new AtomicLong(0L);
        private final Deque<BufferedEvent<T>> ring = new ArrayDeque<>();

        synchronized BufferedEvent<T> append(String bootEpoch, T payload, int maxCapacity) {
            long seq = sequence.incrementAndGet();
            String id = bootEpoch + "-" + seq;
            BufferedEvent<T> event = new BufferedEvent<>(seq, id, payload);
            ring.addLast(event);
            while (ring.size() > maxCapacity) {
                ring.removeFirst();
            }
            return event;
        }

        long currentSequence() {
            return sequence.get();
        }

        synchronized List<BufferedEvent<T>> eventsAfter(long afterSequence) {
            if (ring.isEmpty() || sequence.get() <= afterSequence) {
                return List.of();
            }
            List<BufferedEvent<T>> missed = new ArrayList<>();
            for (BufferedEvent<T> item : ring) {
                if (item.sequence() > afterSequence) {
                    missed.add(item);
                }
            }
            return missed;
        }
    }
}
