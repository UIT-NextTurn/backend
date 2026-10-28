package com.nextturn.dto.auth;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Payload for exchanging an OAuth2 authorization code with Keycloak.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TokenExchangeRequest {

    @NotBlank(message = "Authorization code must not be blank")
    @Schema(description = "OAuth2 authorization code returned by Keycloak", requiredMode = Schema.RequiredMode.REQUIRED, example = "bfa95f32-7208-410c-9304-4e2b0051187d")
    private String code;

    @Schema(description = "Redirect URI matching the initial authorization request. If omitted, uses default-redirect-uri", example = "http://localhost:3000/auth/callback")
    private String redirectUri;
}
