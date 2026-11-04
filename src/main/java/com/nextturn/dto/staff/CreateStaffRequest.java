package com.nextturn.dto.staff;

import com.nextturn.enums.StaffRole;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Payload submitted by an Admin to register and onboard a new staff member.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Staff onboarding payload")
public class CreateStaffRequest {

    @NotBlank(message = "Họ và tên nhân viên là bắt buộc")
    @Size(min = 2, max = 100, message = "Họ và tên phải có độ dài từ 2 đến 100 ký tự")
    @Pattern(
            regexp = "^[\\p{L}\\s]+$",
            message = "Họ và tên chỉ được chứa chữ cái và khoảng trắng"
    )
    @Schema(description = "Full legal name of the staff member", example = "Nguyễn Văn A", requiredMode = Schema.RequiredMode.REQUIRED)
    private String fullName;

    @NotBlank(message = "Địa chỉ email là bắt buộc")
    @Pattern(
            regexp = "^[a-zA-Z0-9_!#$%&'*+/=?`{|}~^.-]+@[a-zA-Z0-9.-]+$",
            message = "Email không đúng định dạng chuẩn RFC 5322"
    )
    @Schema(description = "Corporate or staff email address used for login", example = "staff.a@tenant.com", requiredMode = Schema.RequiredMode.REQUIRED)
    private String email;

    @NotBlank(message = "Số điện thoại là bắt buộc")
    @Pattern(
            regexp = "^(0[3|5|7|8|9])[0-9]{8}$",
            message = "Số điện thoại phải đúng định dạng số điện thoại Việt Nam (10 chữ số bắt đầu bằng 03, 05, 07, 08, 09)"
    )
    @Schema(description = "Vietnam standard 10-digit mobile phone number", example = "0912345678", requiredMode = Schema.RequiredMode.REQUIRED)
    private String phoneNumber;

    @NotNull(message = "Vai trò nhân viên là bắt buộc")
    @Schema(description = "Operational role within tenant (ADMIN, COUNTER, SPECIALIST)", example = "COUNTER", requiredMode = Schema.RequiredMode.REQUIRED)
    private StaffRole role;

    @Schema(description = "Optional initial temporary password. If omitted, activation link is sent to user email.", example = "StaffPass123!@#")
    private String initialPassword;
}

