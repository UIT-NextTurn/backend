package com.nextturn.dto.calendar;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request payload for manually releasing a previously held time slot.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Request to manually release a held time slot")
public class ReleaseSlotRequest {

    @NotBlank(message = "Holding token is mandatory")
    @Schema(description = "Secure holding token issued during hold acquisition", example = "3fa85f64-5717-4562-b3fc-2c963f66afa6", requiredMode = Schema.RequiredMode.REQUIRED)
    private String holdingToken;
}

