package com.nextturn.infrastructure.sse;

import com.nextturn.exception.AppException;
import com.nextturn.exception.system.SystemErrorCode;
import com.nextturn.infrastructure.logging.LogContract;
import com.nextturn.infrastructure.logging.LogDomain;
import com.nextturn.infrastructure.logging.LogOutcome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Lock-free per-tenant and global SSE connection cap preventing any single tenant from exhausting
 * the servlet container connection limit across Lobby, Ticket, and Dashboard streams.
 */
@Component
public class TenantSseConnectionLimiter {

    private static final Logger log = LoggerFactory.getLogger(TenantSseConnectionLimiter.class);

    public static final int DEFAULT_MAX_PER_TENANT = 1000;
    public static final int DEFAULT_MAX_PLATFORM = 200;
    public static final int DEFAULT_MAX_TOTAL = 6000;

    private final int maxSubscribersPerTenant;
    private final int maxPlatformSubscribers;
    private final int maxTotalSubscribers;

    private final ConcurrentHashMap<String, AtomicInteger> activePerTenant = new ConcurrentHashMap<>();
    private final AtomicInteger activePlatform = new AtomicInteger(0);
    private final AtomicInteger totalActive = new AtomicInteger(0);

    @Autowired
    public TenantSseConnectionLimiter(
            @Value("${nextturn.sse.limits.max-subscribers-per-tenant:1000}") int maxSubscribersPerTenant,
            @Value("${nextturn.sse.limits.max-platform-subscribers:200}") int maxPlatformSubscribers,
            @Value("${nextturn.sse.limits.max-total-subscribers:6000}") int maxTotalSubscribers
    ) {
        this.maxSubscribersPerTenant = Math.max(1, maxSubscribersPerTenant);
        this.maxPlatformSubscribers = Math.max(1, maxPlatformSubscribers);
        this.maxTotalSubscribers = Math.max(1, maxTotalSubscribers);
    }

    public TenantSseConnectionLimiter() {
        this(DEFAULT_MAX_PER_TENANT, DEFAULT_MAX_PLATFORM, DEFAULT_MAX_TOTAL);
    }

    /**
     * Atomically reserves one SSE connection slot for the given tenant and global pool.
     * Throws HTTP 429 ({@link SystemErrorCode#RATE_LIMIT_EXCEEDED}) if either cap is reached.
     */
    public void acquireTenantSlot(String tenantCode) {
        String key = normalizeTenant(tenantCode);
        if (!tryIncrementBounded(totalActive, maxTotalSubscribers)) {
            LogContract.warn(log, LogDomain.SYSTEM, "sse_global_limit_exceeded")
                    .outcome(LogOutcome.REJECTED)
                    .tenant(key)
                    .field("maxTotal", maxTotalSubscribers)
                    .emit();
            throw new AppException(
                    SystemErrorCode.RATE_LIMIT_EXCEEDED,
                    "Hệ thống đã đạt giới hạn kết nối SSE tối đa. Vui lòng thử lại sau."
            );
        }

        AtomicInteger tenantCounter = activePerTenant.computeIfAbsent(key, k -> new AtomicInteger(0));
        if (!tryIncrementBounded(tenantCounter, maxSubscribersPerTenant)) {
            decrementFloorZero(totalActive);
            LogContract.warn(log, LogDomain.SYSTEM, "sse_tenant_limit_exceeded")
                    .outcome(LogOutcome.REJECTED)
                    .tenant(key)
                    .field("maxPerTenant", maxSubscribersPerTenant)
                    .emit();
            throw new AppException(
                    SystemErrorCode.RATE_LIMIT_EXCEEDED,
                    "Tổ chức đã đạt giới hạn kết nối SSE đồng thời (" + maxSubscribersPerTenant + "). Vui lòng thử lại sau."
            );
        }
    }

    /**
     * Releases one SSE connection slot previously acquired for the given tenant.
     * Callers must ensure this is invoked at most once per acquired emitter.
     */
    public void releaseTenantSlot(String tenantCode) {
        String key = normalizeTenant(tenantCode);
        decrementFloorZero(totalActive);
        activePerTenant.computeIfPresent(key, (k, counter) -> {
            int remaining = decrementFloorZero(counter);
            return remaining <= 0 ? null : counter;
        });
    }

    /**
     * Atomically reserves one SSE connection slot for a Platform Superadmin dashboard stream.
     */
    public void acquirePlatformSlot() {
        if (!tryIncrementBounded(totalActive, maxTotalSubscribers)) {
            LogContract.warn(log, LogDomain.SYSTEM, "sse_global_limit_exceeded")
                    .outcome(LogOutcome.REJECTED)
                    .field("scope", "platform")
                    .field("maxTotal", maxTotalSubscribers)
                    .emit();
            throw new AppException(
                    SystemErrorCode.RATE_LIMIT_EXCEEDED,
                    "Hệ thống đã đạt giới hạn kết nối SSE tối đa. Vui lòng thử lại sau."
            );
        }
        if (!tryIncrementBounded(activePlatform, maxPlatformSubscribers)) {
            decrementFloorZero(totalActive);
            LogContract.warn(log, LogDomain.SYSTEM, "sse_platform_limit_exceeded")
                    .outcome(LogOutcome.REJECTED)
                    .field("maxPlatform", maxPlatformSubscribers)
                    .emit();
            throw new AppException(
                    SystemErrorCode.RATE_LIMIT_EXCEEDED,
                    "Kênh giám sát nền tảng đã đạt giới hạn kết nối SSE đồng thời (" + maxPlatformSubscribers + ")."
            );
        }
    }

    /**
     * Releases one SSE connection slot previously acquired for a Platform dashboard emitter.
     */
    public void releasePlatformSlot() {
        decrementFloorZero(totalActive);
        decrementFloorZero(activePlatform);
    }

    public int getActiveCountForTenant(String tenantCode) {
        AtomicInteger counter = activePerTenant.get(normalizeTenant(tenantCode));
        return counter != null ? counter.get() : 0;
    }

    public int getActivePlatformCount() {
        return activePlatform.get();
    }

    public int getTotalActiveCount() {
        return totalActive.get();
    }

    private static boolean tryIncrementBounded(AtomicInteger counter, int limit) {
        while (true) {
            int current = counter.get();
            if (current >= limit) {
                return false;
            }
            if (counter.compareAndSet(current, current + 1)) {
                return true;
            }
        }
    }

    private static int decrementFloorZero(AtomicInteger counter) {
        while (true) {
            int current = counter.get();
            if (current <= 0) {
                return 0;
            }
            int next = current - 1;
            if (counter.compareAndSet(current, next)) {
                return next;
            }
        }
    }

    private static String normalizeTenant(String tenantCode) {
        return (tenantCode != null && !tenantCode.isBlank()) ? tenantCode.trim().toLowerCase() : "default";
    }
}
