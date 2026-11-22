package com.nextturn.dto.checkin;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.nextturn.entity.TenantReceptionPoint;
import com.nextturn.entity.TenantService;
import com.nextturn.enums.ReceptionPointStatus;
import com.nextturn.enums.ReceptionQrMode;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Response DTO representing a configured reception intake point (REQ-CHECK-IN-03).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "Reception point summary response")
public class ReceptionPointResponse {

    private String id;
    private String pointCode;
    private String pointName;
    private String locationLabel;
    private String serviceId;
    private String serviceCode;
    private String serviceName;
    private ReceptionQrMode qrMode;
    private Integer rotationSeconds;
    private ReceptionPointStatus status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public static ReceptionPointResponse fromEntity(TenantReceptionPoint point) {
        if (point == null) {
            return null;
        }
        TenantService service = point.getService();
        return ReceptionPointResponse.builder()
                .id(point.getId())
                .pointCode(point.getPointCode())
                .pointName(point.getPointName())
                .locationLabel(point.getLocationLabel())
                .serviceId(service != null ? service.getId() : null)
                .serviceCode(service != null ? service.getServiceCode() : null)
                .serviceName(service != null ? service.getServiceName() : null)
                .qrMode(point.getQrMode())
                .rotationSeconds(point.getRotationSeconds())
                .status(point.getStatus())
                .createdAt(point.getCreatedAt())
                .updatedAt(point.getUpdatedAt())
                .build();
    }
}
