package com.nextturn.dto.calendar;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.FutureOrPresent;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/**
 * Request payload for allocating a work shift on the calendar.
 * Supports single-day assignment as well as recurring multi-day scheduling.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Work shift creation request payload on tenant calendar")
public class CreateShiftRequest {

    @NotBlank(message = "Staff ID is mandatory")
    @Schema(description = "Staff UUID", example = "d3b07384-d113-4e89-8d7d-5e2634e9e030", requiredMode = Schema.RequiredMode.REQUIRED)
    private String staffId;

    @Schema(description = "Optional specific Service UUID assigned to this shift. If omitted and staff has 1 service, it defaults to that service.", example = "8c7a65b4-d5e6-4f7a-8b9c-0d1e2f3a4b5c")
    private String serviceId;

    @NotNull(message = "Work date is mandatory")
    @FutureOrPresent(message = "Work date must be today or in the future")
    @JsonFormat(pattern = "yyyy-MM-dd")
    @Schema(description = "Scheduled work date (YYYY-MM-DD)", example = "2026-11-05", requiredMode = Schema.RequiredMode.REQUIRED)
    private LocalDate workDate;

    @NotNull(message = "Shift start time is mandatory")
    @JsonFormat(pattern = "HH:mm[:ss]")
    @Schema(description = "Shift start time (HH:mm:ss)", example = "08:00:00", requiredMode = Schema.RequiredMode.REQUIRED)
    private LocalTime shiftStart;

    @NotNull(message = "Shift end time is mandatory")
    @JsonFormat(pattern = "HH:mm[:ss]")
    @Schema(description = "Shift end time (HH:mm:ss)", example = "17:00:00", requiredMode = Schema.RequiredMode.REQUIRED)
    private LocalTime shiftEnd;

    @FutureOrPresent(message = "Recurring end date must be today or in the future")
    @JsonFormat(pattern = "yyyy-MM-dd")
    @Schema(description = "Optional recurring end date (YYYY-MM-DD) for generating shifts over a date range", example = "2026-11-12")
    private LocalDate repeatUntil;

    @Schema(description = "Optional days of week filter for recurring generation (e.g. ['MONDAY', 'WEDNESDAY', 'FRIDAY'])")
    private List<DayOfWeek> repeatDays;
}

