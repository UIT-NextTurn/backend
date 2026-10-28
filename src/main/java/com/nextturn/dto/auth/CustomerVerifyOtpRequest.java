package com.nextturn.dto.auth;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request payload to verify an email/phone OTP and authenticate as an END_USER on web or embedded widget (REQ-AUTH-07).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Customer OTP verification payload")
public class CustomerVerifyOtpRequest {

    @Email(message = "Email address must be a valid format")
    @Schema(description = "Customer's primary email address", example = "customer@example.com")
    private String email;

    @Pattern(regexp = "^\\+?[0-9]{9,15}$", message = "Phone number must be a valid format (9-15 digits)")
    @Schema(description = "Customer's phone number", example = "+84912345678")
    private String phoneNumber;

    @NotBlank(message = "Verification code must not be blank")
    @Pattern(regexp = "^\\d{6}$", message = "Verification code must be exactly 6 digits")
    @Schema(description = "6-digit OTP verification code", example = "123456", requiredMode = Schema.RequiredMode.REQUIRED)
    private String otp;

    /**
     * Resolves the effective contact identifier (email or phone).
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
