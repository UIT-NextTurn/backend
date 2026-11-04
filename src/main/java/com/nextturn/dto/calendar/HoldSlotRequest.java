package com.nextturn.dto.calendar;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request payload for holding a booking time slot temporarily while completing customer details.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Request to hold a booking time slot temporarily")
public class HoldSlotRequest {

    @Min(value = 1, message = "Holding duration must be at least 1 minute")
    @Max(value = 60, message = "Holding duration cannot exceed 60 minutes")
    @Builder.Default
    @Schema(description = "Temporary reservation holding window in minutes (default: 10)", example = "10")
    private Integer holdingMinutes = 10;
}

