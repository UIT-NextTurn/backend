package com.nextturn.dto.tenant;

import com.nextturn.util.EmailValidationUtil;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request payload for self-registration of a new enterprise tenant facility (REQ-TNT-05).
 * The tenant starts in PENDING_APPROVAL status until Superadministrator reviews it.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Request body for enterprise tenant self-registration")
public class TenantRegistrationRequest {

    @NotBlank(message = "Tên tổ chức là bắt buộc")
    @Size(min = 3, max = 150, message = "Tên tổ chức phải có độ dài từ 3 đến 150 ký tự")
    @Pattern(regexp = "^[^<>\"'%;()&+\\\\]+$", message = "Tên tổ chức không được chứa ký tự đặc biệt nguy hiểm")
    @Schema(description = "Name of the tenant organization", example = "Phòng khám Đa khoa Sài Gòn", requiredMode = Schema.RequiredMode.REQUIRED)
    private String tenantName;

    @NotBlank(message = "Mã định danh tổ chức là bắt buộc")
    @Size(min = 3, max = 30, message = "Mã định danh tổ chức phải có độ dài từ 3 đến 30 ký tự")
    @Pattern(regexp = "^[a-z0-9-]+$", message = "Mã định danh tổ chức chỉ gồm chữ cái in thường không dấu, chữ số và dấu gạch nối")
    @Schema(description = "Unique facility code (lowercase, digits, hyphens only)", example = "pk-sai-gon", requiredMode = Schema.RequiredMode.REQUIRED)
    private String tenantCode;

    @NotBlank(message = "Email quản trị viên cơ sở là bắt buộc")
    @Pattern(
            regexp = EmailValidationUtil.STRICT_EMAIL_REGEX,
            message = "Email không hợp lệ: không được chứa ký tự '+', không bắt đầu/kết thúc bằng dấu '.', không chứa dấu chấm liên tiếp '..', và phải có tên miền hợp lệ"
    )
    @Size(max = 255, message = "Email quản trị viên cơ sở không được vượt quá 255 ký tự")
    @Schema(description = "Tenant administrator email address", example = "admin@saigonclinic.vn", requiredMode = Schema.RequiredMode.REQUIRED)
    private String adminEmail;

    @Schema(description = "Full name of the applicant administrator", example = "Trần Thị Mai")
    private String adminFullName;

    @NotBlank(message = "Số điện thoại liên hệ là bắt buộc")
    @Pattern(regexp = "^[0-9]{10}$", message = "Số điện thoại liên hệ phải chuẩn định dạng 10 chữ số")
    @Schema(description = "Contact phone number (exactly 10 digits)", example = "0912345678", requiredMode = Schema.RequiredMode.REQUIRED)
    private String phoneNumber;

    @Schema(description = "Initial password for tenant admin; if omitted or blank, activation email will require setting password upon first login")
    private String initialPassword;
}
