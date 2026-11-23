package com.nextturn.infrastructure.logging;

/**
 * Standardized outcome classifications enforced by {@link LogContract}.
 */
public enum LogOutcome {
    STARTED,
    SUCCESS,
    DEGRADED,
    FAILED,
    REJECTED,
    SKIPPED
}
