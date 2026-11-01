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
 * Detailed tenant profile and quota response (REQ-TNT-01, REQ-TNT-02).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Tenant detail response")
public class TenantResponse {

    @Schema(description = "Unique tenant ID", example = "018f0000-0000-7000-8000-000000000001")
    private String id;

    @Schema(description = "Facility tenant code", example = "bv-tam-duc")
    private String tenantCode;

    @Schema(description = "Facility organization name", example = "Bệnh viện Đa khoa Tâm Đức")
    private String tenantName;

    @Schema(description = "Administrator email address", example = "admin@tamduc.vn")
    private String adminEmail;

    @Schema(description = "Contact phone number", example = "0987654321")
    private String phoneNumber;

    @Schema(description = "Tenant lifecycle status", example = "ACTIVE")
    private TenantStatus status;

    @Schema(description = "Keycloak Organization ID", example = "org-bv-tam-duc")
    private String organisationId;

    @Schema(description = "Public widget API integration key", example = "nt_pub_a8b9c0d1e2")
    private String widgetApiKey;

    @Schema(description = "Operational capacity quotas")
    private TenantQuotaResponse quotas;

    @Schema(description = "Creation timestamp")
    private LocalDateTime createdAt;

    @Schema(description = "Last update timestamp")
    private LocalDateTime updatedAt;

    public static TenantResponse fromEntity(CentralTenant tenant, TenantQuotaResponse quotas) {
        if (tenant == null) {
            return null;
        }
        return TenantResponse.builder()
                .id(tenant.getId())
                .tenantCode(tenant.getTenantCode())
                .tenantName(tenant.getTenantName())
                .adminEmail(tenant.getAdminEmail())
                .phoneNumber(tenant.getPhoneNumber())
                .status(tenant.getStatus())
                .organisationId(tenant.getOrganisationId())
                .widgetApiKey(tenant.getWidgetApiKey())
                .quotas(quotas)
                .createdAt(tenant.getCreatedAt())
                .updatedAt(tenant.getUpdatedAt())
                .build();
    }
}

