package com.nextturn.dto.calendar;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.nextturn.entity.TenantSettings;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Standard DTO response for facility-level calendar booking policies.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Tenant calendar booking policies response")
public class CalendarSettingsResponse {

    @Schema(description = "Settings unique identifier", example = "tenant-settings-default")
    private String id;

    @Schema(description = "Minimum lead time hours required before appointment start time", example = "2")
    private Integer minLeadTimeHours;

    @Schema(description = "Maximum days in advance that customers can book appointments", example = "30")
    private Integer maxAdvanceBookingDays;

    @Schema(description = "Cut-off hours prior to appointment when self-cancellation is permitted", example = "12")
    private Integer cancellationDeadlineHours;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    @Schema(description = "Last settings modification timestamp")
    private LocalDateTime updatedAt;

    public static CalendarSettingsResponse fromEntity(TenantSettings settings) {
        if (settings == null) {
            return null;
        }
        return CalendarSettingsResponse.builder()
                .id(settings.getId())
                .minLeadTimeHours(settings.getMinLeadTimeHours())
                .maxAdvanceBookingDays(settings.getMaxAdvanceBookingDays())
                .cancellationDeadlineHours(settings.getCancellationDeadlineHours())
                .updatedAt(settings.getUpdatedAt())
                .build();
    }
}

