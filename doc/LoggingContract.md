# NextTurn Backend Logging Contract

This specification defines the mandatory logging standard (*quy ước ghi nhật ký*) for all modules in `com.nextturn`. Freestyle log strings and using `log.trace` for runtime errors or degraded fallbacks are **prohibited**.

---

## 1. Core Structured Format

All application logs MUST be emitted through `com.nextturn.infrastructure.logging.LogContract`, producing deterministic key-value lines:

```text
[<DOMAIN>] event=<snake_case_action> outcome=<OUTCOME> [entity=<type:id>] [tenant=<tenantCode>] [durationMs=<ms>] [errorType=<ExceptionClass>] [reason="<sanitized>"] [<extraKey>=<val> ...]
```

### Examples

```text
2026-11-23T00:10:00.123+07:00  WARN [trace=7f3a1c2b tenant=shop-b] c.n.s.c.i.TenantReceptionQrTokenServiceImpl : [CHECKIN] event=qr_cache_write outcome=DEGRADED entity=receptionPoint:rp-01 errorType=RedisConnectionFailureException reason="Unable to connect to Redis"
2026-11-23T00:10:01.005+07:00  INFO [trace=7f3a1c2b tenant=shop-b] c.n.s.q.i.TenantQueueCallingServiceImpl     : [QUEUE] event=ticket_called outcome=SUCCESS entity=ticket:A-001 counter="Quầy 01"
```

---

## 2. MDC Correlation Keys

Every thread executing a request or scheduled sweep binds standard SLF4J MDC fields (automatically rendered by `logging.pattern.level` in `application.yml`):

| MDC Key | Populated By | Default | Cleared By |
| :--- | :--- | :--- | :--- |
| `traceId` | `TenantContextFilter`, `TenantSchedulerSweepSupport` | `system` | Respective `finally` block |
| `tenantCode` | `TenantContextHolder.setTenantCode(...)` | `central` | `TenantContextHolder.clear()` |

> Callers may read or override `X-Trace-Id` / `X-Request-Id` on incoming HTTP requests; `TenantContextFilter` generates an 8-character hex `traceId` if none is supplied.

---

## 3. Severity Level Policy (Strict)

| Level | Permitted Use Cases | Rules & Constraints |
| :--- | :--- | :--- |
| **`ERROR`** | Unhandled exceptions (`GlobalExceptionHandler`), unrecoverable DB/routing faults, failed provisioning rollback, critical startup failures. | Must attach `causeWithStack(ex)` when an unexpected `Throwable` is caught. |
| **`WARN`** | **Non-fatal degraded dependencies** (Valkey cache read/write failure, SSE push failure, email retry failure, audit/telemetry fallback) and **rejected/suspicious client or business paths** (`BaseException`, cross-tenant denial, optimistic lock contention, slot conflict, JWT rejection). | **NEVER** use `TRACE` or `INFO` for caught exceptions or fallbacks. Use `.cause(ex)` (omits noisy stack trace for expected/degraded paths) or `.causeWithStack(ex)` if stack is needed. |
| **`INFO`** | Deterministic domain milestones: ticket transitions (`WAIT -> CALLING -> SERVING -> COMPLETED/NO_SHOW`), appointment booking/cancel/reschedule, reminder dispatches, tenant provisioning/status transitions, scheduler sweep summaries. | Keep concise; omit redundant per-row spam inside tight loops. |
| **`DEBUG`** | Diagnostic parameters, optional bean skips (e.g. `CentralTenantRepository` absent in tests), tenant context binding, cache misses when helpful. | Never log sensitive credentials. |
| **`TRACE`** | Ultra-verbose entry/exit mechanics **with zero exceptions** (e.g. `TenantContextHolder.clear()`, DB catalog switch). | **Strictly forbidden inside any `catch` block.** |

---

## 4. Standardized Domains & Outcomes

* **`LogDomain`**: `AUTH`, `TENANT`, `STAFF`, `SERVICE`, `CALENDAR`, `APPOINTMENT`, `CHECKIN`, `TICKET`, `QUEUE`, `NOTIFICATION`, `SCHEDULER`, `SECURITY`, `SYSTEM`
* **`LogOutcome`**: `STARTED`, `SUCCESS`, `DEGRADED`, `FAILED`, `REJECTED`, `SKIPPED`

---

## 5. PII & Secret Masking Rules

1. **Email addresses**: Always pass through `LogContract.maskEmail(email)` (`ph***@gmail.com`) in normal logs.
2. **Phone numbers**: Always pass through `LogContract.maskPhone(phone)` (`***5678`).
3. **Secrets / Tokens / Passwords**: **NEVER** log raw JWTs, SMTP passwords, HMAC secrets, or Keycloak client secrets. Use `LogContract.maskSecret(...)` if an identifier prefix is needed for debugging.
4. **Local Console Dev Fallback**: Explicit dev-only OTP / activation console logs when SMTP is unconfigured remain restricted to `isConfigured() == false` paths under `EmailServiceImpl`.
