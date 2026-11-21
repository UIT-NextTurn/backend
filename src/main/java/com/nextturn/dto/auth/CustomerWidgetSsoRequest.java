package com.nextturn.dto.auth;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request payload for REQ-AUTH-08: Inheriting logged-in customer identity from an enterprise host website
 * into the embedded NextTurn widget via HMAC-SHA256 digital signature (*kế thừa định danh khách đặt lịch
 * từ trang web doanh nghiệp qua chữ ký số bảo mật*).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Request payload for embedded widget customer SSO via HMAC-SHA256 signature (REQ-AUTH-08)")
public class CustomerWidgetSsoRequest {

    @Schema(description = "Public widget integration key of the tenant facility (can also be sent via X-Widget-Key header)", example = "nt_pub_a8b9c0d1e2")
    private String widgetApiKey;

    @NotBlank(message = "Mã định danh thành viên tại trang web doanh nghiệp là bắt buộc")
    @Schema(description = "External member identifier on the enterprise host website", example = "MEM-998877", requiredMode = Schema.RequiredMode.REQUIRED)
    private String externalMemberId;

    @NotBlank(message = "Họ và tên khách hàng là bắt buộc")
    @Schema(description = "Customer full name from enterprise website", example = "Nguyễn Văn Khách", requiredMode = Schema.RequiredMode.REQUIRED)
    private String fullName;

    @Schema(description = "Customer email address from enterprise website", example = "khach.nguyen@example.com")
    private String email;

    @Schema(description = "Customer phone number from enterprise website", example = "0901234567")
    private String phoneNumber;

    @NotNull(message = "Mốc thời gian tạo chữ ký là bắt buộc")
    @Schema(description = "Unix timestamp (in seconds or milliseconds) when the signature was generated", example = "1795250000", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long timestamp;

    @NotBlank(message = "Chữ ký số bảo mật là bắt buộc")
    @Schema(description = "HMAC-SHA256 hex or Base64 signature computed over canonical payload using tenant's widget_secret_key", requiredMode = Schema.RequiredMode.REQUIRED)
    private String signature;

    @Schema(description = "Optional explicit host website domain for origin verification", example = "https://clinic-saigon.vn")
    private String originDomain;
}
