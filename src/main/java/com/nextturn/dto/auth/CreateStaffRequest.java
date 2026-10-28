package com.nextturn.dto.auth;

import com.nextturn.enums.StaffRole;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Payload submitted by a TENANT_ADMIN to provision a healthcare encounter staff member.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Staff onboarding payload for counter staff and specialists")
public class CreateStaffRequest {

    @NotBlank(message = "Staff full name is required")
    @Schema(description = "Full legal name of the staff member", example = "Dr. Nguyen Van A")
    private String fullName;

    @NotBlank(message = "Staff email address is required")
    @Email(message = "Email must be a valid email address")
    @Schema(description = "Corporate or staff email address used for login", example = "staff.nguyen@clinic-saigon.vn")
    private String email;

    @NotBlank(message = "Phone number is required")
    @Pattern(regexp = "^[0-9+()\\s-]{8,20}$", message = "Phone number must be a valid format (8-20 characters)")
    @Schema(description = "Contact phone number", example = "+84901234567")
    private String phoneNumber;

    @Builder.Default
    @Schema(description = "Operational role within the clinic", example = "COUNTER", allowableValues = {"COUNTER", "SPECIALIST", "ADMIN"})
    private StaffRole role = StaffRole.COUNTER;

    @Schema(description = "Optional initial temporary password. If omitted, Keycloak triggers an email invite with password setup.", example = "TempP@ssw0rd123!")
    private String initialPassword;
}
