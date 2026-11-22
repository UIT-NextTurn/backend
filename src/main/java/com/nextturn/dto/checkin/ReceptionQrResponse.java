package com.nextturn.dto.checkin;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.nextturn.enums.ReceptionQrMode;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Response DTO containing an encoded reception QR token, deep-link URL, and 60s rotation metadata (REQ-CHECK-IN-03).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "Generated reception QR payload with rotation metadata")
public class ReceptionQrResponse {

    private String receptionPointId;
    private String pointCode;
    private String pointName;
    private String locationLabel;
    private String tenantCode;

    private String serviceId;
    private String serviceCode;
    private String serviceName;

    private ReceptionQrMode qrMode;
    private String qrToken;
    private String checkInUrl;

    @Schema(description = "Dynamic rotation period in seconds (60s for DYNAMIC mode)", example = "60")
    private Integer rotationSeconds;

    private Instant issuedAt;
    private Instant expiresAt;

    @Schema(description = "Seconds remaining until this dynamic QR code expires", example = "42")
    private Long remainingSeconds;
}
