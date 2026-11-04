package com.nextturn.dto.staff;

import com.nextturn.dto.service.ServiceResponse;
import com.nextturn.entity.TenantStaff;
import com.nextturn.enums.StaffRole;
import com.nextturn.enums.StaffStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Standard DTO representation of an encounter staff member.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Staff member profile response")
public class StaffResponse {

    @Schema(description = "Staff UUID", example = "d3b07384-d113-4e89-8d7d-5e2634e9e030")
    private String id;

    @Schema(description = "Unique staff badge or identification code", example = "STAFF-0001")
    private String staffCode;

    @Schema(description = "Linked Keycloak User UUID", example = "e0436d64-e1e3-47e9-a41f-13fc13b4c95a")
    private String keycloakUserId;

    @Schema(description = "Full legal name", example = "Nguyễn Văn A")
    private String fullName;

    @Schema(description = "Corporate or staff email address", example = "staff.a@tenant.com")
    private String email;

    @Schema(description = "Contact phone number", example = "0912345678")
    private String phoneNumber;

    @Schema(description = "Assigned operational role", example = "COUNTER")
    private StaffRole role;

    @Schema(description = "Operational status", example = "ACTIVE")
    private StaffStatus status;

    @Schema(description = "Services assigned to this staff member")
    private List<ServiceResponse> assignedServices;

    @Schema(description = "Profile creation timestamp")
    private LocalDateTime createdAt;

    @Schema(description = "Last update timestamp")
    private LocalDateTime updatedAt;

    public static StaffResponse fromEntity(TenantStaff staff) {
        if (staff == null) {
            return null;
        }
        return StaffResponse.builder()
                .id(staff.getId())
                .staffCode(staff.getStaffCode())
                .keycloakUserId(staff.getKeycloakUserId())
                .fullName(staff.getFullName())
                .email(staff.getEmail())
                .phoneNumber(staff.getPhoneNumber())
                .role(staff.getRole())
                .status(staff.getStatus())
                .createdAt(staff.getCreatedAt())
                .updatedAt(staff.getUpdatedAt())
                .build();
    }

    public static StaffResponse fromEntity(TenantStaff staff, List<ServiceResponse> assignedServices) {
        StaffResponse response = fromEntity(staff);
        if (response != null) {
            response.setAssignedServices(assignedServices);
        }
        return response;
    }
}

