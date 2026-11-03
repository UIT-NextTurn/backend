package com.nextturn.dto.service;

import com.nextturn.entity.TenantService;
import com.nextturn.enums.ServiceStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Standard DTO representation of a tenant service.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Service catalog response details")
public class ServiceResponse {

    @Schema(description = "Service unique identifier (UUID)", example = "a1b2c3d4-e5f6-7890-abcd-ef1234567890")
    private String id;

    @Schema(description = "Service code (SVC-xxxx)", example = "SVC-0001")
    private String serviceCode;

    @Schema(description = "Service display name", example = "Tư Vấn Trực Tiếp")
    private String serviceName;

    @Schema(description = "Standard duration in minutes (T_standard)", example = "15")
    private Integer standardDuration;

    @Schema(description = "Service unit price in VND", example = "150000.00")
    private BigDecimal price;

    @Schema(description = "Operational status (ACTIVE, INACTIVE)", example = "ACTIVE")
    private ServiceStatus status;

    @Schema(description = "Creation timestamp")
    private LocalDateTime createdAt;

    @Schema(description = "Last update timestamp")
    private LocalDateTime updatedAt;

    public static ServiceResponse fromEntity(TenantService entity) {
        if (entity == null) {
            return null;
        }
        return ServiceResponse.builder()
                .id(entity.getId())
                .serviceCode(entity.getServiceCode())
                .serviceName(entity.getServiceName())
                .standardDuration(entity.getStandardDuration())
                .price(entity.getPrice())
                .status(entity.getStatus())
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .build();
    }
}
