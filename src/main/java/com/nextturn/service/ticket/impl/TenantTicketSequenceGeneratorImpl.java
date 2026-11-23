package com.nextturn.service.ticket.impl;

import com.nextturn.constant.ValkeyKeyConstant;
import com.nextturn.entity.TenantService;
import com.nextturn.exception.ticket.TicketErrorCode;
import com.nextturn.exception.ticket.TicketException;
import com.nextturn.infrastructure.valkey.ValkeyDistributedLockSupport;
import com.nextturn.repository.ticket.TenantQueueTicketRepository;
import com.nextturn.service.ticket.TenantTicketSequenceGenerator;
import com.nextturn.infrastructure.logging.LogContract;
import com.nextturn.infrastructure.logging.LogDomain;
import com.nextturn.infrastructure.logging.LogOutcome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Default implementation of {@link TenantTicketSequenceGenerator} using:
 * <ul>
 *     <li>Valkey distributed mutex lock ({@code SET NX PX 5000ms}) + safe Lua unlock script</li>
 *     <li>Atomic Valkey Lua script combining MySQL crash-recovery seed, {@code INCR}, and 26-hour TTL
 *         (ensuring automatic reset to {@code 001} at {@code 00:00:00} every new operating day)</li>
 *     <li>Dynamic 3+ digit formatting ({@code A-001} .. {@code A-999}, expanding seamlessly to {@code A-1000})</li>
 * </ul>
 */
@Component
public class TenantTicketSequenceGeneratorImpl implements TenantTicketSequenceGenerator {

    private static final Logger log = LoggerFactory.getLogger(TenantTicketSequenceGeneratorImpl.class);
    private static final Duration LOCK_TTL = Duration.ofSeconds(5);
    private static final long SEQUENCE_TTL_SECONDS = 26L * 3600L; // 26h covers full operating day + midnight buffer
    private static final Pattern NUMERIC_SUFFIX_PATTERN = Pattern.compile("(\\d+)$");

    /**
     * Atomic Lua script:
     * 1. Reads current Valkey counter for KEYS[1].
     * 2. If missing or behind MySQL max sequence ARGV[1] (e.g. after Valkey restart), seeds to ARGV[1].
     * 3. Atomically executes INCR to produce the next strictly monotonic sequence number.
     * 4. Ensures TTL ARGV[2] is attached so daily keys expire cleanly after midnight.
     */
    private static final DefaultRedisScript<Long> SEQUENCE_INCR_SCRIPT = new DefaultRedisScript<>(
            """
            local current = tonumber(redis.call('GET', KEYS[1]))
            local dbMax = tonumber(ARGV[1])
            if current == nil or current < dbMax then
                redis.call('SET', KEYS[1], dbMax)
            end
            local nextSeq = redis.call('INCR', KEYS[1])
            if redis.call('TTL', KEYS[1]) < 0 then
                redis.call('EXPIRE', KEYS[1], tonumber(ARGV[2]))
            end
            return nextSeq
            """,
            Long.class
    );

    private final TenantQueueTicketRepository ticketRepository;
    private final StringRedisTemplate redisTemplate;

    @Autowired
    public TenantTicketSequenceGeneratorImpl(
            TenantQueueTicketRepository ticketRepository,
            @Autowired(required = false) StringRedisTemplate redisTemplate
    ) {
        this.ticketRepository = ticketRepository;
        this.redisTemplate = redisTemplate;
    }

    @Override
    public String acquireTicketLock(String tenantCode, String serviceId, LocalDate workDate) {
        if (redisTemplate == null) {
            return null;
        }
        String lockKey = ValkeyKeyConstant.ticketLockKey(tenantCode, serviceId, workDate);
        String ownerToken = UUID.randomUUID().toString();

        // Retry briefly (up to ~500ms) for concurrent requests to serialize cleanly behind the distributed mutex
        for (int attempt = 0; attempt < 10; attempt++) {
            try {
                Boolean acquired = redisTemplate.opsForValue().setIfAbsent(lockKey, ownerToken, LOCK_TTL);
                if (Boolean.TRUE.equals(acquired)) {
                    return ownerToken;
                }
            } catch (Exception ex) {
                LogContract.warn(log, LogDomain.TICKET, "sequence_lock_acquire")
                        .outcome(LogOutcome.DEGRADED)
                        .field("lockKey", lockKey)
                        .cause(ex)
                        .emit();
                return null;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new TicketException(TicketErrorCode.TICKET_LOCK_CONFLICT);
            }
        }

        LogContract.warn(log, LogDomain.TICKET, "sequence_lock_timeout")
                .outcome(LogOutcome.REJECTED)
                .field("lockKey", lockKey)
                .emit();
        throw new TicketException(TicketErrorCode.TICKET_LOCK_CONFLICT);
    }

    @Override
    public void releaseTicketLock(String tenantCode, String serviceId, LocalDate workDate, String ownerToken) {
        if (redisTemplate == null || ownerToken == null || ownerToken.isBlank()) {
            return;
        }
        String lockKey = ValkeyKeyConstant.ticketLockKey(tenantCode, serviceId, workDate);
        try {
            ValkeyDistributedLockSupport.releaseLock(redisTemplate, lockKey, ownerToken);
        } catch (Exception ex) {
            LogContract.warn(log, LogDomain.TICKET, "sequence_lock_release")
                    .outcome(LogOutcome.DEGRADED)
                    .field("lockKey", lockKey)
                    .cause(ex)
                    .emit();
        }
    }

    @Override
    public GeneratedTicketNumber nextTicketNumber(String tenantCode, TenantService service, LocalDate workDate) {
        String serviceId = service.getId();
        Integer dbMaxObj = ticketRepository != null
                ? ticketRepository.findMaxSequenceNumberByServiceAndWorkDate(serviceId, workDate)
                : 0;
        int dbMax = dbMaxObj != null ? dbMaxObj : 0;

        int nextSeq;
        if (redisTemplate != null) {
            String seqKey = ValkeyKeyConstant.ticketSequenceKey(tenantCode, serviceId, workDate);
            try {
                Long luaResult = redisTemplate.execute(
                        SEQUENCE_INCR_SCRIPT,
                        List.of(seqKey),
                        String.valueOf(dbMax),
                        String.valueOf(SEQUENCE_TTL_SECONDS)
                );
                nextSeq = (luaResult != null && luaResult > 0) ? luaResult.intValue() : (dbMax + 1);
            } catch (Exception ex) {
                LogContract.warn(log, LogDomain.TICKET, "sequence_lua_next")
                        .outcome(LogOutcome.DEGRADED)
                        .field("seqKey", seqKey)
                        .cause(ex)
                        .emit();
                nextSeq = dbMax + 1;
            }
        } else {
            nextSeq = dbMax + 1;
        }

        String prefix = resolveServicePrefix(service);
        String ticketCode = formatTicketCode(prefix, nextSeq);
        return new GeneratedTicketNumber(nextSeq, ticketCode);
    }

    @Override
    public String resolveServicePrefix(TenantService service) {
        if (service == null || service.getServiceCode() == null || service.getServiceCode().isBlank()) {
            return "A";
        }
        Matcher matcher = NUMERIC_SUFFIX_PATTERN.matcher(service.getServiceCode().trim());
        if (matcher.find()) {
            try {
                int num = Integer.parseInt(matcher.group(1));
                if (num >= 1) {
                    return toAlphabeticalPrefix(num);
                }
            } catch (NumberFormatException ignored) {
            }
        }
        // Fallback: first uppercase ASCII letter from serviceCode or "A"
        String alphaOnly = service.getServiceCode().replaceAll("[^A-Za-z]", "").toUpperCase();
        return !alphaOnly.isEmpty() ? alphaOnly.substring(0, 1) : "A";
    }

    @Override
    public String formatTicketCode(String prefix, int sequenceNumber) {
        String safePrefix = (prefix != null && !prefix.isBlank()) ? prefix.trim().toUpperCase() : "A";
        int safeSeq = Math.max(1, sequenceNumber);
        // %03d keeps 3 zero-padded digits for 1..999 (e.g. A-001) and naturally expands to 4+ digits for >=1000 (e.g. A-1000)
        return String.format("%s-%03d", safePrefix, safeSeq);
    }

    /**
     * Converts a 1-based index into spreadsheet-style uppercase letters:
     * 1 -> A, 2 -> B, ..., 26 -> Z, 27 -> AA.
     */
    private String toAlphabeticalPrefix(int index) {
        StringBuilder sb = new StringBuilder();
        int current = index;
        while (current > 0) {
            current--;
            sb.insert(0, (char) ('A' + (current % 26)));
            current /= 26;
        }
        return sb.toString();
    }
}
