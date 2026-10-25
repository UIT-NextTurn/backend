package com.nextturn.dto.auth;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request payload to trigger an OTP for passwordless customer login.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Customer email OTP request payload")
public class CustomerSendOtpRequest {

    @NotBlank(message = "Email address must not be blank")
    @Email(message = "Email address must be a valid format")
    @Schema(description = "Customer's primary email address", example = "customer@example.com", requiredMode = Schema.RequiredMode.REQUIRED)
    private String email;
}
