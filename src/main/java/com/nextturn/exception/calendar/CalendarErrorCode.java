package com.nextturn.exception.calendar;

import com.nextturn.exception.BaseErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;
import org.springframework.http.HttpStatus;

/**
 * Domain error codes for Tenant Calendar work shift scheduling and capacity time slot generation.
 */
@Getter
@Accessors(fluent = true)
@RequiredArgsConstructor
public enum CalendarErrorCode implements BaseErrorCode {

    STAFF_NOT_FOUND(
            "CAL_001",
            "Nhân viên không tồn tại trong hệ thống hoặc đã bị xóa.",
            HttpStatus.NOT_FOUND
    ),
    STAFF_NOT_ACTIVE(
            "CAL_002",
            "Nhân viên không ở trạng thái hoạt động.",
            HttpStatus.BAD_REQUEST
    ),
    STAFF_NO_SERVICE(
            "CAL_003",
            "Nhân viên chưa được phân công dịch vụ nào.",
            HttpStatus.BAD_REQUEST
    ),
    SERVICE_NOT_FOUND(
            "CAL_004",
            "Dịch vụ không tồn tại hoặc đã bị vô hiệu hóa.",
            HttpStatus.NOT_FOUND
    ),
    SERVICE_NOT_ASSIGNED(
            "CAL_005",
            "Nhân viên không được phân công phụ trách dịch vụ này.",
            HttpStatus.BAD_REQUEST
    ),
    SERVICE_REQUIRED_FOR_MULTI_SERVICE_STAFF(
            "CAL_006",
            "Nhân viên phụ trách nhiều dịch vụ. Vui lòng chỉ định cụ thể dịch vụ cho ca làm việc.",
            HttpStatus.BAD_REQUEST
    ),
    DATE_IN_PAST(
            "CAL_007",
            "Ngày làm việc phải lớn hơn hoặc bằng ngày hiện tại (không cho phép tạo ca trong quá khứ).",
            HttpStatus.BAD_REQUEST
    ),
    TIME_WINDOW_INVALID(
            "CAL_008",
            "Thời gian bắt đầu ca phải nhỏ hơn thời gian kết thúc ca.",
            HttpStatus.BAD_REQUEST
    ),
    DURATION_TOO_SHORT(
            "CAL_009",
            "Tổng thời lượng của ca tối thiểu phải lớn hơn hoặc bằng thời lượng phục vụ chuẩn của dịch vụ mà nhân viên đó đảm nhiệm.",
            HttpStatus.BAD_REQUEST
    ),
    SHIFT_OVERLAPPING(
            "CAL_010",
            "Nhân viên đã được phân công ca trực khác từ %s đến %s trong ngày này. Vui lòng kiểm tra lại.",
            HttpStatus.CONFLICT
    ),
    SHIFT_NOT_FOUND(
            "CAL_011",
            "Ca làm việc không tồn tại trong hệ thống.",
            HttpStatus.NOT_FOUND
    ),
    SHIFT_ALREADY_CANCELLED(
            "CAL_012",
            "Ca làm việc đã bị hủy trước đó.",
            HttpStatus.BAD_REQUEST
    ),
    SLOT_NOT_FOUND(
            "CAL_013",
            "Khung giờ không tồn tại trong hệ thống.",
            HttpStatus.NOT_FOUND
    ),
    SLOT_ALREADY_HOLDING_OR_BOOKED(
            "CAL_014",
            "Khung giờ này vừa có người khác nhanh tay chọn trước. Vui lòng chọn một khung giờ khác.",
            HttpStatus.CONFLICT
    ),
    HOLDING_TOKEN_INVALID(
            "CAL_015",
            "Mã giữ chỗ không hợp lệ hoặc không khớp.",
            HttpStatus.BAD_REQUEST
    ),
    HOLDING_TOKEN_EXPIRED(
            "CAL_016",
            "Thời gian giữ chỗ của bạn đã hết hạn. Vui lòng chọn lại khung giờ.",
            HttpStatus.BAD_REQUEST
    ),
    SHIFT_CANNOT_CANCEL_WITH_BOOKED_SLOTS(
            "CAL_017",
            "Ca trực hiện có %d lịch hẹn của khách hàng đã được xác nhận. Vui lòng hủy lịch hẹn hoặc điều chuyển nhân viên thay thế trước khi hủy ca trực này.",
            HttpStatus.CONFLICT
    ),
    SHIFT_CANNOT_TRUNCATE_WITH_BOOKED_SLOTS(
            "CAL_018",
            "Ca trực hiện có %d lịch hẹn của khách hàng đã được xác nhận trong khoảng thời gian bị cắt giảm. Vui lòng hủy lịch hẹn hoặc điều chuyển nhân viên thay thế trước khi điều chỉnh ca trực này.",
            HttpStatus.CONFLICT
    ),
    SETTINGS_INVALID(
            "CAL_019",
            "Thông số chính sách đặt hẹn không hợp lệ.",
            HttpStatus.BAD_REQUEST
    );

    private final String code;
    private final String message;
    private final HttpStatus httpStatus;
}

