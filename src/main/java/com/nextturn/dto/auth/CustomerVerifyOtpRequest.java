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
 * Request payload to verify an email OTP and authenticate as an END_USER.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Customer OTP verification payload")
public class CustomerVerifyOtpRequest {

    @NotBlank(message = "Email address must not be blank")
    @Email(message = "Email address must be a valid format")
    @Schema(description = "Customer's primary email address", example = "customer@example.com", requiredMode = Schema.RequiredMode.REQUIRED)
    private String email;

    @NotBlank(message = "Verification code must not be blank")
    @Pattern(regexp = "^\\d{6}$", message = "Verification code must be exactly 6 digits")
    @Schema(description = "6-digit OTP verification code", example = "123456", requiredMode = Schema.RequiredMode.REQUIRED)
    private String otp;
}
