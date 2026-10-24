package com.nextturn.infrastructure.security.keycloak;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nextturn.dto.AuthTokenResponse;
import com.nextturn.enums.UserRole;
import com.nextturn.exception.auth.AuthErrorCode;
import com.nextturn.exception.auth.AuthException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * Service orchestrating OAuth2 token lifecycle with Keycloak IAM:
 * - Authorization code token exchange
 * - Direct access grant (username & password) login
 * - Refresh token renewal
 * - Token claim decoding and client role verification
 */
@Service
public class KeycloakTokenService {

    private static final Logger log = LoggerFactory.getLogger(KeycloakTokenService.class);

    private final KeycloakProperties properties;
    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    @org.springframework.beans.factory.annotation.Autowired
    public KeycloakTokenService(
            KeycloakProperties properties,
            RestClient.Builder restClientBuilder,
            ObjectMapper objectMapper
    ) {
        this.properties = properties;
        this.restClient = (restClientBuilder != null ? restClientBuilder : RestClient.builder()).build();
        this.objectMapper = objectMapper;
    }

    public KeycloakTokenService(KeycloakProperties properties, ObjectMapper objectMapper) {
        this(properties, RestClient.builder(), objectMapper);
    }

    /**
     * Exchanges an authorization code for JWT tokens via Keycloak token endpoint.
     */
    public AuthTokenResponse exchangeAuthorizationCode(String code, String redirectUri) {
        MultiValueMap<String, String> formData = new LinkedMultiValueMap<>();
        formData.add("grant_type", "authorization_code");
        formData.add("client_id", properties.clientId());
        if (properties.clientSecret() != null && !properties.clientSecret().isBlank()) {
            formData.add("client_secret", properties.clientSecret());
        }
        formData.add("code", code);
        formData.add("redirect_uri", redirectUri);

        return executeTokenRequest(formData);
    }


    /**
     * Refreshes expired access tokens using an active refresh token.
     */
    public AuthTokenResponse refreshAccessToken(String refreshToken) {
        MultiValueMap<String, String> formData = new LinkedMultiValueMap<>();
        formData.add("grant_type", "refresh_token");
        formData.add("client_id", properties.clientId());
        if (properties.clientSecret() != null && !properties.clientSecret().isBlank()) {
            formData.add("client_secret", properties.clientSecret());
        }
        formData.add("refresh_token", refreshToken);

        return executeTokenRequest(formData);
    }

    /**
     * Posts form data to Keycloak /protocol/openid-connect/token and parses response.
     */
    private AuthTokenResponse executeTokenRequest(MultiValueMap<String, String> formData) {
        String tokenUrl = properties.tokenEndpoint();

        try {
            String responseBody = restClient.post()
                    .uri(tokenUrl)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(formData)
                    .retrieve()
                    .body(String.class);

            if (responseBody == null || responseBody.isBlank()) {
                throw new AuthException(AuthErrorCode.INVALID_TOKEN, "Empty token response received from Keycloak");
            }

            JsonNode tokenJson = objectMapper.readTree(responseBody);
            String accessToken = tokenJson.path("access_token").asText(null);
            String refreshToken = tokenJson.path("refresh_token").asText(null);
            String idToken = tokenJson.path("id_token").asText(null);
            Long expiresIn = tokenJson.has("expires_in") ? tokenJson.path("expires_in").asLong() : null;
            Long refreshExpiresIn = tokenJson.has("refresh_expires_in") ? tokenJson.path("refresh_expires_in").asLong() : null;
            String tokenType = tokenJson.path("token_type").asText("Bearer");

            if (accessToken == null || accessToken.isBlank()) {
                throw new AuthException(AuthErrorCode.INVALID_TOKEN, "Access token missing in Keycloak response");
            }

            return parseAndValidateAccessToken(
                    accessToken,
                    refreshToken,
                    idToken,
                    expiresIn,
                    refreshExpiresIn,
                    tokenType
            );
        } catch (RestClientResponseException ex) {
            String errorDetails = extractErrorDescription(ex.getResponseBodyAsString());
            log.warn("Keycloak token request failed with status [{}]: {}", ex.getStatusCode(), errorDetails != null ? errorDetails : ex.getResponseBodyAsString());
            if (ex.getStatusCode().value() == 400 || ex.getStatusCode().value() == 401) {
                String message = errorDetails != null && !errorDetails.isBlank()
                        ? errorDetails
                        : "Invalid credentials or authorization code";
                throw new AuthException(AuthErrorCode.INVALID_CREDENTIALS, message);
            }
            throw new AuthException(AuthErrorCode.KEYCLOAK_UNAVAILABLE, "Authentication provider service encountered an error");
        } catch (AuthException ex) {
            throw ex;
        } catch (Exception ex) {
            log.error("Failed to process Keycloak token communication", ex);
            throw new AuthException(AuthErrorCode.KEYCLOAK_UNAVAILABLE, "Unable to communicate with authentication provider");
        }
    }

    /**
     * Decodes the access token payload, extracts client roles and tenant context,
     * and strictly rejects END_USER roles.
     */
    public AuthTokenResponse parseAndValidateAccessToken(
            String accessToken,
            String refreshToken,
            String idToken,
            Long expiresIn,
            Long refreshExpiresIn,
            String tokenType
    ) {
        try {
            String[] parts = accessToken.split("\\.");
            if (parts.length < 2) {
                throw new AuthException(AuthErrorCode.INVALID_TOKEN, "Malformed JWT structure");
            }

            byte[] decoded = Base64.getUrlDecoder().decode(parts[1]);
            JsonNode payload = objectMapper.readTree(new String(decoded, StandardCharsets.UTF_8));

            String userId = payload.path("sub").asText(null);
            String email = payload.path("email").asText(null);
            String tenantCode = extractTenantCode(payload);

            List<String> roles = extractRoles(payload);

            // =========================================================================
            // Role Enforcement: Allow SUPERADMIN, TENANT_ADMIN, COUNTER_STAFF
            // Disallow END_USER from logging into staff/management portal
            // =========================================================================
            boolean hasSuperAdmin = roles.contains(UserRole.SUPERADMIN_VALUE);
            boolean hasTenantAdmin = roles.contains(UserRole.TENANT_ADMIN_VALUE);
            boolean hasCounterStaff = roles.contains(UserRole.COUNTER_STAFF_VALUE);

            if (!hasSuperAdmin && !hasTenantAdmin && !hasCounterStaff) {
                log.warn("Login rejected for user [{}] with non-management roles: {}", email != null ? email : userId, roles);
                throw new AuthException(
                        AuthErrorCode.ROLE_NOT_PERMITTED,
                        "End users (customers) are not permitted to log in via the management portal"
                );
            }

            String primaryRole;
            String targetDashboardUrl;

            if (hasSuperAdmin) {
                primaryRole = UserRole.SUPERADMIN_VALUE;
                targetDashboardUrl = "/admin/dashboard";
            } else if (hasTenantAdmin) {
                primaryRole = UserRole.TENANT_ADMIN_VALUE;
                targetDashboardUrl = "/tenant/dashboard";
            } else {
                primaryRole = UserRole.COUNTER_STAFF_VALUE;
                targetDashboardUrl = "/staff/counter";
            }

            return AuthTokenResponse.builder()
                    .accessToken(accessToken)
                    .refreshToken(refreshToken)
                    .idToken(idToken)
                    .expiresIn(expiresIn)
                    .refreshExpiresIn(refreshExpiresIn)
                    .tokenType(tokenType != null ? tokenType : "Bearer")
                    .userId(userId)
                    .email(email)
                    .roles(roles)
                    .primaryRole(primaryRole)
                    .tenantCode(tenantCode)
                    .targetDashboardUrl(targetDashboardUrl)
                    .build();
        } catch (AuthException ex) {
            throw ex;
        } catch (Exception ex) {
            log.error("Failed to decode and validate JWT payload", ex);
            throw new AuthException(AuthErrorCode.INVALID_TOKEN, "Failed to decode JWT claims");
        }
    }

    private List<String> extractRoles(JsonNode payload) {
        List<String> roles = new ArrayList<>();

        // 1. Client roles from resource_access (primary for NextTurn client roles)
        JsonNode resourceAccessNode = payload.path("resource_access");
        String targetClientId = properties.clientId() != null ? properties.clientId() : "nextturn-backend";
        extractRolesFromNode(resourceAccessNode.path(targetClientId).path("roles"), roles);

        String azp = payload.path("azp").asText(null);
        if (azp != null && !azp.equalsIgnoreCase(targetClientId)) {
            extractRolesFromNode(resourceAccessNode.path(azp).path("roles"), roles);
        }

        // 2. Realm roles as fallback
        extractRolesFromNode(payload.path("realm_access").path("roles"), roles);

        return roles;
    }

    private void extractRolesFromNode(JsonNode rolesNode, List<String> roles) {
        if (rolesNode.isArray()) {
            for (JsonNode r : rolesNode) {
                String role = r.asText().trim();
                if (role.startsWith("ROLE_")) {
                    role = role.substring(5);
                }
                if (!role.isBlank() && !roles.contains(role)) {
                    roles.add(role);
                }
            }
        }
    }

    private String extractTenantCode(JsonNode payload) {
        if (payload.hasNonNull("tenant_code")) {
            return payload.path("tenant_code").asText().trim();
        }
        if (payload.hasNonNull("tenant_id")) {
            return payload.path("tenant_id").asText().trim();
        }
        if (payload.hasNonNull("organization")) {
            JsonNode org = payload.path("organization");
            if (org.isTextual()) {
                return org.asText().trim();
            } else if (org.isArray() && !org.isEmpty()) {
                JsonNode first = org.get(0);
                if (first.isTextual()) {
                    return first.asText().trim();
                } else if (first.isObject()) {
                    if (first.hasNonNull("alias")) return first.path("alias").asText().trim();
                    if (first.hasNonNull("name")) return first.path("name").asText().trim();
                    if (first.hasNonNull("id")) return first.path("id").asText().trim();
                }
            } else if (org.isObject()) {
                if (org.hasNonNull("alias")) return org.path("alias").asText().trim();
                if (org.hasNonNull("name")) return org.path("name").asText().trim();
                if (org.hasNonNull("id")) return org.path("id").asText().trim();
                java.util.Iterator<String> fieldNames = org.fieldNames();
                if (fieldNames.hasNext()) {
                    return fieldNames.next();
                }
            }
        }
        return null;
    }

    private String extractErrorDescription(String responseBody) {
        if (responseBody == null || responseBody.isBlank()) {
            return null;
        }
        try {
            JsonNode errorJson = objectMapper.readTree(responseBody);
            if (errorJson.hasNonNull("error_description")) {
                return errorJson.path("error_description").asText();
            }
            if (errorJson.hasNonNull("error")) {
                return errorJson.path("error").asText();
            }
        } catch (Exception ignored) {
        }
        return null;
    }
}
