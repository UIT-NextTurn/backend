package com.nextturn.infrastructure.logging;

import org.slf4j.Logger;
import org.slf4j.MDC;
import org.slf4j.event.Level;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Central structured logging contract (*hợp đồng ghi nhật ký có cấu trúc*) for NextTurn backend services.
 * <p>
 * Eliminates freestyle log strings and enforces consistent domain tags, event names, outcomes,
 * exception categorization, PII masking, and MDC correlation keys ({@code traceId}, {@code tenantCode}).
 */
public final class LogContract {

    public static final String MDC_TRACE_ID = "traceId";
    public static final String MDC_TENANT_CODE = "tenantCode";

    private LogContract() {
        // Utility class
    }

    public static Entry info(Logger logger, LogDomain domain, String event) {
        return new Entry(logger, Level.INFO, domain, event, LogOutcome.SUCCESS);
    }

    public static Entry warn(Logger logger, LogDomain domain, String event) {
        return new Entry(logger, Level.WARN, domain, event, LogOutcome.DEGRADED);
    }

    public static Entry error(Logger logger, LogDomain domain, String event) {
        return new Entry(logger, Level.ERROR, domain, event, LogOutcome.FAILED);
    }

    public static Entry debug(Logger logger, LogDomain domain, String event) {
        return new Entry(logger, Level.DEBUG, domain, event, LogOutcome.SUCCESS);
    }

    public static Entry trace(Logger logger, LogDomain domain, String event) {
        return new Entry(logger, Level.TRACE, domain, event, LogOutcome.SUCCESS);
    }

    // -------------------------------------------------------------------------
    // MDC Correlation Management
    // -------------------------------------------------------------------------

    /**
     * Binds a trace ID to SLF4J {@link MDC} (generates a short 8-char ID if blank).
     */
    public static String bindTraceId(String traceId) {
        String effective = (traceId != null && !traceId.isBlank())
                ? traceId.trim()
                : UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        MDC.put(MDC_TRACE_ID, effective);
        return effective;
    }

    public static String bindNewTraceId() {
        return bindTraceId(null);
    }

    public static void clearTraceId() {
        MDC.remove(MDC_TRACE_ID);
    }

    public static void bindTenantCode(String tenantCode) {
        if (tenantCode == null || tenantCode.isBlank()) {
            MDC.remove(MDC_TENANT_CODE);
        } else {
            MDC.put(MDC_TENANT_CODE, tenantCode.trim());
        }
    }

    public static void clearTenantCode() {
        MDC.remove(MDC_TENANT_CODE);
    }

    // -------------------------------------------------------------------------
    // PII & Secret Masking Helpers
    // -------------------------------------------------------------------------

    public static String maskEmail(String email) {
        if (email == null || email.isBlank()) {
            return "<none>";
        }
        String clean = email.trim();
        int at = clean.indexOf('@');
        if (at <= 0) {
            return maskSecret(clean);
        }
        String local = clean.substring(0, at);
        String domain = clean.substring(at);
        String prefix = local.length() <= 2 ? local.substring(0, 1) : local.substring(0, 2);
        return prefix + "***" + domain;
    }

    public static String maskPhone(String phone) {
        if (phone == null || phone.isBlank()) {
            return "<none>";
        }
        String clean = phone.trim();
        if (clean.length() <= 4) {
            return "****";
        }
        return "***" + clean.substring(clean.length() - 4);
    }

    public static String maskSecret(String secret) {
        if (secret == null || secret.isBlank()) {
            return "<empty>";
        }
        String clean = secret.trim();
        if (clean.length() <= 4) {
            return "****";
        }
        return clean.substring(0, 4) + "***";
    }

    static String sanitizeValue(Object raw) {
        if (raw == null) {
            return "null";
        }
        String text = String.valueOf(raw)
                .replace('\r', ' ')
                .replace('\n', ' ')
                .trim();
        if (text.isEmpty()) {
            return "\"\"";
        }
        boolean needsQuotes = text.contains(" ") || text.contains("=") || text.contains("\"");
        if (needsQuotes) {
            return "\"" + text.replace("\"", "'") + "\"";
        }
        return text;
    }

    /**
     * Fluent log builder enforcing zero allocation when the target SLF4J level is disabled.
     */
    public static final class Entry {

        private final Logger logger;
        private final Level level;
        private final LogDomain domain;
        private final String event;
        private LogOutcome outcome;
        private String entity;
        private String tenant;
        private Long durationMs;
        private String errorType;
        private String reason;
        private Throwable stackThrowable;
        private final Map<String, Object> fields = new LinkedHashMap<>();

        private Entry(Logger logger, Level level, LogDomain domain, String event, LogOutcome defaultOutcome) {
            this.logger = logger;
            this.level = level;
            this.domain = domain != null ? domain : LogDomain.SYSTEM;
            this.event = (event != null && !event.isBlank()) ? event.trim() : "unspecified";
            this.outcome = defaultOutcome;
        }

        public Entry outcome(LogOutcome outcome) {
            if (outcome != null) {
                this.outcome = outcome;
            }
            return this;
        }

        public Entry entity(String type, Object id) {
            if (type != null && !type.isBlank()) {
                this.entity = id != null ? type.trim() + ":" + id : type.trim();
            }
            return this;
        }

        public Entry tenant(String tenantCode) {
            if (tenantCode != null && !tenantCode.isBlank()) {
                this.tenant = tenantCode.trim();
            }
            return this;
        }

        public Entry durationMs(long durationMs) {
            this.durationMs = durationMs;
            return this;
        }

        public Entry reason(String reason) {
            if (reason != null && !reason.isBlank()) {
                this.reason = reason;
            }
            return this;
        }

        public Entry email(String rawEmail) {
            return field("recipient", maskEmail(rawEmail));
        }

        public Entry phone(String rawPhone) {
            return field("phone", maskPhone(rawPhone));
        }

        /**
         * Attaches exception metadata ({@code errorType} + {@code reason}) without stack trace.
         * Preferred for {@code WARN} degraded/rejected paths.
         */
        public Entry cause(Throwable ex) {
            if (ex != null) {
                this.errorType = ex.getClass().getSimpleName();
                this.reason = ex.getMessage() != null ? ex.getMessage() : ex.toString();
            }
            return this;
        }

        /**
         * Attaches exception metadata AND forwards {@link Throwable} to SLF4J so the full stack trace is logged.
         * Required for {@code ERROR} severity unexpected failures.
         */
        public Entry causeWithStack(Throwable ex) {
            cause(ex);
            this.stackThrowable = ex;
            return this;
        }

        public Entry field(String key, Object value) {
            if (key != null && !key.isBlank() && value != null) {
                this.fields.put(key.trim(), value);
            }
            return this;
        }

        public String formatMessage() {
            StringBuilder sb = new StringBuilder(128);
            sb.append('[').append(domain.name()).append("] ");
            sb.append("event=").append(event);
            sb.append(" outcome=").append(outcome.name());
            if (tenant != null) {
                sb.append(" tenant=").append(sanitizeValue(tenant));
            }
            if (entity != null) {
                sb.append(" entity=").append(sanitizeValue(entity));
            }
            if (durationMs != null) {
                sb.append(" durationMs=").append(durationMs);
            }
            if (errorType != null) {
                sb.append(" errorType=").append(sanitizeValue(errorType));
            }
            for (Map.Entry<String, Object> entry : fields.entrySet()) {
                sb.append(' ').append(entry.getKey()).append('=').append(sanitizeValue(entry.getValue()));
            }
            if (reason != null) {
                sb.append(" reason=").append(sanitizeValue(reason));
            }
            return sb.toString();
        }

        public void emit() {
            if (logger == null || !isLevelEnabled()) {
                return;
            }
            String message = formatMessage();
            switch (level) {
                case ERROR -> {
                    if (stackThrowable != null) {
                        logger.error(message, stackThrowable);
                    } else {
                        logger.error(message);
                    }
                }
                case WARN -> {
                    if (stackThrowable != null) {
                        logger.warn(message, stackThrowable);
                    } else {
                        logger.warn(message);
                    }
                }
                case INFO -> logger.info(message);
                case DEBUG -> logger.debug(message);
                case TRACE -> logger.trace(message);
            }
        }

        private boolean isLevelEnabled() {
            return switch (level) {
                case ERROR -> logger.isErrorEnabled();
                case WARN -> logger.isWarnEnabled();
                case INFO -> logger.isInfoEnabled();
                case DEBUG -> logger.isDebugEnabled();
                case TRACE -> logger.isTraceEnabled();
            };
        }
    }
}
