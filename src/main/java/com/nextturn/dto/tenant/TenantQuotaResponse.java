package com.nextturn.dto.tenant;

import com.nextturn.entity.CentralTenantQuota;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Tenant operational capacity quotas response DTO (REQ-TNT-02).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Tenant operational capacity quotas response")
public class TenantQuotaResponse {

    @Schema(description = "Tenant ID", example = "018f0000-0000-7000-8000-000000000001")
    private String tenantId;

    @Schema(description = "Maximum staff members allowed", example = "10")
    private Integer maxStaffs;

    @Schema(description = "Current active staff count", example = "3")
    private Long currentActiveStaff;

    @Schema(description = "Daily ticket quota (-1 for unlimited)", example = "500")
    private Integer dailyTicketQuota;

    @Schema(description = "Maximum service categories (-1 for unlimited)", example = "30")
    private Integer maxServices;

    @Schema(description = "Maximum appointments quota (-1 for unlimited)", example = "1000")
    private Integer maxAppointments;

    @Schema(description = "Last update timestamp")
    private LocalDateTime updatedAt;

    public static TenantQuotaResponse fromEntity(CentralTenantQuota quota, Long currentActiveStaff) {
        if (quota == null) {
            return null;
        }
        return TenantQuotaResponse.builder()
                .tenantId(quota.getTenantId())
                .maxStaffs(quota.getMaxStaffs())
                .currentActiveStaff(currentActiveStaff != null ? currentActiveStaff : 0L)
                .dailyTicketQuota(quota.getDailyTicketQuota())
                .maxServices(quota.getMaxServices())
                .maxAppointments(quota.getMaxAppointments())
                .updatedAt(quota.getUpdatedAt())
                .build();
    }
}

