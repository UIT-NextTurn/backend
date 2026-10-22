package com.nextturn.exception.queue;

import com.nextturn.exception.BaseErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;
import org.springframework.http.HttpStatus;

/**
 * Queue service line and virtual waiting room error codes.
 */
@Getter
@Accessors(fluent = true)
@RequiredArgsConstructor
public enum QueueErrorCode implements BaseErrorCode {

    QUEUE_NOT_FOUND("QUEUE_001", "Queue service line not found", HttpStatus.NOT_FOUND),
    QUEUE_CLOSED("QUEUE_002", "Queue is currently closed or outside operational business hours", HttpStatus.BAD_REQUEST),
    QUEUE_PAUSED("QUEUE_003", "Queue intake is temporarily paused by counter staff", HttpStatus.BAD_REQUEST),
    QUEUE_CAPACITY_FULL("QUEUE_004", "Queue maximum capacity has been reached for today", HttpStatus.TOO_MANY_REQUESTS),
    DUPLICATE_QUEUE_NAME("QUEUE_005", "A queue service line with this name already exists in branch", HttpStatus.CONFLICT),
    ACTIVE_TICKET_IN_QUEUE("QUEUE_006", "Customer already holds an active ticket in this queue", HttpStatus.CONFLICT),
    ESTIMATED_WAIT_UNAVAILABLE("QUEUE_007", "Insufficient telemetry to calculate estimated wait time", HttpStatus.SERVICE_UNAVAILABLE);

    private final String code;
    private final String message;
    private final HttpStatus httpStatus;
}
