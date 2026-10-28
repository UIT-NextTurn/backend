package com.nextturn.dto.auth;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Successful authentication token response payload containing JWT tokens, verified roles,
 * and routing targets for the frontend application.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AuthTokenResponse {

    @Schema(description = "JWT Access Token for Bearer authentication")
    private String accessToken;

    @Schema(description = "Keycloak Refresh Token")
    private String refreshToken;

    @Schema(description = "ID Token if requested")
    private String idToken;

    @Schema(description = "Token type", example = "Bearer")
    @Builder.Default
    private String tokenType = "Bearer";

    @Schema(description = "Access token expiry in seconds", example = "300")
    private Long expiresIn;

    @Schema(description = "Refresh token expiry in seconds", example = "1800")
    private Long refreshExpiresIn;

    @Schema(description = "Keycloak user unique identifier (sub UUID)")
    private String userId;

    @Schema(description = "Authenticated user email", example = "admin@clinic.vn")
    private String email;

    @Schema(description = "List of verified application client roles (SUPERADMIN, TENANT_ADMIN, COUNTER_STAFF, END_USER)")
    private List<String> roles;

    @Schema(description = "Highest precedence role (SUPERADMIN > TENANT_ADMIN > COUNTER_STAFF > END_USER)", example = "TENANT_ADMIN")
    private String primaryRole;

    @Schema(description = "Tenant identifier / code if user is affiliated with a facility", example = "clinic_sai_gon")
    private String tenantCode;

    @Schema(description = "Target UI dashboard path for the frontend router", example = "/tenant/dashboard")
    private String targetDashboardUrl;
}
