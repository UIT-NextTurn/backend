package com.nextturn.dto.service;

import com.nextturn.enums.ServiceStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * Request payload for updating service details, operational status, and standard duration.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Payload for updating service information, operational status, and standard duration")
public class UpdateServiceRequest {

    @NotBlank(message = "Tên dịch vụ là bắt buộc")
    @Size(min = 3, max = 150, message = "Tên dịch vụ phải có độ dài từ 3 đến 150 ký tự")
    @Pattern(
            regexp = "^[^<>\"'%;&]+$",
            message = "Tên dịch vụ không chứa các ký tự nguy hiểm gây lỗi bảo mật XSS hoặc SQL Injection"
    )
    @Schema(description = "Updated service display name", example = "Tư Vấn Khách Hàng Nâng Cao", requiredMode = Schema.RequiredMode.REQUIRED)
    private String serviceName;

    @NotNull(message = "Thời lượng phục vụ chuẩn là bắt buộc")
    @Min(value = 1, message = "Thời lượng phục vụ chuẩn phải là số nguyên dương tính theo phút")
    @Schema(description = "Standard service duration in minutes (T_standard >= 1)", example = "20", requiredMode = Schema.RequiredMode.REQUIRED)
    private Integer standardDuration;

    @NotNull(message = "Đơn giá dịch vụ là bắt buộc")
    @DecimalMin(value = "0.00", message = "Đơn giá dịch vụ phải là số không âm")
    @Schema(description = "Service price in VND (0 for free service)", example = "200000.00", requiredMode = Schema.RequiredMode.REQUIRED)
    private BigDecimal price;

    @Schema(description = "Operational status of the service (ACTIVE, INACTIVE)", example = "ACTIVE")
    private ServiceStatus status;
}
