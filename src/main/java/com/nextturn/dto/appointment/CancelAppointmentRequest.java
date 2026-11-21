package com.nextturn.dto.appointment;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request payload for cancelling a confirmed appointment (REQ-APT-04).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Request payload to cancel an appointment (REQ-APT-04)")
public class CancelAppointmentRequest {

    @Schema(description = "Customer phone number for verification when cancelling via public lookup", example = "0901234567")
    private String customerPhone;

    @NotBlank(message = "Vui lòng nhập lý do hủy lịch hẹn")
    @Size(max = 500, message = "Lý do hủy lịch hẹn không được vượt quá 500 ký tự")
    @Schema(description = "Reason for cancelling the appointment", example = "Bận công tác đột xuất", requiredMode = Schema.RequiredMode.REQUIRED)
    private String cancellationReason;
}
