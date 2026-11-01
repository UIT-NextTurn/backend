package com.nextturn.dto.tenant;

import com.nextturn.entity.CentralTenant;
import com.nextturn.enums.TenantStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Detailed tenant monitoring and operational metrics response (REQ-TNT-04).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Tenant monitoring and resource telemetry summary")
public class TenantMonitoringResponse {

    @Schema(description = "Tenant internal ID", example = "018f0000-0000-7000-8000-000000000001")
    private String id;

    @Schema(description = "Unique tenant code", example = "pk-tam-duc")
    private String tenantCode;

    @Schema(description = "Organization name", example = "Phòng khám Đa khoa Tâm Đức")
    private String tenantName;

    @Schema(description = "Facility admin email", example = "admin@tamduc.vn")
    private String adminEmail;

    @Schema(description = "Contact phone number", example = "0987654321")
    private String phoneNumber;

    @Schema(description = "Lifecycle operational status", example = "ACTIVE")
    private TenantStatus status;

    @Schema(description = "Keycloak Organization UUID", example = "org-tam-duc")
    private String organisationId;

    @Schema(description = "Rejection reason if status is REJECTED", example = "Hồ sơ chưa đầy đủ thông tin")
    private String rejectionReason;

    @Schema(description = "Public widget API integration key", example = "nt_pub_123456")
    private String widgetApiKey;

    @Schema(description = "Actual active staff count in tenant schema", example = "8")
    private Long activeStaffCount;

    @Schema(description = "Number of queue tickets issued today", example = "42")
    private Long todayTicketsCount;

    @Schema(description = "Configured operational capacity quotas")
    private TenantQuotaResponse quotas;

    @Schema(description = "Tenant onboarding timestamp")
    private LocalDateTime createdAt;

    @Schema(description = "Last update timestamp")
    private LocalDateTime updatedAt;

    public static TenantMonitoringResponse fromEntity(
            CentralTenant tenant,
            TenantQuotaResponse quotas,
            Long activeStaffCount,
            Long todayTicketsCount
    ) {
        if (tenant == null) {
            return null;
        }
        return TenantMonitoringResponse.builder()
                .id(tenant.getId())
                .tenantCode(tenant.getTenantCode())
                .tenantName(tenant.getTenantName())
                .adminEmail(tenant.getAdminEmail())
                .phoneNumber(tenant.getPhoneNumber())
                .status(tenant.getStatus())
                .organisationId(tenant.getOrganisationId())
                .rejectionReason(tenant.getRejectionReason())
                .widgetApiKey(tenant.getWidgetApiKey())
                .activeStaffCount(activeStaffCount != null ? activeStaffCount : 0L)
                .todayTicketsCount(todayTicketsCount != null ? todayTicketsCount : 0L)
                .quotas(quotas)
                .createdAt(tenant.getCreatedAt())
                .updatedAt(tenant.getUpdatedAt())
                .build();
    }
}

