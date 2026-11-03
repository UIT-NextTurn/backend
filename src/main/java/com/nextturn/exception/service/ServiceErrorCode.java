package com.nextturn.exception.service;

import com.nextturn.exception.BaseErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;
import org.springframework.http.HttpStatus;

/**
 * Domain error codes for Tenant Service Catalog management.
 */
@Getter
@Accessors(fluent = true)
@RequiredArgsConstructor
public enum ServiceErrorCode implements BaseErrorCode {

    SERVICE_NAME_ALREADY_EXISTS(
            "SVC_001",
            "Tên dịch vụ đã tồn tại trong danh mục này. Vui lòng nhập tên khác.",
            HttpStatus.CONFLICT
    ),
    SERVICE_NOT_FOUND(
            "SVC_002",
            "Dịch vụ không tồn tại trong hệ thống hoặc đã bị xóa.",
            HttpStatus.NOT_FOUND
    ),
    SERVICE_DURATION_INVALID(
            "SVC_003",
            "Thời lượng phục vụ chuẩn phải là số nguyên dương tính theo phút.",
            HttpStatus.BAD_REQUEST
    ),
    SERVICE_CANNOT_DELETE_ACTIVE_BOOKINGS(
            "SVC_004",
            "Dịch vụ này hiện đang có lịch hẹn hoặc vé chờ chưa hoàn tất. Hệ thống không thể xóa dịch vụ. Quản trị viên vui lòng chuyển sang trạng thái “Tạm ngưng hoạt động” (Inactive) để dừng tiếp nhận lượt mới.",
            HttpStatus.CONFLICT
    );

    private final String code;
    private final String message;
    private final HttpStatus httpStatus;
}
