package com.nextturn.dto.dashboard;

import com.nextturn.enums.QuotaAlertLevel;
import com.nextturn.enums.QuotaMonitorFilter;
import com.nextturn.enums.TenantStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Superadmin tenant resource consumption and quota alert list (REQ-DASHBOARD-06).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Tenant quota consumption monitoring and alerts")
public class TenantQuotaMonitoringResponse {

    private LocalDateTime generatedAt;
    private QuotaMonitorFilter filter;
    private long totalTenants;
    private long warningCount;
    private long criticalCount;
    private List<TenantQuotaUsageRow> tenants;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TenantQuotaUsageRow {
        private String tenantId;
        private String tenantCode;
        private String tenantName;
        private TenantStatus status;

        private long dailyTicketsUsed;
        private int dailyTicketQuota;
        private double ticketUsagePercentage;

        private long activeStaffCount;
        private int maxStaffQuota;
        private double staffUsagePercentage;

        private long activeServicesCount;
        private int maxServicesQuota;

        private boolean quotaExceeded;
        private QuotaAlertLevel alertLevel;
    }
}
