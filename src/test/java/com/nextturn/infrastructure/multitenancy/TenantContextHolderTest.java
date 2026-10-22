package com.nextturn.infrastructure.multitenancy;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class TenantContextHolderTest {

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
    }

    @Test
    @DisplayName("Should set, get, and clear tenant code in current thread")
    void shouldSetGetAndClearTenantCode() {
        assertThat(TenantContextHolder.getTenantCode()).isNull();
        assertThat(TenantContextHolder.hasTenant()).isFalse();

        TenantContextHolder.setTenantCode("clinic_alpha");
        assertThat(TenantContextHolder.getTenantCode()).isEqualTo("clinic_alpha");
        assertThat(TenantContextHolder.hasTenant()).isTrue();
        assertThat(TenantContextHolder.getTenantId()).isEqualTo("clinic_alpha");

        TenantContextHolder.clear();
        assertThat(TenantContextHolder.getTenantCode()).isNull();
        assertThat(TenantContextHolder.hasTenant()).isFalse();
    }

    @Test
    @DisplayName("Should isolate tenant context across concurrent threads")
    void shouldIsolateTenantContextAcrossThreads() throws InterruptedException {
        TenantContextHolder.setTenantCode("main_thread_tenant");

        AtomicReference<String> workerTenant = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);

        Thread worker = new Thread(() -> {
            try {
                // Should initially be null in worker thread
                if (TenantContextHolder.getTenantCode() == null) {
                    TenantContextHolder.setTenantCode("worker_thread_tenant");
                    workerTenant.set(TenantContextHolder.getTenantCode());
                }
            } finally {
                TenantContextHolder.clear();
                latch.countDown();
            }
        });

        worker.start();
        boolean completed = latch.await(2, TimeUnit.SECONDS);

        assertThat(completed).isTrue();
        assertThat(workerTenant.get()).isEqualTo("worker_thread_tenant");
        // Main thread must still retain its own tenant
        assertThat(TenantContextHolder.getTenantCode()).isEqualTo("main_thread_tenant");
    }

    @Test
    @DisplayName("Setting null or blank tenant should clear the context")
    void shouldClearWhenSettingNullOrBlank() {
        TenantContextHolder.setTenantCode("temp_tenant");
        assertThat(TenantContextHolder.hasTenant()).isTrue();

        TenantContextHolder.setTenantCode("   ");
        assertThat(TenantContextHolder.hasTenant()).isFalse();
        assertThat(TenantContextHolder.getTenantCode()).isNull();

        TenantContextHolder.setTenantCode("another_tenant");
        TenantContextHolder.setTenantCode(null);
        assertThat(TenantContextHolder.hasTenant()).isFalse();
        assertThat(TenantContextHolder.getTenantCode()).isNull();
    }
}
