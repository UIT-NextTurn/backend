package com.nextturn.dto.appointment;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request payload for rescheduling a confirmed appointment to a new time slot (REQ-APT-04).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Request payload to reschedule an appointment to a new time slot (REQ-APT-04)")
public class RescheduleAppointmentRequest {

    @Schema(description = "Customer phone number for verification when rescheduling via public lookup", example = "0901234567")
    private String customerPhone;

    @NotBlank(message = "Mã khung giờ mới (new_slot_id) là bắt buộc")
    @Schema(description = "Target new time slot UUID", requiredMode = Schema.RequiredMode.REQUIRED)
    private String newSlotId;

    @Schema(description = "Optional holding session token if the new slot was temporarily held prior to rescheduling")
    private String holdingToken;
}
