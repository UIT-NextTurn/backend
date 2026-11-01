package com.nextturn.dto.tenant;

import com.nextturn.enums.TenantStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request payload for tenant lifecycle state transitions (REQ-TNT-03).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Request to update tenant lifecycle status")
public class UpdateTenantStatusRequest {

    @NotNull(message = "Trạng thái tổ chức không được để trống")
    @Schema(description = "Target tenant lifecycle status (ACTIVE or SUSPENDED)", example = "SUSPENDED")
    private TenantStatus status;
}

