package com.nextturn.exception.ticket;

import com.nextturn.exception.BaseErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;
import org.springframework.http.HttpStatus;

/**
 * Queue ticket lifecycle and state machine transition error codes.
 */
@Getter
@Accessors(fluent = true)
@RequiredArgsConstructor
public enum TicketErrorCode implements BaseErrorCode {

    TICKET_NOT_FOUND("TICKET_001", "Ticket not found", HttpStatus.NOT_FOUND),
    INVALID_TICKET_STATE("TICKET_002", "Requested ticket action is invalid for current ticket state", HttpStatus.BAD_REQUEST),
    TICKET_ALREADY_CALLED("TICKET_003", "Ticket is already being called or served", HttpStatus.CONFLICT),
    TICKET_ALREADY_COMPLETED("TICKET_004", "Ticket has already been completed or closed", HttpStatus.BAD_REQUEST),
    TICKET_ALREADY_CANCELLED("TICKET_005", "Ticket was cancelled and cannot be recalled", HttpStatus.BAD_REQUEST),
    TICKET_MAX_RECALLS_EXCEEDED("TICKET_006", "Maximum recall attempts reached; ticket must be marked NO-SHOW", HttpStatus.BAD_REQUEST),
    TICKET_TRANSFER_FAILED("TICKET_007", "Unable to transfer ticket to target queue or counter", HttpStatus.INTERNAL_SERVER_ERROR),
    TICKET_HOLD_LIMIT_REACHED("TICKET_008", "Hold duration limit exceeded for ticket", HttpStatus.BAD_REQUEST),
    NO_ACTIVE_TICKET_TODAY("TICKET_009", "Không tìm thấy vé hàng đợi nào đang chờ phục vụ với số điện thoại này trong hôm nay.", HttpStatus.NOT_FOUND),
    SERVICE_UNAVAILABLE_FOR_TICKET("TICKET_010", "Dịch vụ không tồn tại hoặc hiện đang tạm ngưng tiếp nhận.", HttpStatus.BAD_REQUEST),
    WALK_IN_CAPACITY_FULL("TICKET_011", "Dịch vụ này hiện đã ngưng phát số tiếp nhận trực tiếp cho ca làm việc hôm nay. Quý khách vui lòng đặt lịch hẹn trước vào các ngày tiếp theo trên hệ thống.", HttpStatus.CONFLICT),
    CHECKIN_TOO_EARLY("TICKET_012", "Bạn đến quá sớm so với giờ hẹn (%s). Vui lòng quét mã lại trong khoảng thời gian từ %s để được xếp hàng.", HttpStatus.BAD_REQUEST),
    CHECKIN_TOO_LATE("TICKET_013", "Lịch hẹn của bạn đã quá hạn tiếp nhận. Vui lòng đăng ký lượt mới theo diện khách vãng lai tại màn hình tiếp đón.", HttpStatus.BAD_REQUEST),
    APPOINTMENT_WRONG_DATE("TICKET_014", "Lịch hẹn không diễn ra trong ngày hôm nay nên chưa thể check-in lấy số thứ tự.", HttpStatus.BAD_REQUEST),
    DUPLICATE_ACTIVE_TICKET("TICKET_015", "Số điện thoại này đang có phiếu số thứ tự chờ phục vụ cho dịch vụ này trong hôm nay.", HttpStatus.CONFLICT),
    TICKET_LOCK_CONFLICT("TICKET_016", "Hệ thống cấp số đang xử lý đồng thời, vui lòng thử lại sau giây lát.", HttpStatus.CONFLICT),
    DAILY_TICKET_QUOTA_EXCEEDED("TICKET_017", "Cơ sở đã đạt hạn ngạch cấp vé hàng ngày theo gói thuê bao.", HttpStatus.TOO_MANY_REQUESTS);

    private final String code;
    private final String message;
    private final HttpStatus httpStatus;
}
