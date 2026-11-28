package com.nextturn.service.dashboard.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nextturn.constant.DashboardEndpointConstant;
import com.nextturn.constant.ValkeyKeyConstant;
import com.nextturn.dto.dashboard.DashboardStreamTokenResponse;
import com.nextturn.infrastructure.logging.LogContract;
import com.nextturn.infrastructure.logging.LogDomain;
import com.nextturn.infrastructure.logging.LogOutcome;
import com.nextturn.infrastructure.security.principal.AuthenticatedUser;
import com.nextturn.service.dashboard.DashboardStreamTokenService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Default implementation of {@link DashboardStreamTokenService} backed by Valkey atomic {@code GETDEL}
 * with an in-memory bounded fallback for environments where Valkey is unavailable.
 * <p>
 * Raw Keycloak JWTs are never placed in URLs; instead, a 256-bit opaque single-use token (30s TTL)
 * is minted and bound strictly to {@code (userId, tenantCode, scope, authorities)}.
 */
@Service
public class DashboardStreamTokenServiceImpl implements DashboardStreamTokenService {

    private static final Logger log = LoggerFactory.getLogger(DashboardStreamTokenServiceImpl.class);
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final int TOKEN_BYTE_LENGTH = 32;
    private static final long MIN_TTL_SECONDS = 10L;
    private static final long MAX_TTL_SECONDS = 60L;
    private static final int MAX_FALLBACK_ENTRIES = 10_000;

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final long ttlSeconds;

    private final ConcurrentHashMap<String, StoredStreamToken> fallbackStore = new ConcurrentHashMap<>();

    @Autowired
    public DashboardStreamTokenServiceImpl(
            @Autowired(required = false) StringRedisTemplate redisTemplate,
            @Autowired(required = false) ObjectMapper objectMapper,
            @Value("${nextturn.sse.stream-token.ttl-seconds:30}") long configuredTtlSeconds
    ) {
        this(redisTemplate, objectMapper, Clock.systemDefaultZone(), configuredTtlSeconds);
    }

    public DashboardStreamTokenServiceImpl(
            StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper,
            Clock clock,
            long configuredTtlSeconds
    ) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper().findAndRegisterModules();
        this.clock = clock != null ? clock : Clock.systemDefaultZone();
        this.ttlSeconds = Math.max(MIN_TTL_SECONDS, Math.min(MAX_TTL_SECONDS, configuredTtlSeconds));
    }

    @Override
    public DashboardStreamTokenResponse issueToken(
            AuthenticatedUser caller,
            Collection<? extends GrantedAuthority> authorities,
            StreamScope scope,
            String tenantCode
    ) {
        if (scope == null) {
            throw new IllegalArgumentException("StreamScope must not be null");
        }

        byte[] randomBytes = new byte[TOKEN_BYTE_LENGTH];
        SECURE_RANDOM.nextBytes(randomBytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);

        String userId = caller != null && caller.userId() != null && !caller.userId().isBlank()
                ? caller.userId().trim()
                : "sse-user";
        String email = caller != null && caller.email() != null && !caller.email().isBlank()
                ? caller.email().trim()
                : null;
        String cleanTenant = (scope == StreamScope.TENANT_DASHBOARD && tenantCode != null && !tenantCode.isBlank())
                ? tenantCode.trim().toLowerCase()
                : null;
        List<String> authorityNames = authorities == null
                ? List.of()
                : authorities.stream()
                        .filter(Objects::nonNull)
                        .map(GrantedAuthority::getAuthority)
                        .filter(a -> a != null && !a.isBlank())
                        .distinct()
                        .toList();

        Instant now = Instant.now(clock);
        Instant expiresAtInstant = now.plusSeconds(ttlSeconds);
        StoredStreamToken stored = new StoredStreamToken(
                userId,
                email,
                cleanTenant,
                scope,
                authorityNames,
                expiresAtInstant.toEpochMilli()
        );

        storeToken(token, stored);

        String baseUrl = scope == StreamScope.TENANT_DASHBOARD
                ? DashboardEndpointConstant.TENANT_DASHBOARD_BASE + DashboardEndpointConstant.OVERVIEW_STREAM
                : DashboardEndpointConstant.CENTRAL_DASHBOARD_BASE + DashboardEndpointConstant.OVERVIEW_STREAM;

        return DashboardStreamTokenResponse.builder()
                .streamToken(token)
                .expiresInSeconds(ttlSeconds)
                .expiresAt(LocalDateTime.ofInstant(expiresAtInstant, clock.getZone()))
                .scope(scope.name())
                .streamUrl(baseUrl + "?streamToken=" + token)
                .build();
    }

    @Override
    public Optional<ConsumedStreamPrincipal> consumeToken(String rawToken, StreamScope expectedScope) {
        if (rawToken == null || rawToken.isBlank() || expectedScope == null) {
            return Optional.empty();
        }
        String cleanToken = rawToken.trim();
        StoredStreamToken stored = fetchAndDeleteToken(cleanToken);
        if (stored == null) {
            return Optional.empty();
        }
        long nowMillis = Instant.now(clock).toEpochMilli();
        if (nowMillis > stored.expiresAtEpochMilli()) {
            return Optional.empty();
        }
        if (stored.scope() != expectedScope) {
            return Optional.empty();
        }
        return Optional.of(new ConsumedStreamPrincipal(
                stored.userId(),
                stored.email(),
                stored.tenantCode(),
                stored.scope(),
                stored.authorities() != null ? stored.authorities() : List.of()
        ));
    }

    private void storeToken(String token, StoredStreamToken stored) {
        if (redisTemplate != null) {
            try {
                String json = objectMapper.writeValueAsString(stored);
                redisTemplate.opsForValue().set(
                        ValkeyKeyConstant.sseStreamTokenKey(token),
                        json,
                        Duration.ofSeconds(ttlSeconds)
                );
                return;
            } catch (Exception ex) {
                LogContract.warn(log, LogDomain.SECURITY, "sse_stream_token_store_fallback")
                        .outcome(LogOutcome.DEGRADED)
                        .cause(ex)
                        .emit();
            }
        }
        pruneExpiredFallbackTokens();
        if (fallbackStore.size() < MAX_FALLBACK_ENTRIES) {
            fallbackStore.put(token, stored);
        }
    }

    private StoredStreamToken fetchAndDeleteToken(String token) {
        if (redisTemplate != null) {
            try {
                String json = redisTemplate.opsForValue().getAndDelete(ValkeyKeyConstant.sseStreamTokenKey(token));
                if (json != null && !json.isBlank()) {
                    return objectMapper.readValue(json, StoredStreamToken.class);
                }
            } catch (Exception ex) {
                LogContract.warn(log, LogDomain.SECURITY, "sse_stream_token_consume_fallback")
                        .outcome(LogOutcome.DEGRADED)
                        .cause(ex)
                        .emit();
            }
        }
        return fallbackStore.remove(token);
    }

    private void pruneExpiredFallbackTokens() {
        if (fallbackStore.isEmpty()) {
            return;
        }
        long nowMillis = Instant.now(clock).toEpochMilli();
        fallbackStore.entrySet().removeIf(entry -> entry.getValue().expiresAtEpochMilli() < nowMillis);
    }

    private record StoredStreamToken(
            String userId,
            String email,
            String tenantCode,
            StreamScope scope,
            List<String> authorities,
            long expiresAtEpochMilli
    ) {
    }
}
