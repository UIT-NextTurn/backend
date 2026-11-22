package com.nextturn.dto.checkin;

import com.nextturn.enums.ReceptionPointStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request payload for toggling reception point operational status (ACTIVE / INACTIVE).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Request payload to update reception point operational status")
public class UpdateReceptionPointStatusRequest {

    @NotNull(message = "Trạng thái điểm tiếp đón không được để trống")
    @Schema(description = "Target status (ACTIVE or INACTIVE)", example = "ACTIVE")
    private ReceptionPointStatus status;
}
