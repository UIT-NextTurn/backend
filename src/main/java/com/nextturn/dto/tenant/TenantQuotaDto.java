package com.nextturn.dto.tenant;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Operational capacity quota limits DTO (REQ-TNT-02).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Operational capacity quota limits for a tenant facility")
public class TenantQuotaDto {

    @Schema(description = "Maximum active staff members allowed (>= 1)", example = "10")
    @Min(value = 1, message = "Số lượng nhân viên tối đa phải là số nguyên dương >= 1")
    private Integer maxStaffs;

    @Schema(description = "Daily ticket quota (>= 1 or -1 for unlimited)", example = "500")
    private Integer dailyTicketQuota;

    @Schema(description = "Maximum service categories allowed (>= 1 or -1 for unlimited)", example = "30")
    private Integer maxServices;

    @Schema(description = "Maximum future appointments allowed (>= 1 or -1 for unlimited)", example = "1000")
    private Integer maxAppointments;
}

