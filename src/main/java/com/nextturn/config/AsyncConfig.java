package com.nextturn.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * Configuration enabling asynchronous method execution across the application
 * (*kích hoạt xử lý tác vụ bất đồng bộ*).
 * <p>
 * Integrates with Java 25 Virtual Threads configured via {@code spring.threads.virtual.enabled: true},
 * ensuring non-blocking execution for long-running I/O operations such as SMTP mail transmission.
 */
@Configuration
@EnableAsync
public class AsyncConfig {
}
