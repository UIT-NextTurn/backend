package com.nextturn.dto.auth;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Response payload following an OTP dispatch.
 * Includes expiration metadata and widget 60-second resend countdown (REQ-AUTH-06).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Customer OTP generation response")
public class CustomerOtpResponse {

    @Schema(description = "Recipient email address or contact info", example = "customer@example.com")
    private String email;

    @Schema(description = "Normalized contact string (email or phone)", example = "customer@example.com")
    private String contact;

    @Schema(description = "OTP expiration time in seconds (5 minutes)", example = "300")
    private long expiresInSeconds;

    @Schema(description = "Mandatory waiting cooldown period in seconds before requesting a new code (REQ-AUTH-06)", example = "60")
    @Builder.Default
    private long resendCooldownSeconds = 60L;

    @Schema(description = "Status message", example = "Verification code has been dispatched to your email")
    private String message;

    @Schema(description = "Indicates whether this contact belongs to an existing user with booking history", example = "true")
    private boolean existingUser;
}
