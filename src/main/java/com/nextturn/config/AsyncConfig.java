package com.nextturn.config;

import com.nextturn.infrastructure.logging.LogContract;
import com.nextturn.infrastructure.logging.LogDomain;
import com.nextturn.infrastructure.logging.LogOutcome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * Configuration enabling asynchronous method execution across the application
 * (*kích hoạt xử lý tác vụ bất đồng bộ*).
 * <p>
 * Integrates with Java 25 Virtual Threads configured via {@code spring.threads.virtual.enabled: true},
 * and provides a bounded executor for post-commit queue/SSE side-effect fan-out so staff HTTP
 * latency is strictly isolated from slow subscriber sockets.
 */
@Configuration
@EnableAsync
public class AsyncConfig {

    private static final Logger log = LoggerFactory.getLogger(AsyncConfig.class);

    public static final String QUEUE_EVENT_EXECUTOR = "queueEventExecutor";
    private static final int QUEUE_FANOUT_CORE_POOL_SIZE = 4;
    private static final int QUEUE_FANOUT_MAX_POOL_SIZE = 16;
    private static final int QUEUE_FANOUT_QUEUE_CAPACITY = 500;

    /**
     * Bounded executor for post-commit queue side effects (notifications, analytics rollups,
     * and SSE broadcasts). Drops overflowing tasks with a degraded warning instead of running
     * them on the caller thread so HTTP request latency never depends on subscriber sockets.
     */
    @Bean(name = QUEUE_EVENT_EXECUTOR)
    public Executor queueEventExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(QUEUE_FANOUT_CORE_POOL_SIZE);
        executor.setMaxPoolSize(QUEUE_FANOUT_MAX_POOL_SIZE);
        executor.setQueueCapacity(QUEUE_FANOUT_QUEUE_CAPACITY);
        executor.setThreadNamePrefix("queue-fanout-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(10);
        executor.setRejectedExecutionHandler((task, pool) ->
                LogContract.warn(log, LogDomain.QUEUE, "async_fanout_rejected")
                        .outcome(LogOutcome.DEGRADED)
                        .field("activeThreads", pool.getActiveCount())
                        .field("queueSize", pool.getQueue().size())
                        .emit()
        );
        executor.initialize();
        return executor;
    }
}
