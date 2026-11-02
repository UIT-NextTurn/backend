package com.nextturn.dto.auth;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Pattern;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request payload to trigger an OTP for passwordless customer login.
 * Supports both standalone web login and embedded widget integration (REQ-AUTH-06).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Customer OTP dispatch request payload for web and embedded widget")
public class CustomerSendOtpRequest {

    @Email(message = "Email address must be a valid format")
    @Schema(description = "Customer's primary email address", example = "customer@example.com")
    private String email;

    @Pattern(regexp = "^\\+?[0-9]{9,15}$", message = "Phone number must be a valid format (9-15 digits)")
    @Schema(description = "Customer's phone number", example = "+84912345678")
    private String phoneNumber;

    @Schema(description = "Public integration key of the tenant facility attached in the embedded widget (REQ-AUTH-06)", example = "wtk_live_a1b2c3d4e5")
    private String widgetApiKey;

    @Schema(description = "Optional origin domain of the integrating website", example = "booking.myclinic.vn")
    private String originDomain;

    /**
     * Resolves the effective normalized contact identifier (email or phone).
     *
     * @return normalized contact string or null if both are missing
     */
    public String resolveContact() {
        if (email != null && !email.isBlank()) {
            return email.trim().toLowerCase();
        }
        if (phoneNumber != null && !phoneNumber.isBlank()) {
            return phoneNumber.trim();
        }
        return null;
    }
}
