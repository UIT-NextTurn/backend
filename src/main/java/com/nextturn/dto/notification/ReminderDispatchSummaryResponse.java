package com.nextturn.dto.notification;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Summary counts returned after an appointment reminder sweep (REQ-NOTI-01).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Summary of 24-hour and 1-hour appointment reminders processed in a sweep")
public class ReminderDispatchSummaryResponse {

    private int reminders24hSent;
    private int reminders1hSent;
    private int failedCount;
}
