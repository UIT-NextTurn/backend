package com.nextturn.exception.counter;

import com.nextturn.exception.BaseErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;
import org.springframework.http.HttpStatus;

/**
 * Service desk counter operator error codes.
 */
@Getter
@Accessors(fluent = true)
@RequiredArgsConstructor
public enum CounterErrorCode implements BaseErrorCode {

    COUNTER_NOT_FOUND("COUNTER_001", "Service desk counter not found", HttpStatus.NOT_FOUND),
    COUNTER_ALREADY_EXISTS("COUNTER_002", "Counter identifier already exists in this branch", HttpStatus.CONFLICT),
    COUNTER_OFFLINE("COUNTER_003", "Counter is currently closed or operator is offline", HttpStatus.BAD_REQUEST),
    COUNTER_BUSY("COUNTER_004", "Counter is currently serving another active ticket", HttpStatus.CONFLICT),
    COUNTER_NOT_ASSIGNED("COUNTER_005", "Staff member is not assigned to this counter", HttpStatus.FORBIDDEN),
    COUNTER_SERVICE_MISMATCH("COUNTER_006", "Counter is not configured to handle tickets for this queue service", HttpStatus.BAD_REQUEST);

    private final String code;
    private final String message;
    private final HttpStatus httpStatus;
}
