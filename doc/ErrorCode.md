# Error Codes

## System (`SystemErrorCode`)

| Enum Name | Code | Message |
|---|---|---|
| INTERNAL_ERROR | SYS_001 | An unexpected error occurred |
| VALIDATION_ERROR | SYS_002 | Validation failed |
| RESOURCE_NOT_FOUND | SYS_003 | Resource not found |
| METHOD_NOT_ALLOWED | SYS_004 | HTTP method not supported |
| DATABASE_ERROR | SYS_005 | Database error occurred |
| INVALID_PARAMETER | SYS_006 | Invalid parameter provided |
| INVALID_MULTIPART | SYS_007 | Invalid multipart request. Please check your file upload. |
| UNAUTHORIZED | SYS_008 | Unauthorized access |
| FORBIDDEN | SYS_009 | Forbidden access by user role |
| RATE_LIMIT_EXCEEDED | SYS_010 | Too many requests. Please slow down. |

## Auth (`AuthErrorCode`)

| Enum Name | Code | Message |
|---|---|---|
| TENANT_CODE_REQUIRED | AUTH_001 | Tenant code is required |
| INVALID_TENANT_CODE_FORMAT | AUTH_002 | Tenant code must contain only lowercase alphanumeric characters and underscores |
| TENANT_NOT_FOUND | AUTH_003 | Tenant not found with provided code |
| TENANT_INACTIVE | AUTH_004 | Tenant facility is not active |
| INVALID_CREDENTIALS | AUTH_005 | Invalid username or password |
| TOKEN_EXPIRED | AUTH_006 | Authentication token has expired |
| INVALID_TOKEN | AUTH_007 | Malformed or invalid authentication token |
| REFRESH_TOKEN_EXPIRED | AUTH_008 | Refresh token has expired or been revoked |
| TENANT_MISMATCH | AUTH_009 | Authenticated user tenant claim does not match requested tenant |
| ROLE_NOT_PERMITTED | AUTH_010 | User role is not permitted for this action or portal |
| KEYCLOAK_UNAVAILABLE | AUTH_011 | Keycloak identity provider service is currently unavailable |
| LOGIN_URL_BUILD_FAILED | AUTH_012 | Failed to generate staff login redirect URL |
| INVALID_OTP | AUTH_013 | Invalid or expired verification code |
| OTP_EXPIRED | AUTH_014 | Verification code has expired. Please request a new code. |
| OTP_TOO_MANY_ATTEMPTS | AUTH_015 | Too many invalid OTP attempts. Please request a new code. |
| USER_BLOCKED | AUTH_016 | User account is suspended or blocked |
| OTP_COOLDOWN_ACTIVE | AUTH_017 | Please wait before requesting a new verification code |
| OTP_DAILY_LIMIT_EXCEEDED | AUTH_018 | Daily limit of 5 verification codes reached for this contact. Please try again after 24 hours. |
| WIDGET_KEY_INVALID | AUTH_019 | Invalid or unrecognized widget integration key |
| UNAUTHORIZED_ORIGIN_DOMAIN | AUTH_020 | Website domain is not authorized to embed the widget for this facility |

## Tenant (`TenantErrorCode`)

| Enum Name | Code | Message |
|---|---|---|
| TENANT_NOT_FOUND | TENANT_001 | Tenant not found |
| TENANT_CODE_ALREADY_EXISTS | TENANT_002 | Tenant code is already registered |
| TENANT_SLUG_INVALID | TENANT_003 | Tenant slug contains invalid characters |
| TENANT_SUSPENDED | TENANT_004 | Tenant operations are paused or suspended |
| TENANT_SUBSCRIPTION_EXPIRED | TENANT_005 | Tenant subscription tier has expired |
| TENANT_SCHEMA_CREATION_FAILED | TENANT_006 | Failed to provision tenant database schema |
| TENANT_SCHEMA_MIGRATION_FAILED | TENANT_007 | Flyway migration failed for tenant schema |
| TENANT_CONTEXT_MISSING | TENANT_008 | Multi-tenant routing context is missing or could not be resolved |
| TENANT_QUOTA_EXCEEDED | TENANT_009 | Monthly ticket or active counter quota exceeded for tenant tier |

## Queue (`QueueErrorCode`)

| Enum Name | Code | Message |
|---|---|---|
| QUEUE_NOT_FOUND | QUEUE_001 | Queue service line not found |
| QUEUE_CLOSED | QUEUE_002 | Queue is currently closed or outside operational business hours |
| QUEUE_PAUSED | QUEUE_003 | Queue intake is temporarily paused by counter staff |
| QUEUE_CAPACITY_FULL | QUEUE_004 | Queue maximum capacity has been reached for today |
| DUPLICATE_QUEUE_NAME | QUEUE_005 | A queue service line with this name already exists in branch |
| ACTIVE_TICKET_IN_QUEUE | QUEUE_006 | Customer already holds an active ticket in this queue |
| ESTIMATED_WAIT_UNAVAILABLE | QUEUE_007 | Insufficient telemetry to calculate estimated wait time |

## Counter (`CounterErrorCode`)

| Enum Name | Code | Message |
|---|---|---|
| COUNTER_NOT_FOUND | COUNTER_001 | Service desk counter not found |
| COUNTER_ALREADY_EXISTS | COUNTER_002 | Counter identifier already exists in this branch |
| COUNTER_OFFLINE | COUNTER_003 | Counter is currently closed or operator is offline |
| COUNTER_BUSY | COUNTER_004 | Counter is currently serving another active ticket |
| COUNTER_NOT_ASSIGNED | COUNTER_005 | Staff member is not assigned to this counter |
| COUNTER_SERVICE_MISMATCH | COUNTER_006 | Counter is not configured to handle tickets for this queue service |

## Ticket (`TicketErrorCode`)

| Enum Name | Code | Message |
|---|---|---|
| TICKET_NOT_FOUND | TICKET_001 | Ticket not found |
| INVALID_TICKET_STATE | TICKET_002 | Requested ticket action is invalid for current ticket state |
| TICKET_ALREADY_CALLED | TICKET_003 | Ticket is already being called or served |
| TICKET_ALREADY_COMPLETED | TICKET_004 | Ticket has already been completed or closed |
| TICKET_ALREADY_CANCELLED | TICKET_005 | Ticket was cancelled and cannot be recalled |
| TICKET_MAX_RECALLS_EXCEEDED | TICKET_006 | Maximum recall attempts reached; ticket must be marked NO-SHOW |
| TICKET_TRANSFER_FAILED | TICKET_007 | Unable to transfer ticket to target queue or counter |
| TICKET_HOLD_LIMIT_REACHED | TICKET_008 | Hold duration limit exceeded for ticket |

## Appointment (`AppointmentErrorCode`)

| Enum Name | Code | Message |
|---|---|---|
| APPOINTMENT_NOT_FOUND | APPT_001 | Appointment booking not found |
| SLOT_UNAVAILABLE | APPT_002 | Selected appointment time slot is already fully booked |
| BOOKING_WINDOW_INVALID | APPT_003 | Appointment date is outside the permissible booking window |
| APPOINTMENT_ALREADY_CHECKED_IN | APPT_004 | Appointment has already checked in and received a queue ticket |
| CHECK_IN_TOO_EARLY | APPT_005 | Check-in is only permitted up to 30 minutes before appointment time |
| CHECK_IN_EXPIRED | APPT_006 | Appointment check-in grace period has expired |
| CANCELLATION_DEADLINE_PASSED | APPT_007 | Appointment cancellation deadline has passed |
| CUSTOMER_DOUBLE_BOOKING | APPT_008 | Customer already has an appointment booked for this service time slot |

## Notification (`NotificationErrorCode`)

| Enum Name | Code | Message |
|---|---|---|
| SMS_GATEWAY_ERROR | NOTIF_001 | SMS gateway dispatch failed |
| EMAIL_GATEWAY_ERROR | NOTIF_002 | Email delivery service failed |
| WEBSOCKET_BROADCAST_ERROR | NOTIF_003 | WebSocket message broadcast failed |
| TELEGRAM_BOT_ERROR | NOTIF_004 | Telegram alert delivery failed |
| NOTIFICATION_TEMPLATE_NOT_FOUND | NOTIF_005 | Notification template not configured for tenant |

## Admin (`AdminErrorCode`)

| Enum Name | Code | Message |
|---|---|---|
| IMPERSONATION_DENIED | ADMIN_001 | Tenant impersonation not permitted without valid reason or active session |
| CROSS_TENANT_ACCESS_DENIED | ADMIN_002 | Cross-tenant data manipulation is strictly prohibited |
| BRANCH_NOT_FOUND | ADMIN_003 | Tenant branch not found |
| STAFF_USER_NOT_FOUND | ADMIN_004 | Staff user not found in tenant organization |
| ROLE_ASSIGNMENT_INVALID | ADMIN_005 | Invalid staff role assignment for tenant context |
