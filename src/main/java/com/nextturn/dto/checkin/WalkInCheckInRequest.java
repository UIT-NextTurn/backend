package com.nextturn.dto.checkin;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request payload for on-site walk-in queue registration (REQ-CHECK-IN-02).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Request payload for walk-in check-in and queue ticket issuance")
public class WalkInCheckInRequest {

    @Schema(description = "Target service UUID (optional if inferred from a service-scoped receptionQrToken)")
    private String serviceId;

    @NotBlank(message = "Số điện thoại khách hàng không được để trống")
    @Pattern(regexp = "^[0-9]{10}$", message = "Số điện thoại phải chuẩn định dạng 10 chữ số")
    @Schema(description = "10-digit customer phone number", example = "0901234567")
    private String customerPhone;

    @Size(max = 150, message = "Họ và tên khách hàng không được vượt quá 150 ký tự")
    @Schema(description = "Optional customer full name", example = "Trần Thị Vãng Lai")
    private String customerName;

    @Schema(description = "Optional dynamic/static reception point QR token scanned at the lobby")
    private String receptionQrToken;
}
