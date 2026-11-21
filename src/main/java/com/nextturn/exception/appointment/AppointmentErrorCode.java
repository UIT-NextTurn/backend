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
    CUSTOMER_DOUBLE_BOOKING("APPT_008", "Customer already has an appointment booked for this service time slot", HttpStatus.CONFLICT),
    NO_AVAILABLE_SLOTS("APPT_009", "Dịch vụ trong ngày đã chọn hiện không còn khung giờ trống khả dụng. Quý khách vui lòng chọn ngày khác trong lịch mở hẹn.", HttpStatus.NOT_FOUND),
    HOLDING_SESSION_EXPIRED("APPT_010", "Phiên giữ chỗ của bạn đã hết hạn (quá 10 phút). Vui lòng chọn lại khung giờ để tiếp tục.", HttpStatus.BAD_REQUEST),
    HOLDING_TOKEN_MISMATCH("APPT_011", "Mã giữ chỗ không hợp lệ hoặc không khớp với khung giờ đã chọn.", HttpStatus.BAD_REQUEST),
    LEAD_TIME_VIOLATED("APPT_012", "Khung giờ này đã quá thời hạn đặt trước tối thiểu (%d giờ) theo quy định của cơ sở. Vui lòng chọn khung giờ khác.", HttpStatus.BAD_REQUEST),
    APPOINTMENT_NOT_CONFIRMED("APPT_013", "Chỉ cuộc hẹn đang ở trạng thái Đã xác nhận (CONFIRMED) mới được phép thay đổi hoặc hủy lịch.", HttpStatus.BAD_REQUEST),
    INVALID_CUSTOMER_INFO("APPT_014", "Thông tin khách hàng đặt lịch không hợp lệ.", HttpStatus.BAD_REQUEST),
    UNAUTHORIZED_APPOINTMENT_ACCESS("APPT_015", "Bạn không có quyền truy cập hoặc chỉnh sửa cuộc hẹn này.", HttpStatus.FORBIDDEN);

    private final String code;
    private final String message;
    private final HttpStatus httpStatus;
}
