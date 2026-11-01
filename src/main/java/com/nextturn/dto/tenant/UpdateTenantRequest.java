package com.nextturn.dto.tenant;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request payload for updating tenant administrative info and operational quotas (REQ-TNT-02).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Request body for updating tenant profile and quotas")
public class UpdateTenantRequest {

    @Size(min = 3, max = 150, message = "Tên tổ chức phải có độ dài từ 3 đến 150 ký tự")
    @Pattern(regexp = "^[^<>\"'%;()&+\\\\]+$", message = "Tên tổ chức không được chứa ký tự đặc biệt nguy hiểm")
    @Schema(description = "Updated facility name", example = "Bệnh viện Đa khoa Tâm Đức Mở Rộng")
    private String tenantName;

    @Pattern(regexp = "^[0-9]{10}$", message = "Số điện thoại liên hệ phải chuẩn định dạng 10 chữ số")
    @Schema(description = "Updated contact phone number (10 digits)", example = "0912345678")
    private String phoneNumber;

    @Min(value = 1, message = "Số lượng nhân viên tối đa phải là số nguyên dương >= 1")
    @Schema(description = "Maximum active staff members allowed (>= 1)", example = "15")
    private Integer maxStaffs;

    @Schema(description = "Daily ticket quota (>= 1 or -1 for unlimited)", example = "1000")
    private Integer dailyTicketQuota;

    @Schema(description = "Maximum service categories allowed (>= 1 or -1 for unlimited)", example = "50")
    private Integer maxServices;

    @Schema(description = "Maximum future appointments allowed (>= 1 or -1 for unlimited)", example = "-1")
    private Integer maxAppointments;
}

