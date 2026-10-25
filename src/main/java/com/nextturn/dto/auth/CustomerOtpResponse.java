package com.nextturn.dto.auth;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Response payload following an OTP dispatch.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Customer OTP generation response")
public class CustomerOtpResponse {

    @Schema(description = "Recipient email address", example = "customer@example.com")
    private String email;

    @Schema(description = "OTP expiration time in seconds", example = "300")
    private long expiresInSeconds;

    @Schema(description = "Status message", example = "OTP code has been sent to your email")
    private String message;

    @Schema(description = "Indicates whether this email belongs to an existing user with booking history", example = "true")
    private boolean existingUser;
}
