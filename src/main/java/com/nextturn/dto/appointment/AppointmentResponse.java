package com.nextturn.dto.appointment;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.nextturn.entity.TenantAppointment;
import com.nextturn.entity.TenantService;
import com.nextturn.entity.TenantTimeSlot;
import com.nextturn.enums.AppointmentStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * Response DTO representing a customer appointment booking and its modification eligibility (REQ-APT-03, REQ-APT-04, REQ-APT-05).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "Detailed appointment response including service, slot, QR check-in payload, and modification flags")
public class AppointmentResponse {

    private String id;
    private String appointmentCode;
    private String tenantCode;
    private String tenantName;
    private String userId;

    private String serviceId;
    private String serviceCode;
    private String serviceName;

    private String slotId;
    private LocalDate workDate;
    private LocalTime slotStart;
    private LocalTime slotEnd;

    private String customerName;
    private String customerPhone;
    private String customerEmail;
    private String notes;
    private String cancellationReason;

    private String qrToken;
    private AppointmentStatus status;
    private Boolean canModify;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public static AppointmentResponse fromEntity(
            TenantAppointment entity,
            String tenantCode,
            String tenantName,
            boolean canModify
    ) {
        if (entity == null) {
            return null;
        }
        TenantService service = entity.getService();
        TenantTimeSlot slot = entity.getSlot();

        return AppointmentResponse.builder()
                .id(entity.getId())
                .appointmentCode(entity.getAppointmentCode())
                .tenantCode(tenantCode)
                .tenantName(tenantName)
                .userId(entity.getUserId())
                .serviceId(service != null ? service.getId() : null)
                .serviceCode(service != null ? service.getServiceCode() : null)
                .serviceName(service != null ? service.getServiceName() : null)
                .slotId(slot != null ? slot.getId() : null)
                .workDate(slot != null ? slot.getWorkDate() : null)
                .slotStart(slot != null ? slot.getSlotStart() : null)
                .slotEnd(slot != null ? slot.getSlotEnd() : null)
                .customerName(entity.getCustomerName())
                .customerPhone(entity.getCustomerPhone())
                .customerEmail(entity.getCustomerEmail())
                .notes(entity.getNotes())
                .cancellationReason(entity.getCancellationReason())
                .qrToken(entity.getQrToken())
                .status(entity.getStatus())
                .canModify(canModify)
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .build();
    }
}
