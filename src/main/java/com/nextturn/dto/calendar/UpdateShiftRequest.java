package com.nextturn.dto.calendar;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalTime;

/**
 * Request payload for adjusting the operational time window of an existing scheduled work shift.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Work shift adjustment request payload")
public class UpdateShiftRequest {

    @NotNull(message = "Shift start time is mandatory")
    @JsonFormat(pattern = "HH:mm[:ss]")
    @Schema(description = "Adjusted shift start time (HH:mm:ss)", example = "09:00:00", requiredMode = Schema.RequiredMode.REQUIRED)
    private LocalTime shiftStart;

    @NotNull(message = "Shift end time is mandatory")
    @JsonFormat(pattern = "HH:mm[:ss]")
    @Schema(description = "Adjusted shift end time (HH:mm:ss)", example = "16:00:00", requiredMode = Schema.RequiredMode.REQUIRED)
    private LocalTime shiftEnd;
}

