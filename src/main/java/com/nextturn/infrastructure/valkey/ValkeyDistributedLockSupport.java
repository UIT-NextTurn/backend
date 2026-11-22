package com.nextturn.infrastructure.valkey;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.List;

/**
 * Shared Valkey distributed mutex lock helpers ensuring owner-safe atomic lock release via Lua.
 */
public final class ValkeyDistributedLockSupport {

    /**
     * Atomic Lua script that deletes {@code KEYS[1]} only when its stored value matches
     * the caller's ownership token {@code ARGV[1]}, preventing a slow worker whose lock TTL
     * already expired from deleting another worker's lock.
     */
    public static final DefaultRedisScript<Long> UNLOCK_SCRIPT = new DefaultRedisScript<>(
            """
            if redis.call('GET', KEYS[1]) == ARGV[1] then
                return redis.call('DEL', KEYS[1])
            else
                return 0
            end
            """,
            Long.class
    );

    private ValkeyDistributedLockSupport() {
        // Utility class
    }

    /**
     * Releases the Valkey distributed lock if still owned by {@code ownerToken}.
     *
     * @return {@code true} if the lock existed with matching ownership and was deleted
     */
    public static boolean releaseLock(StringRedisTemplate redisTemplate, String lockKey, String ownerToken) {
        if (redisTemplate == null
                || lockKey == null || lockKey.isBlank()
                || ownerToken == null || ownerToken.isBlank()) {
            return false;
        }
        Long deleted = redisTemplate.execute(UNLOCK_SCRIPT, List.of(lockKey), ownerToken);
        return Long.valueOf(1L).equals(deleted);
    }
}
