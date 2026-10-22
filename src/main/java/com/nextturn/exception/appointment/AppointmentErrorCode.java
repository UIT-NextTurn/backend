package com.nextturn.exception.appointment;

import com.nextturn.exception.BaseErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;
import org.springframework.http.HttpStatus;

/**
 * Appointment booking, slot reservation, and check-in error codes.
 */
@Getter
@Accessors(fluent = true)
@RequiredArgsConstructor
public enum AppointmentErrorCode implements BaseErrorCode {

    APPOINTMENT_NOT_FOUND("APPT_001", "Appointment booking not found", HttpStatus.NOT_FOUND),
    SLOT_UNAVAILABLE("APPT_002", "Selected appointment time slot is already fully booked", HttpStatus.CONFLICT),
    BOOKING_WINDOW_INVALID("APPT_003", "Appointment date is outside the permissible booking window", HttpStatus.BAD_REQUEST),
    APPOINTMENT_ALREADY_CHECKED_IN("APPT_004", "Appointment has already checked in and received a queue ticket", HttpStatus.CONFLICT),
    CHECK_IN_TOO_EARLY("APPT_005", "Check-in is only permitted up to 30 minutes before appointment time", HttpStatus.BAD_REQUEST),
    CHECK_IN_EXPIRED("APPT_006", "Appointment check-in grace period has expired", HttpStatus.BAD_REQUEST),
    CANCELLATION_DEADLINE_PASSED("APPT_007", "Appointment cancellation deadline has passed", HttpStatus.BAD_REQUEST),
    CUSTOMER_DOUBLE_BOOKING("APPT_008", "Customer already has an appointment booked for this service time slot", HttpStatus.CONFLICT);

    private final String code;
    private final String message;
    private final HttpStatus httpStatus;
}
