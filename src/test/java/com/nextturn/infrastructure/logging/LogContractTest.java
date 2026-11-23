package com.nextturn.infrastructure.logging;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.MDC;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests verifying {@link LogContract} formatting, severity enforcement,
 * PII masking, and MDC correlation cleanup.
 */
class LogContractTest {

    @AfterEach
    void cleanupMdc() {
        LogContract.clearTraceId();
        LogContract.clearTenantCode();
    }

    @Test
    @DisplayName("Formats deterministic structured key-value contract with cause details")
    void formatMessage_FollowsStructuredContract() {
        Logger logger = mock(Logger.class);
        when(logger.isWarnEnabled()).thenReturn(true);

        LogContract.warn(logger, LogDomain.CHECKIN, "qr_cache_write")
                .outcome(LogOutcome.DEGRADED)
                .tenant("shop-b")
                .entity("receptionPoint", "rp-01")
                .email("phanhuynhhung0707@gmail.com")
                .phone("0912345678")
                .cause(new IllegalStateException("Valkey connection\nrefused"))
                .emit();

        verify(logger).warn("[CHECKIN] event=qr_cache_write outcome=DEGRADED tenant=shop-b entity=receptionPoint:rp-01 errorType=IllegalStateException recipient=ph***@gmail.com phone=***5678 reason=\"Valkey connection refused\"");
    }

    @Test
    @DisplayName("Passes Throwable stack trace when causeWithStack is used on ERROR logs")
    void errorWithStack_ForwardsThrowable() {
        Logger logger = mock(Logger.class);
        when(logger.isErrorEnabled()).thenReturn(true);
        RuntimeException boom = new RuntimeException("DB outage");

        LogContract.error(logger, LogDomain.SYSTEM, "unhandled_exception")
                .causeWithStack(boom)
                .emit();

        verify(logger).error(
                eq("[SYSTEM] event=unhandled_exception outcome=FAILED errorType=RuntimeException reason=\"DB outage\""),
                eq(boom)
        );
    }

    @Test
    @DisplayName("Skips logging completely when level is disabled")
    void disabledLevel_NoEmission() {
        Logger logger = mock(Logger.class);
        when(logger.isDebugEnabled()).thenReturn(false);

        LogContract.debug(logger, LogDomain.TENANT, "context_bound")
                .field("uri", "/api/test")
                .emit();

        verify(logger, never()).debug(anyString());
    }

    @Test
    @DisplayName("Masks email, phone, and secrets safely")
    void masksPiiAndSecrets() {
        assertThat(LogContract.maskEmail("phanhuynhhung0707@gmail.com")).isEqualTo("ph***@gmail.com");
        assertThat(LogContract.maskEmail("a@b.vn")).isEqualTo("a***@b.vn");
        assertThat(LogContract.maskPhone("0909123456")).isEqualTo("***3456");
        assertThat(LogContract.maskSecret("secret-token-xyz")).isEqualTo("secr***");
    }

    @Test
    @DisplayName("Manages MDC traceId and tenantCode lifecycle cleanly")
    void managesMdcKeys() {
        String id = LogContract.bindNewTraceId();
        assertThat(id).hasSize(8);
        assertThat(MDC.get(LogContract.MDC_TRACE_ID)).isEqualTo(id);

        LogContract.bindTenantCode("clinic-alpha");
        assertThat(MDC.get(LogContract.MDC_TENANT_CODE)).isEqualTo("clinic-alpha");

        LogContract.clearTraceId();
        LogContract.clearTenantCode();
        assertThat(MDC.get(LogContract.MDC_TRACE_ID)).isNull();
        assertThat(MDC.get(LogContract.MDC_TENANT_CODE)).isNull();
    }
}
