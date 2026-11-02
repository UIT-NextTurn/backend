package com.nextturn.exception.notification;

import com.nextturn.exception.BaseErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;
import org.springframework.http.HttpStatus;

/**
 * Multi-channel notification delivery error codes.
 */
@Getter
@Accessors(fluent = true)
@RequiredArgsConstructor
public enum NotificationErrorCode implements BaseErrorCode {

    SMS_GATEWAY_ERROR("NOTIF_001", "SMS gateway dispatch failed", HttpStatus.BAD_GATEWAY),
    EMAIL_GATEWAY_ERROR("NOTIF_002", "Email delivery service failed", HttpStatus.BAD_GATEWAY),
    WEBSOCKET_BROADCAST_ERROR("NOTIF_003", "WebSocket message broadcast failed", HttpStatus.INTERNAL_SERVER_ERROR),
    TELEGRAM_BOT_ERROR("NOTIF_004", "Telegram alert delivery failed", HttpStatus.BAD_GATEWAY),
    NOTIFICATION_TEMPLATE_NOT_FOUND("NOTIF_005", "Notification template not configured for tenant", HttpStatus.NOT_FOUND);

    private final String code;
    private final String message;
    private final HttpStatus httpStatus;
}
