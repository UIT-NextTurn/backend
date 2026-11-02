package com.nextturn.dto.auth;

import com.fasterxml.jackson.annotation.JsonAlias;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Request payload for user logout and OAuth2 refresh token revocation (*thu hồi mã thông báo*).
 */
public record LogoutRequest(
        @Schema(
                description = "OAuth2 refresh token to revoke in Keycloak IAM",
                example = "eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9..."
        )
        @JsonAlias({"refresh_token", "refreshToken"})
        String refreshToken
) {}
