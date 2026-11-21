package com.nextturn.dto.appointment;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request payload for confirming customer information and creating an appointment (REQ-APT-03).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Request payload to confirm held slot and create a customer appointment (REQ-APT-03)")
public class CreateAppointmentRequest {

    @NotBlank(message = "Mã khung giờ (slot_id) là bắt buộc")
    @Schema(description = "Target time slot UUID currently held by the customer", requiredMode = Schema.RequiredMode.REQUIRED)
    private String slotId;

    @NotBlank(message = "Mã phiên giữ chỗ (holding_session_token) là bắt buộc")
    @Schema(description = "Holding session token issued when the slot was locked (REQ-APT-02)", requiredMode = Schema.RequiredMode.REQUIRED)
    private String holdingToken;

    @NotBlank(message = "Họ và tên khách hàng là bắt buộc")
    @Size(min = 2, max = 100, message = "Họ và tên khách hàng phải có độ dài từ 2 đến 100 ký tự")
    @Schema(description = "Customer full name (2-100 characters)", example = "Nguyễn Văn Khách", requiredMode = Schema.RequiredMode.REQUIRED)
    private String customerName;

    @NotBlank(message = "Số điện thoại khách hàng là bắt buộc")
    @Pattern(regexp = "^[0-9]{10}$", message = "Số điện thoại phải chuẩn định dạng 10 chữ số")
    @Schema(description = "Customer phone number (10 digits)", example = "0901234567", requiredMode = Schema.RequiredMode.REQUIRED)
    private String customerPhone;

    @Size(max = 255, message = "Địa chỉ Email không được vượt quá 255 ký tự")
    @Pattern(
            regexp = "^$|^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$",
            message = "Địa chỉ Email không đúng định dạng chuẩn RFC 5322"
    )
    @Schema(description = "Optional customer email address for confirmation and reminders", example = "khach@example.com")
    private String customerEmail;

    @Size(max = 255, message = "Ghi chú không được vượt quá 255 ký tự")
    @Schema(description = "Optional customer notes (max 255 characters)", example = "Khám định kỳ buổi sáng")
    private String notes;
}
