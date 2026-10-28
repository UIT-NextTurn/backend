package com.nextturn.dto.auth;

import com.nextturn.enums.StaffRole;
import com.nextturn.enums.StaffStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Pattern;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Payload submitted by a TENANT_ADMIN to update an existing staff member's profile or status.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Staff update payload")
public class UpdateStaffRequest {

    @Schema(description = "Updated full name of the staff member", example = "Dr. Nguyen Van A")
    private String fullName;

    @Pattern(regexp = "^[0-9+()\\s-]{8,20}$", message = "Phone number must be a valid format (8-20 characters)")
    @Schema(description = "Updated contact phone number", example = "+84909876543")
    private String phoneNumber;

    @Schema(description = "Updated operational role", example = "SPECIALIST")
    private StaffRole role;

    @Schema(description = "Active or inactive status. Setting to INACTIVE suspends login in Keycloak.", example = "ACTIVE")
    private StaffStatus status;
}
