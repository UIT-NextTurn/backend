package com.nextturn.exception.checkin;

import com.nextturn.exception.BaseErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;
import org.springframework.http.HttpStatus;

/**
 * Domain error codes for Reception Point QR Management and Lobby Check-in (REQ-CHECK-IN-01..03).
 */
@Getter
@Accessors(fluent = true)
@RequiredArgsConstructor
public enum CheckInErrorCode implements BaseErrorCode {

    RECEPTION_POINT_NOT_FOUND("CHECKIN_001", "Không tìm thấy điểm tiếp đón.", HttpStatus.NOT_FOUND),
    RECEPTION_POINT_CODE_EXISTS("CHECKIN_002", "Mã điểm tiếp đón đã tồn tại.", HttpStatus.CONFLICT),
    RECEPTION_POINT_INACTIVE("CHECKIN_003", "Điểm tiếp đón hiện đang tạm ngưng hoạt động.", HttpStatus.BAD_REQUEST),
    RECEPTION_QR_INVALID("CHECKIN_004", "Mã QR điểm tiếp đón không hợp lệ.", HttpStatus.BAD_REQUEST),
    RECEPTION_QR_EXPIRED("CHECKIN_005", "Mã QR điểm tiếp đón đã hết hạn (60 giây). Vui lòng quét lại mã QR mới nhất trên màn hình tiếp đón.", HttpStatus.BAD_REQUEST),
    SERVICE_MISMATCH_WITH_RECEPTION("CHECKIN_006", "Dịch vụ được chọn không khớp với điểm tiếp đón này.", HttpStatus.BAD_REQUEST);

    private final String code;
    private final String message;
    private final HttpStatus httpStatus;
}
