package com.nextturn.dto.auth;

import com.nextturn.entity.TenantStaff;
import com.nextturn.enums.StaffRole;
import com.nextturn.enums.StaffStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Detailed staff member response returned to TENANT_ADMIN.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Staff member profile details")
public class StaffResponse {

    @Schema(description = "Staff unique identifier in tenant database", example = "550e8400-e29b-41d4-a716-446655440000")
    private String id;

    @Schema(description = "Unique staff badge or identification code", example = "STF-1001")
    private String staffCode;

    @Schema(description = "Keycloak internal user UUID", example = "9b1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d")
    private String keycloakUserId;

    @Schema(description = "Full legal name", example = "Dr. Nguyen Van A")
    private String fullName;

    @Schema(description = "Staff email address", example = "staff.nguyen@clinic-saigon.vn")
    private String email;

    @Schema(description = "Contact phone number", example = "+84901234567")
    private String phoneNumber;

    @Schema(description = "Operational role", example = "COUNTER")
    private StaffRole role;

    @Schema(description = "Account status", example = "ACTIVE")
    private StaffStatus status;

    @Schema(description = "Creation timestamp")
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
}
