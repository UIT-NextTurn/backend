package com.nextturn.dto.queue;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request payload for Counter Staff clicking "Call Next Ticket" (REQ-QC-01).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Request payload to call the highest-priority waiting ticket for a service")
public class CallNextTicketRequest {

    @NotBlank(message = "Mã dịch vụ không được để trống")
    @Schema(description = "Target service UUID to pull the next priority ticket from", requiredMode = Schema.RequiredMode.REQUIRED)
    private String serviceId;

    @Schema(description = "Optional counter staff UUID override (useful for Tenant Admin delegation; defaults to authenticated staff)")
    private String counterStaffId;

    @Size(max = 50, message = "Tên/Số quầy hiển thị không được vượt quá 50 ký tự")
    @Schema(description = "Counter number or desk label shown on the lobby display (e.g. 'Quầy 01')", example = "Quầy 01")
    private String counterLabel;
}
