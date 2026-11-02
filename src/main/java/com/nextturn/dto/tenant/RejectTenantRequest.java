package com.nextturn.dto.tenant;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request payload for rejecting a pending tenant registration application (REQ-TNT-06).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Request body for denying a tenant registration application")
public class RejectTenantRequest {

    @NotBlank(message = "Lý do từ chối là bắt buộc")
    @Size(max = 500, message = "Lý do từ chối không được vượt quá 500 ký tự")
    @Schema(
            description = "Mandatory explanation reason for rejecting the registration application",
            example = "Giấy phép kinh doanh y tế đã hết hạn hoặc không khớp với thông tin đăng ký.",
            requiredMode = Schema.RequiredMode.REQUIRED
    )
    private String reason;
}
