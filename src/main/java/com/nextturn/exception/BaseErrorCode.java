package com.nextturn.exception;

import org.springframework.http.HttpStatus;

/**
 * Base contract for application-specific error codes.
 * Ensures every error has a distinct alphanumeric code, default message, and mapped HTTP status.
 */
public interface BaseErrorCode {

    String code();

    String message();

    HttpStatus httpStatus();
}
