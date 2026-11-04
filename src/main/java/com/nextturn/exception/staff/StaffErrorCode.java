package com.nextturn.exception.staff;

import com.nextturn.exception.BaseErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;
import org.springframework.http.HttpStatus;

/**
 * Domain error codes for Tenant Staff management.
 */
@Getter
@Accessors(fluent = true)
@RequiredArgsConstructor
public enum StaffErrorCode implements BaseErrorCode {

    STAFF_ALREADY_EXISTS(
            "STF_001",
            "Email hoặc số điện thoại này đã được đăng ký bởi một nhân viên khác trong hệ thống.",
            HttpStatus.CONFLICT
    ),
    STAFF_NOT_FOUND(
            "STF_002",
            "Nhân viên không tồn tại trong hệ thống hoặc đã bị xóa.",
            HttpStatus.NOT_FOUND
    ),
    STAFF_NOT_ACTIVE(
            "STF_003",
            "Nhân viên không ở trạng thái hoạt động.",
            HttpStatus.BAD_REQUEST
    ),
    STAFF_SERVICE_EMPTY(
            "STF_004",
            "Danh sách dịch vụ phân công không được để trống.",
            HttpStatus.BAD_REQUEST
    ),
    STAFF_KEYCLOAK_SYNC_FAILED(
            "STF_005",
            "Lỗi đồng bộ tài khoản xác thực Keycloak.",
            HttpStatus.INTERNAL_SERVER_ERROR
    ),
    STAFF_CANNOT_DEACTIVATE_ACTIVE_SCHEDULE(
            "STF_006",
            "Nhân viên hiện đang có ca trực hoặc cuộc hẹn trong tương lai chưa hoàn tất. Vui lòng chuyển giao các cuộc hẹn này sang nhân viên khác trước khi vô hiệu hóa.",
            HttpStatus.CONFLICT
    ),
    STAFF_CANNOT_HARD_DELETE_HISTORICAL_DATA(
            "STF_007",
            "Hệ thống tuyệt đối từ chối thao tác Xóa vật lý (Hard delete) bản ghi nhân sự nếu nhân viên đó đã từng có lịch sử gọi vé, tiếp nhận phục vụ hoặc phản hồi đánh giá trong cơ sở dữ liệu.",
            HttpStatus.CONFLICT
    );

    private final String code;
    private final String message;
    private final HttpStatus httpStatus;
}
