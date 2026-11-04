package com.nextturn.dto.calendar;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.nextturn.enums.TimeSlotStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Response payload returned after successfully acquiring a temporary hold on a time slot.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Response containing temporary time slot holding lease")
public class SlotHoldingResponse {

    @Schema(description = "Time slot UUID", example = "6ba7b810-9dad-11d1-80b4-00c04fd430c8")
    private String slotId;

    @Schema(description = "Secure holding token required to confirm or release booking", example = "3fa85f64-5717-4562-b3fc-2c963f66afa6")
    private String holdingToken;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    @Schema(description = "Expiration timestamp when holding lease expires automatically", example = "2026-11-05 08:40:00")
    private LocalDateTime holdingExpiresAt;

    @Schema(description = "Current slot status (HOLDING)", example = "HOLDING")
    private TimeSlotStatus status;

    @Schema(description = "Informative status message", example = "Khung giờ đã được giữ chỗ tạm thời trong 10 phút")
    private String message;
}

