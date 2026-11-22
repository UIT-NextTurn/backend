package com.nextturn.dto.checkin;

import com.nextturn.enums.ReceptionQrMode;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request payload for creating a physical lobby/counter reception point (REQ-CHECK-IN-03).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Request payload to register a lobby or service reception point for QR check-in")
public class CreateReceptionPointRequest {

    @NotBlank(message = "Mã điểm tiếp đón không được để trống")
    @Size(max = 30, message = "Mã điểm tiếp đón không được vượt quá 30 ký tự")
    @Pattern(regexp = "^[A-Za-z0-9_-]+$", message = "Mã điểm tiếp đón chỉ được chứa chữ cái, số, dấu gạch ngang hoặc gạch dưới")
    @Schema(description = "Unique reception point code", example = "REC-MAIN-01")
    private String pointCode;

    @NotBlank(message = "Tên điểm tiếp đón không được để trống")
    @Size(max = 150, message = "Tên điểm tiếp đón không được vượt quá 150 ký tự")
    @Schema(description = "Display name of the reception point", example = "Quầy Tiếp Đón Sảnh Chính")
    private String pointName;

    @Size(max = 150, message = "Vị trí điểm tiếp đón không được vượt quá 150 ký tự")
    @Schema(description = "Optional physical location label", example = "Tầng 1 - Sảnh A")
    private String locationLabel;

    @Schema(description = "Optional target service UUID (null means general multi-service lobby intake)")
    private String serviceId;

    @Builder.Default
    @Schema(description = "QR generation mode: DYNAMIC (60s rotation) or STATIC (printed poster)", example = "DYNAMIC")
    private ReceptionQrMode qrMode = ReceptionQrMode.DYNAMIC;

    @Min(value = 15, message = "Chu kỳ xoay mã QR tối thiểu là 15 giây")
    @Builder.Default
    @Schema(description = "Dynamic QR rotation interval in seconds (default 60s per REQ-CHECK-IN-03)", example = "60")
    private Integer rotationSeconds = 60;
}
