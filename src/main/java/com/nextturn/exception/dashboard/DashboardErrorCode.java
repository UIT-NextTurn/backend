package com.nextturn.exception.dashboard;

import com.nextturn.exception.BaseErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;
import org.springframework.http.HttpStatus;

/**
 * Domain error codes for Tenant Analytics Dashboard and Superadmin Platform Monitoring (REQ-DASHBOARD-01..06).
 */
@Getter
@Accessors(fluent = true)
@RequiredArgsConstructor
public enum DashboardErrorCode implements BaseErrorCode {

    INVALID_DATE_RANGE(
            "DASH_001",
            "Khoảng ngày thống kê không hợp lệ (ngày bắt đầu phải nhỏ hơn hoặc bằng ngày kết thúc).",
            HttpStatus.BAD_REQUEST
    ),
    UNSUPPORTED_EXPORT_FORMAT(
            "DASH_002",
            "Định dạng xuất báo cáo không hợp lệ. Chỉ hỗ trợ CSV hoặc XLSX.",
            HttpStatus.BAD_REQUEST
    ),
    EXPORT_GENERATION_FAILED(
            "DASH_003",
            "Không thể khởi tạo tệp báo cáo thống kê. Vui lòng thử lại sau.",
            HttpStatus.INTERNAL_SERVER_ERROR
    ),
    INVALID_FILTER_PARAMETER(
            "DASH_004",
            "Tham số lọc bảng điều khiển không hợp lệ.",
            HttpStatus.BAD_REQUEST
    );

    private final String code;
    private final String message;
    private final HttpStatus httpStatus;
}
