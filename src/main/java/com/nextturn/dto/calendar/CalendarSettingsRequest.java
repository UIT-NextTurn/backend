package com.nextturn.dto.calendar;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request payload for configuring facility-level advance booking rules and cancellation policies.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Tenant calendar booking settings update request")
public class CalendarSettingsRequest {

    @NotNull(message = "Minimum lead time hours is mandatory")
    @Min(value = 0, message = "Minimum lead time hours must be greater than or equal to 0")
    @Schema(description = "Minimum lead time in hours required before appointment start time", example = "2", requiredMode = Schema.RequiredMode.REQUIRED)
    private Integer minLeadTimeHours;

    @NotNull(message = "Maximum advance booking days is mandatory")
    @Min(value = 1, message = "Maximum advance booking days must be at least 1 day")
    @Schema(description = "Maximum days in advance that customers can book appointments", example = "30", requiredMode = Schema.RequiredMode.REQUIRED)
    private Integer maxAdvanceBookingDays;

    @NotNull(message = "Cancellation deadline hours is mandatory")
    @Min(value = 0, message = "Cancellation deadline hours must be greater than or equal to 0")
    @Schema(description = "Cut-off hours prior to appointment when self-cancellation is permitted", example = "12", requiredMode = Schema.RequiredMode.REQUIRED)
    private Integer cancellationDeadlineHours;
}

