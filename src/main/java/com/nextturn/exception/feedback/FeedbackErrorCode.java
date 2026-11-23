package com.nextturn.exception.feedback;

import com.nextturn.exception.BaseErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;
import org.springframework.http.HttpStatus;

/**
 * Domain error codes for customer feedback submission and CSAT queries (REQ-FEEDBACK-01..04).
 */
@Getter
@Accessors(fluent = true)
@RequiredArgsConstructor
public enum FeedbackErrorCode implements BaseErrorCode {

    FEEDBACK_TOKEN_INVALID("FEEDBACK_001", "Liên kết đánh giá không tồn tại hoặc không hợp lệ.", HttpStatus.NOT_FOUND),
    FEEDBACK_TOKEN_EXPIRED("FEEDBACK_002", "Phiên đánh giá cho lượt phục vụ này đã hết hạn.", HttpStatus.GONE),
    FEEDBACK_ALREADY_SUBMITTED("FEEDBACK_003", "Bạn đã hoàn thành đánh giá cho lượt phục vụ này. Xin chân thành cảm ơn!", HttpStatus.CONFLICT),
    FEEDBACK_INVALID_INPUT("FEEDBACK_004", "Điểm đánh giá phải là số nguyên từ 1 đến 5 sao.", HttpStatus.BAD_REQUEST),
    FEEDBACK_TICKET_NOT_COMPLETED("FEEDBACK_005", "Chỉ có thể đánh giá sau khi lượt phục vụ đã hoàn tất.", HttpStatus.CONFLICT);

    private final String code;
    private final String message;
    private final HttpStatus httpStatus;
}
