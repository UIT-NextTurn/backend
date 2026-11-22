package com.nextturn.dto.ticket;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request payload for issuing an electronic queue ticket for either a confirmed appointment check-in
 * or a walk-in customer (REQ-TICKET-01, REQ-CHECK-IN-01, REQ-CHECK-IN-02).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Request payload to issue an electronic queue ticket")
public class IssueTicketRequest {

    @Schema(description = "Target service UUID (required for walk-in; inferred from appointment if appointmentId/qrToken is provided)")
    private String serviceId;

    @Schema(description = "Confirmed appointment UUID or code (for pre-booked customer check-in)")
    private String appointmentId;

    @Schema(description = "Appointment QR verification token (alternative to appointmentId)")
    private String qrToken;

    @Pattern(regexp = "^$|^[0-9]{10}$", message = "Số điện thoại phải chuẩn định dạng 10 chữ số")
    @Schema(description = "10-digit customer phone number (required for walk-in registration & ticket recovery)", example = "0901234567")
    private String customerPhone;

    @Schema(description = "Optional reception point QR token scanned at the facility lobby/desk (REQ-CHECK-IN-03)")
    private String receptionQrToken;

    @Size(max = 150, message = "Họ và tên khách hàng không được vượt quá 150 ký tự")
    @Schema(description = "Optional customer full name", example = "Nguyễn Văn Khách")
    private String customerName;
}
