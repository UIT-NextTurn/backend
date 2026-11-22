package com.nextturn.dto.checkin;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request payload for pre-booked appointment QR check-in (REQ-CHECK-IN-01).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Request payload to check in an existing confirmed appointment")
public class AppointmentCheckInRequest {

    @Schema(description = "Appointment QR token scanned from confirmation email/ticket")
    private String qrToken;

    @Schema(description = "Appointment UUID or appointment code (alternative to qrToken)", example = "APT-20261122-0001")
    private String appointmentId;

    @Schema(description = "Optional dynamic/static reception point QR token scanned at the lobby")
    private String receptionQrToken;
}
