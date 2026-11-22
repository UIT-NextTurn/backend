package com.nextturn.dto.checkin;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.nextturn.dto.service.ServiceResponse;
import com.nextturn.enums.ReceptionQrMode;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Response DTO returned when a customer scans a lobby/counter reception QR code (REQ-CHECK-IN-01/02/03).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "Resolved reception context after scanning a lobby or counter QR code")
public class ResolveReceptionQrResponse {

    private String tenantCode;
    private String tenantName;

    private String receptionPointId;
    private String pointCode;
    private String pointName;
    private String locationLabel;
    private ReceptionQrMode qrMode;

    @Schema(description = "Bound service if this QR belongs to a specific service counter; null for general lobby intake")
    private ServiceResponse scopedService;

    @Schema(description = "Active services available for walk-in registration at this reception point")
    private List<ServiceResponse> availableServices;

    @Builder.Default
    private Boolean supportsAppointmentCheckIn = true;

    @Builder.Default
    private Boolean supportsWalkIn = true;
}
