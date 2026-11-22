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
    ESTIMATED_WAIT_UNAVAILABLE("QUEUE_007", "Insufficient telemetry to calculate estimated wait time", HttpStatus.SERVICE_UNAVAILABLE),
    QUEUE_EMPTY("QUEUE_008", "Hàng đợi hiện đang trống, không có khách chờ gọi số.", HttpStatus.NOT_FOUND),
    NO_AVAILABLE_SPECIALIST("QUEUE_009", "Hiện toàn bộ chuyên viên phụ trách dịch vụ %s đang bận. Vui lòng đợi chuyên viên hoàn tất lượt phục vụ hiện tại trước khi gọi số tiếp theo.", HttpStatus.CONFLICT),
    SPECIALIST_NOT_AVAILABLE("QUEUE_010", "Chuyên viên được chọn hiện không khả dụng, không thuộc ca trực hoặc đang phục vụ khách khác.", HttpStatus.CONFLICT),
    NO_SHOW_PRECONDITION_NOT_MET("QUEUE_011", "Chỉ được đánh dấu vắng mặt (No-show) sau khi đã gọi lại tối thiểu 3 lần hoặc quá 3 phút chờ.", HttpStatus.BAD_REQUEST),
    VERSION_CONFLICT("QUEUE_012", "Trạng thái phiếu vé đã bị thay đổi bởi thao tác khác. Vui lòng tải lại dữ liệu mới nhất.", HttpStatus.CONFLICT);

    private final String code;
    private final String message;
    private final HttpStatus httpStatus;
}
