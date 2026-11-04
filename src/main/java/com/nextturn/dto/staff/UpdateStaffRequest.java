package com.nextturn.dto.staff;

import com.nextturn.enums.StaffRole;
import com.nextturn.enums.StaffStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Payload for updating staff profile, assigned role, or operational status.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Payload for updating staff details or status")
public class UpdateStaffRequest {

    @Size(min = 2, max = 100, message = "Họ và tên phải có độ dài từ 2 đến 100 ký tự")
    @Pattern(
            regexp = "^[\\p{L}\\s]+$",
            message = "Họ và tên chỉ được chứa chữ cái và khoảng trắng"
    )
    @Schema(description = "Updated full name", example = "Nguyễn Văn B")
    private String fullName;

    @Pattern(
            regexp = "^(0[3|5|7|8|9])[0-9]{8}$",
            message = "Số điện thoại phải đúng định dạng số điện thoại Việt Nam (10 chữ số bắt đầu bằng 03, 05, 07, 08, 09)"
    )
    @Schema(description = "Updated phone number", example = "0987654321")
    private String phoneNumber;

    @Schema(description = "Updated operational role", example = "COUNTER")
    private StaffRole role;

    @Schema(description = "Updated operational status (ACTIVE, INACTIVE)", example = "ACTIVE")
    private StaffStatus status;
}

