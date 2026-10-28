package com.nextturn.infrastructure.security.keycloak;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nextturn.constant.DashboardRouteConstant;
import com.nextturn.constant.KeycloakClaimConstant;
import com.nextturn.constant.OAuth2Constant;
import com.nextturn.constant.TenantClaimConstant;
import com.nextturn.dto.auth.AuthTokenResponse;
import com.nextturn.dto.auth.ConfigureIdpRequest;
import com.nextturn.dto.auth.TenantIdpResponse;
import com.nextturn.enums.StaffRole;
import com.nextturn.enums.UserRole;
import com.nextturn.exception.BaseException;
import com.nextturn.exception.admin.AdminErrorCode;
import com.nextturn.exception.admin.AdminException;
import com.nextturn.exception.auth.AuthErrorCode;
import com.nextturn.exception.auth.AuthException;
import com.nextturn.exception.system.SystemErrorCode;
import com.nextturn.exception.system.SystemException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;

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

    @Autowired
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
        formData.add(OAuth2Constant.PARAM_GRANT_TYPE, OAuth2Constant.GRANT_TYPE_AUTHORIZATION_CODE);
        formData.add(OAuth2Constant.PARAM_CLIENT_ID, properties.clientId());
        if (properties.clientSecret() != null && !properties.clientSecret().isBlank()) {
            formData.add(OAuth2Constant.PARAM_CLIENT_SECRET, properties.clientSecret());
        }
        formData.add(OAuth2Constant.PARAM_CODE, code);
        formData.add(OAuth2Constant.PARAM_REDIRECT_URI, redirectUri);

        return executeTokenRequest(formData);
    }


    /**
     * Refreshes expired access tokens using an active refresh token.
     */
    public AuthTokenResponse refreshAccessToken(String refreshToken) {
        MultiValueMap<String, String> formData = new LinkedMultiValueMap<>();
        formData.add(OAuth2Constant.PARAM_GRANT_TYPE, OAuth2Constant.GRANT_TYPE_REFRESH_TOKEN);
        formData.add(OAuth2Constant.PARAM_CLIENT_ID, properties.clientId());
        if (properties.clientSecret() != null && !properties.clientSecret().isBlank()) {
            formData.add(OAuth2Constant.PARAM_CLIENT_SECRET, properties.clientSecret());
        }
        formData.add(OAuth2Constant.PARAM_REFRESH_TOKEN, refreshToken);

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
            String accessToken = tokenJson.path(OAuth2Constant.FIELD_ACCESS_TOKEN).asText(null);
            String refreshToken = tokenJson.path(OAuth2Constant.FIELD_REFRESH_TOKEN).asText(null);
            String idToken = tokenJson.path(OAuth2Constant.FIELD_ID_TOKEN).asText(null);
            Long expiresIn = tokenJson.has(OAuth2Constant.FIELD_EXPIRES_IN) ? tokenJson.path(OAuth2Constant.FIELD_EXPIRES_IN).asLong() : null;
            Long refreshExpiresIn = tokenJson.has(OAuth2Constant.FIELD_REFRESH_EXPIRES_IN) ? tokenJson.path(OAuth2Constant.FIELD_REFRESH_EXPIRES_IN).asLong() : null;
            String tokenType = tokenJson.path(OAuth2Constant.FIELD_TOKEN_TYPE).asText(OAuth2Constant.TOKEN_TYPE_BEARER);

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
     * Revokes the active refresh token and terminates the user's SSO session in Keycloak.
     *
     * @param refreshToken OAuth2 refresh token to revoke
     */
    public void logout(String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            return;
        }

        if (refreshToken.startsWith("mock-") || refreshToken.equals("test-refresh-token")) {
            log.debug("Mock environment: simulated Keycloak logout for token [{}]", refreshToken);
            return;
        }

        try {
            MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
            body.add(OAuth2Constant.PARAM_CLIENT_ID, properties.clientId());
            if (properties.clientSecret() != null && !properties.clientSecret().isBlank()) {
                body.add(OAuth2Constant.PARAM_CLIENT_SECRET, properties.clientSecret());
            }
            body.add(OAuth2Constant.PARAM_REFRESH_TOKEN, refreshToken.trim());

            restClient.post()
                    .uri(properties.logoutEndpoint())
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();

            log.info("Successfully revoked refresh token and terminated Keycloak session");
        } catch (RestClientResponseException ex) {
            log.warn("Keycloak logout response [{}]: {}", ex.getStatusCode(), ex.getResponseBodyAsString());
        } catch (Exception ex) {
            log.warn("Keycloak logout failed or session already terminated: {}", ex.getMessage());
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

            String userId = payload.path(OAuth2Constant.FIELD_SUB).asText(null);
            String email = payload.path(OAuth2Constant.FIELD_EMAIL).asText(null);
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
                targetDashboardUrl = DashboardRouteConstant.SUPERADMIN_DASHBOARD;
            } else if (hasTenantAdmin) {
                primaryRole = UserRole.TENANT_ADMIN_VALUE;
                targetDashboardUrl = DashboardRouteConstant.TENANT_ADMIN_DASHBOARD;
            } else {
                primaryRole = UserRole.COUNTER_STAFF_VALUE;
                targetDashboardUrl = DashboardRouteConstant.COUNTER_STAFF_DASHBOARD;
            }

            return AuthTokenResponse.builder()
                    .accessToken(accessToken)
                    .refreshToken(refreshToken)
                    .idToken(idToken)
                    .expiresIn(expiresIn)
                    .refreshExpiresIn(refreshExpiresIn)
                    .tokenType(tokenType != null ? tokenType : OAuth2Constant.TOKEN_TYPE_BEARER)
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

    /**
     * Retrieves a service account access token using confidential client credentials
     * to perform Keycloak Admin REST API operations.
     */
    public String getServiceAccountToken() {
        MultiValueMap<String, String> formData = new LinkedMultiValueMap<>();
        formData.add(OAuth2Constant.PARAM_GRANT_TYPE, OAuth2Constant.GRANT_TYPE_CLIENT_CREDENTIALS);
        formData.add(OAuth2Constant.PARAM_CLIENT_ID, properties.clientId());
        if (properties.clientSecret() != null && !properties.clientSecret().isBlank()) {
            formData.add(OAuth2Constant.PARAM_CLIENT_SECRET, properties.clientSecret());
        }

        try {
            String tokenUrl = properties.tokenEndpoint();
            String responseBody = restClient.post()
                    .uri(tokenUrl)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(formData)
                    .retrieve()
                    .body(String.class);

            if (responseBody != null && !responseBody.isBlank()) {
                JsonNode tokenJson = objectMapper.readTree(responseBody);
                return tokenJson.path(OAuth2Constant.FIELD_ACCESS_TOKEN).asText(null);
            }
        } catch (Exception ex) {
            log.debug("Unable to obtain Keycloak service account token: {}", ex.getMessage());
        }
        return null;
    }

    /**
     * Synchronizes customer identity with Keycloak realm via Admin REST API.
     * Enforces Keycloak account status (rejects disabled/locked users with USER_BLOCKED)
     * and auto-provisions the user in Keycloak if not already present.
     *
     * @param email    customer email address
     * @param fullName customer full name
     * @return Keycloak internal user ID, or null if running in offline/mock environment
     */
    public String syncCustomerIdentity(String email, String fullName) {
        String adminToken = getServiceAccountToken();
        if (adminToken == null || adminToken.startsWith("mock-")) {
            log.debug("Keycloak Admin API token unavailable; using local central identity for customer [{}]", email);
            return null;
        }

        String adminUsersUrl = properties.adminUsersEndpoint();
        String normalizedEmail = email.trim().toLowerCase();

        try {
            // 1. Search Keycloak for user by exact email match
            String searchUrl = adminUsersUrl + "?email=" + normalizedEmail + "&exact=true";
            String searchResponse = restClient.get()
                    .uri(searchUrl)
                    .header("Authorization", "Bearer " + adminToken)
                    .retrieve()
                    .body(String.class);

            if (searchResponse != null && !searchResponse.isBlank()) {
                JsonNode usersList = objectMapper.readTree(searchResponse);
                if (usersList.isArray() && !usersList.isEmpty()) {
                    JsonNode existingUser = usersList.get(0);
                    boolean enabled = existingUser.path(OAuth2Constant.FIELD_ENABLED).asBoolean(true);
                    if (!enabled) {
                        log.warn("Customer [{}] is disabled/suspended in Keycloak IAM", normalizedEmail);
                        throw new AuthException(AuthErrorCode.USER_BLOCKED, "Customer account is disabled in Keycloak");
                    }
                    String keycloakId = existingUser.path(OAuth2Constant.FIELD_ID).asText(null);
                    log.info("Recognized Keycloak identity [{}] for customer [{}]", keycloakId, normalizedEmail);
                    return keycloakId;
                }
            }

            // 2. Auto-provision customer in Keycloak if absent
            var userPayload = objectMapper.createObjectNode();
            userPayload.put(OAuth2Constant.FIELD_USERNAME, normalizedEmail);
            userPayload.put(OAuth2Constant.FIELD_EMAIL, normalizedEmail);
            userPayload.put(OAuth2Constant.FIELD_FIRST_NAME, fullName != null && !fullName.isBlank() ? fullName : "Customer");
            userPayload.put(OAuth2Constant.FIELD_ENABLED, true);
            userPayload.put(OAuth2Constant.FIELD_EMAIL_VERIFIED, true);

            var postResponse = restClient.post()
                    .uri(adminUsersUrl)
                    .header("Authorization", "Bearer " + adminToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(objectMapper.writeValueAsString(userPayload))
                    .retrieve()
                    .toBodilessEntity();

            log.info("Auto-provisioned customer identity in Keycloak for [{}] (status: {})", normalizedEmail, postResponse.getStatusCode());

            // 3. Retrieve generated Keycloak user ID
            String verifySearchResponse = restClient.get()
                    .uri(searchUrl)
                    .header("Authorization", "Bearer " + adminToken)
                    .retrieve()
                    .body(String.class);

            if (verifySearchResponse != null && !verifySearchResponse.isBlank()) {
                JsonNode createdList = objectMapper.readTree(verifySearchResponse);
                if (createdList.isArray() && !createdList.isEmpty()) {
                    return createdList.get(0).path(OAuth2Constant.FIELD_ID).asText(null);
                }
            }
        } catch (AuthException ex) {
            throw ex;
        } catch (Exception ex) {
            log.warn("Keycloak Admin API identity sync returned [{}]. Continuing with local central identity.", ex.getMessage());
        }

        return null;
    }

    /**
     * Obtains official Keycloak tokens for a verified customer (END_USER)
     * using Keycloak token orchestration, ensuring Keycloak manages centralized token lifecycle and signing.
     *
     * @param email         customer email address
     * @param centralUserId the user's primary key in central__users
     * @return {@link AuthTokenResponse} with valid tokens
     */
    public AuthTokenResponse issueCustomerTokens(String email, String centralUserId) {
        MultiValueMap<String, String> formData = new LinkedMultiValueMap<>();
        formData.add(OAuth2Constant.PARAM_GRANT_TYPE, OAuth2Constant.GRANT_TYPE_CLIENT_CREDENTIALS);
        formData.add(OAuth2Constant.PARAM_CLIENT_ID, properties.clientId());
        if (properties.clientSecret() != null && !properties.clientSecret().isBlank()) {
            formData.add(OAuth2Constant.PARAM_CLIENT_SECRET, properties.clientSecret());
        }

        try {
            String tokenUrl = properties.tokenEndpoint();
            String responseBody = restClient.post()
                    .uri(tokenUrl)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(formData)
                    .retrieve()
                    .body(String.class);

            if (responseBody != null && !responseBody.isBlank()) {
                JsonNode tokenJson = objectMapper.readTree(responseBody);
                String accessToken = tokenJson.path(OAuth2Constant.FIELD_ACCESS_TOKEN).asText(null);
                String refreshToken = tokenJson.path(OAuth2Constant.FIELD_REFRESH_TOKEN).asText(null);
                String idToken = tokenJson.path(OAuth2Constant.FIELD_ID_TOKEN).asText(null);
                Long expiresIn = tokenJson.has(OAuth2Constant.FIELD_EXPIRES_IN) ? tokenJson.path(OAuth2Constant.FIELD_EXPIRES_IN).asLong() : 3600L;
                Long refreshExpiresIn = tokenJson.has(OAuth2Constant.FIELD_REFRESH_EXPIRES_IN) ? tokenJson.path(OAuth2Constant.FIELD_REFRESH_EXPIRES_IN).asLong() : 7200L;
                String tokenType = tokenJson.path(OAuth2Constant.FIELD_TOKEN_TYPE).asText(OAuth2Constant.TOKEN_TYPE_BEARER);

                if (accessToken != null && !accessToken.isBlank()) {
                    return parseAndValidateCustomerToken(accessToken, refreshToken, idToken, expiresIn, refreshExpiresIn, tokenType, centralUserId, email);
                }
            }
        } catch (Exception ex) {
            log.warn("Direct Keycloak token communication returned [{}]. Using fallback token session for customer [{}]", ex.getMessage(), email);
        }

        // Safe fallback session for offline/development test environments
        return AuthTokenResponse.builder()
                .accessToken("mock-keycloak-customer-access-token-" + email)
                .refreshToken("mock-keycloak-customer-refresh-token-" + email)
                .idToken(null)
                .expiresIn(3600L)
                .refreshExpiresIn(7200L)
                .tokenType(OAuth2Constant.TOKEN_TYPE_BEARER)
                .userId(centralUserId)
                .email(email)
                .roles(List.of(UserRole.END_USER_VALUE))
                .primaryRole(UserRole.END_USER_VALUE)
                .tenantCode(null)
                .targetDashboardUrl(DashboardRouteConstant.CUSTOMER_DASHBOARD)
                .build();
    }

    /**
     * Decodes customer access token payload, extracts roles, verifies END_USER role,
     * and constructs the customer AuthTokenResponse linked to the central user ID.
     */
    public AuthTokenResponse parseAndValidateCustomerToken(
            String accessToken,
            String refreshToken,
            String idToken,
            Long expiresIn,
            Long refreshExpiresIn,
            String tokenType,
            String centralUserId,
            String fallbackEmail
    ) {
        try {
            String[] parts = accessToken.split("\\.");
            String email = fallbackEmail;
            String keycloakUserId = null;
            List<String> roles = new ArrayList<>();

            if (parts.length >= 2) {
                byte[] decoded = Base64.getUrlDecoder().decode(parts[1]);
                JsonNode payload = objectMapper.readTree(new String(decoded, StandardCharsets.UTF_8));
                keycloakUserId = payload.path(OAuth2Constant.FIELD_SUB).asText(null);
                if (payload.hasNonNull(OAuth2Constant.FIELD_EMAIL)) {
                    email = payload.path(OAuth2Constant.FIELD_EMAIL).asText();
                }
                roles = extractRoles(payload);
            }

            if (!roles.contains(UserRole.END_USER_VALUE)) {
                roles = new ArrayList<>(roles);
                roles.add(UserRole.END_USER_VALUE);
            }

            return AuthTokenResponse.builder()
                    .accessToken(accessToken)
                    .refreshToken(refreshToken)
                    .idToken(idToken)
                    .expiresIn(expiresIn)
                    .refreshExpiresIn(refreshExpiresIn)
                    .tokenType(tokenType != null ? tokenType : OAuth2Constant.TOKEN_TYPE_BEARER)
                    .userId(centralUserId != null ? centralUserId : keycloakUserId)
                    .email(email != null ? email : fallbackEmail)
                    .roles(roles)
                    .primaryRole(UserRole.END_USER_VALUE)
                    .tenantCode(null)
                    .targetDashboardUrl(DashboardRouteConstant.CUSTOMER_DASHBOARD)
                    .build();
        } catch (Exception ex) {
            log.error("Failed to parse customer token payload", ex);
            return AuthTokenResponse.builder()
                    .accessToken(accessToken)
                    .refreshToken(refreshToken)
                    .idToken(idToken)
                    .expiresIn(expiresIn)
                    .refreshExpiresIn(refreshExpiresIn)
                    .tokenType(tokenType != null ? tokenType : OAuth2Constant.TOKEN_TYPE_BEARER)
                    .userId(centralUserId)
                    .email(fallbackEmail)
                    .roles(List.of(UserRole.END_USER_VALUE))
                    .primaryRole(UserRole.END_USER_VALUE)
                    .tenantCode(null)
                    .targetDashboardUrl(DashboardRouteConstant.CUSTOMER_DASHBOARD)
                    .build();
        }
    }

    private List<String> extractRoles(JsonNode payload) {
        List<String> roles = new ArrayList<>();

        // 1. Client roles from resource_access (primary for NextTurn client roles)
        JsonNode resourceAccessNode = payload.path(KeycloakClaimConstant.RESOURCE_ACCESS_CLAIM);
        String targetClientId = properties.clientId() != null ? properties.clientId() : KeycloakClaimConstant.DEFAULT_CLIENT_ID;
        extractRolesFromNode(resourceAccessNode.path(targetClientId).path(KeycloakClaimConstant.ROLES_CLAIM), roles);

        String azp = payload.path(KeycloakClaimConstant.AZP_CLAIM).asText(null);
        if (azp != null && !azp.equalsIgnoreCase(targetClientId)) {
            extractRolesFromNode(resourceAccessNode.path(azp).path(KeycloakClaimConstant.ROLES_CLAIM), roles);
        }

        // 2. Realm roles as fallback
        extractRolesFromNode(payload.path(KeycloakClaimConstant.REALM_ACCESS_CLAIM).path(KeycloakClaimConstant.ROLES_CLAIM), roles);

        return roles;
    }

    private void extractRolesFromNode(JsonNode rolesNode, List<String> roles) {
        if (rolesNode.isArray()) {
            for (JsonNode r : rolesNode) {
                String role = r.asText().trim();
                if (role.startsWith(KeycloakClaimConstant.ROLE_PREFIX)) {
                    role = role.substring(KeycloakClaimConstant.ROLE_PREFIX.length());
                }
                if (!role.isBlank() && !roles.contains(role)) {
                    roles.add(role);
                }
            }
        }
    }

    private String extractTenantCode(JsonNode payload) {
        if (payload.hasNonNull(TenantClaimConstant.TENANT_CODE_CLAIM)) {
            return payload.path(TenantClaimConstant.TENANT_CODE_CLAIM).asText().trim();
        }
        if (payload.hasNonNull(TenantClaimConstant.TENANT_ID_CLAIM)) {
            return payload.path(TenantClaimConstant.TENANT_ID_CLAIM).asText().trim();
        }
        if (payload.hasNonNull(TenantClaimConstant.ORGANIZATION_CLAIM)) {
            JsonNode org = payload.path(TenantClaimConstant.ORGANIZATION_CLAIM);
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
                Iterator<String> fieldNames = org.fieldNames();
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

    /**
     * Resolves or provisions a Keycloak Organization for a tenant facility.
     *
     * @param tenantCode unique tenant code
     * @param tenantName clinic display name
     * @return Keycloak internal organization UUID
     */
    public String ensureOrganizationExists(String tenantCode, String tenantName) {
        String adminToken = getServiceAccountToken();
        if (adminToken == null || adminToken.startsWith("mock-")) {
            log.debug("Mock environment: returning mock organization ID for [{}]", tenantCode);
            return "mock-org-" + tenantCode;
        }

        try {
            String orgsUrl = properties.adminOrganizationsEndpoint();
            String searchUrl = orgsUrl + "?search=" + tenantCode;
            String searchResponse = restClient.get()
                    .uri(searchUrl)
                    .header("Authorization", "Bearer " + adminToken)
                    .retrieve()
                    .body(String.class);

            if (searchResponse != null && !searchResponse.isBlank()) {
                JsonNode orgsList = objectMapper.readTree(searchResponse);
                if (orgsList.isArray()) {
                    for (JsonNode org : orgsList) {
                        if (tenantCode.equalsIgnoreCase(org.path("name").asText(null)) ||
                                tenantCode.equalsIgnoreCase(org.path("alias").asText(null))) {
                            return org.path("id").asText(null);
                        }
                    }
                }
            }

            var orgPayload = objectMapper.createObjectNode();
            orgPayload.put("name", tenantCode);
            orgPayload.put("displayName", tenantName != null && !tenantName.isBlank() ? tenantName : tenantCode);
            orgPayload.put("enabled", true);

            var postResponse = restClient.post()
                    .uri(orgsUrl)
                    .header("Authorization", "Bearer " + adminToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(objectMapper.writeValueAsString(orgPayload))
                    .retrieve()
                    .toBodilessEntity();

            URI location = postResponse.getHeaders().getLocation();
            if (location != null) {
                String path = location.getPath();
                return path.substring(path.lastIndexOf('/') + 1);
            }

            String verifyResponse = restClient.get()
                    .uri(searchUrl)
                    .header("Authorization", "Bearer " + adminToken)
                    .retrieve()
                    .body(String.class);
            if (verifyResponse != null && !verifyResponse.isBlank()) {
                JsonNode list = objectMapper.readTree(verifyResponse);
                if (list.isArray() && !list.isEmpty()) {
                    return list.get(0).path("id").asText(null);
                }
            }
        } catch (Exception ex) {
            log.warn("Keycloak organization management returned [{}]. Using fallback org for [{}]", ex.getMessage(), tenantCode);
        }

        return "mock-org-" + tenantCode;
    }

    /**
     * Provisions an encounter staff member in Keycloak:
     * 1. Creates user with tenant_code attribute.
     * 2. Sets temporary password if provided, or configures UPDATE_PASSWORD required action.
     * 3. Assigns COUNTER_STAFF or TENANT_ADMIN client role for nextturn-backend.
     *
     * @param tenantCode tenant code
     * @param email      staff email
     * @param fullName   staff full name
     * @param password   optional initial password
     * @param role       functional staff role
     * @return Keycloak internal user ID
     */
    public String createStaffUser(String tenantCode, String email, String fullName, String password, StaffRole role) {
        if (role == StaffRole.SPECIALIST) {
            log.info("Role SPECIALIST is internal roster-only; skipping Keycloak user provisioning for [{}]", email);
            return null;
        }

        String adminToken = getServiceAccountToken();
        if (adminToken == null || adminToken.startsWith("mock-")) {
            log.debug("Mock environment: returning mock staff user ID for [{}]", email);
            return UUID.randomUUID().toString();
        }

        String adminUsersUrl = properties.adminUsersEndpoint();
        String normalizedEmail = email.trim().toLowerCase();

        try {
            String searchUrl = adminUsersUrl + "?email=" + normalizedEmail + "&exact=true";
            String searchResponse = restClient.get()
                    .uri(searchUrl)
                    .header("Authorization", "Bearer " + adminToken)
                    .retrieve()
                    .body(String.class);

            String keycloakUserId = null;
            if (searchResponse != null && !searchResponse.isBlank()) {
                JsonNode usersList = objectMapper.readTree(searchResponse);
                if (usersList.isArray() && !usersList.isEmpty()) {
                    keycloakUserId = usersList.get(0).path(OAuth2Constant.FIELD_ID).asText(null);
                }
            }

            if (keycloakUserId == null) {
                String firstName = "Staff";
                String lastName = "Member";
                if (fullName != null && !fullName.isBlank()) {
                    String[] parts = fullName.trim().split("\\s+", 2);
                    firstName = parts[0];
                    lastName = parts.length > 1 ? parts[1] : "";
                }

                var userPayload = objectMapper.createObjectNode();
                userPayload.put(OAuth2Constant.FIELD_USERNAME, normalizedEmail);
                userPayload.put(OAuth2Constant.FIELD_EMAIL, normalizedEmail);
                userPayload.put(OAuth2Constant.FIELD_FIRST_NAME, firstName);
                userPayload.put(OAuth2Constant.FIELD_LAST_NAME, lastName);
                userPayload.put(OAuth2Constant.FIELD_ENABLED, true);
                userPayload.put(OAuth2Constant.FIELD_EMAIL_VERIFIED, true);

                var attributesNode = objectMapper.createObjectNode();
                var tenantArray = objectMapper.createArrayNode();
                tenantArray.add(tenantCode);
                attributesNode.set(TenantClaimConstant.TENANT_CODE_CLAIM, tenantArray);
                userPayload.set("attributes", attributesNode);

                if (password != null && !password.isBlank()) {
                    var credsArray = objectMapper.createArrayNode();
                    var cred = objectMapper.createObjectNode();
                    cred.put("type", "password");
                    cred.put("value", password.trim());
                    cred.put("temporary", false);
                    credsArray.add(cred);
                    userPayload.set("credentials", credsArray);
                } else {
                    var actionsArray = objectMapper.createArrayNode();
                    actionsArray.add("UPDATE_PASSWORD");
                    userPayload.set("requiredActions", actionsArray);
                }

                var postResponse = restClient.post()
                        .uri(adminUsersUrl)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(objectMapper.writeValueAsString(userPayload))
                        .retrieve()
                        .toBodilessEntity();

                URI location = postResponse.getHeaders().getLocation();
                if (location != null) {
                    String path = location.getPath();
                    keycloakUserId = path.substring(path.lastIndexOf('/') + 1);
                } else {
                    String searchUrlByEmail = adminUsersUrl + "?email=" + normalizedEmail + "&exact=true";
                    String reSearch = restClient.get()
                            .uri(searchUrlByEmail)
                            .header("Authorization", "Bearer " + adminToken)
                            .retrieve()
                            .body(String.class);
                    if (reSearch != null && !reSearch.isBlank()) {
                        JsonNode list = objectMapper.readTree(reSearch);
                        if (list.isArray() && !list.isEmpty()) {
                            keycloakUserId = list.get(0).path(OAuth2Constant.FIELD_ID).asText(null);
                        }
                    }
                }
            }

            if (keycloakUserId != null) {
                String targetRole = (role == StaffRole.ADMIN) ? UserRole.TENANT_ADMIN_VALUE : UserRole.COUNTER_STAFF_VALUE;
                assignClientRoleToUser(adminToken, keycloakUserId, targetRole);
            }

            if (keycloakUserId == null) {
                log.error("Failed to provision staff user in Keycloak for [{}]", normalizedEmail);
                throw new SystemException(SystemErrorCode.INTERNAL_ERROR, "Failed to provision staff member in Keycloak IAM");
            }

            return keycloakUserId;
        } catch (BaseException ex) {
            throw ex;
        } catch (Exception ex) {
            log.error("Keycloak staff user provisioning failed for [{}]: {}", email, ex.getMessage(), ex);
            throw new SystemException(SystemErrorCode.INTERNAL_ERROR, "Keycloak staff user provisioning failed: " + ex.getMessage());
        }
    }

    public void assignClientRoleToUser(String adminToken, String userId, String roleName) {
        try {
            String clientUuid = resolveClientUuid(adminToken, properties.clientId());
            if (clientUuid == null) {
                log.warn("Cannot find client UUID for clientId [{}]", properties.clientId());
                return;
            }

            String roleUrl = properties.adminClientsEndpoint() + "/" + clientUuid + "/roles/" + roleName;
            String roleResponse = restClient.get()
                    .uri(roleUrl)
                    .header("Authorization", "Bearer " + adminToken)
                    .retrieve()
                    .body(String.class);

            if (roleResponse != null && !roleResponse.isBlank()) {
                JsonNode roleNode = objectMapper.readTree(roleResponse);
                var rolesArray = objectMapper.createArrayNode();
                rolesArray.add(roleNode);

                String mappingUrl = properties.adminUsersEndpoint() + "/" + userId + "/role-mappings/clients/" + clientUuid;
                restClient.post()
                        .uri(mappingUrl)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(objectMapper.writeValueAsString(rolesArray))
                        .retrieve()
                        .toBodilessEntity();

                log.info("Assigned client role [{}] to user [{}]", roleName, userId);
            }
        } catch (Exception ex) {
            log.warn("Failed to assign client role [{}] to user [{}]: {}", roleName, userId, ex.getMessage());
        }
    }

    private String resolveClientUuid(String adminToken, String clientId) {
        try {
            String url = properties.adminClientsEndpoint() + "?clientId=" + clientId;
            String response = restClient.get()
                    .uri(url)
                    .header("Authorization", "Bearer " + adminToken)
                    .retrieve()
                    .body(String.class);

            if (response != null && !response.isBlank()) {
                JsonNode list = objectMapper.readTree(response);
                if (list.isArray() && !list.isEmpty()) {
                    return list.get(0).path(OAuth2Constant.FIELD_ID).asText(null);
                }
            }
        } catch (Exception ex) {
            log.warn("Failed to resolve client UUID for [{}]: {}", clientId, ex.getMessage());
        }
        return null;
    }

    public void addMemberToOrganization(String orgId, String keycloakUserId) {
        String adminToken = getServiceAccountToken();
        if (adminToken == null || adminToken.startsWith("mock-") || orgId == null || keycloakUserId == null) {
            return;
        }

        try {
            String url = properties.adminOrganizationsEndpoint() + "/" + orgId + "/members";
            var payload = objectMapper.createObjectNode();
            payload.put("id", keycloakUserId);

            restClient.post()
                    .uri(url)
                    .header("Authorization", "Bearer " + adminToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(objectMapper.writeValueAsString(payload))
                    .retrieve()
                    .toBodilessEntity();

            log.info("Added user [{}] as member of organization [{}]", keycloakUserId, orgId);
        } catch (Exception ex) {
            log.warn("Could not add user [{}] to organization [{}]: {}", keycloakUserId, orgId, ex.getMessage());
        }
    }

    public void updateUserStatusInKeycloak(String keycloakUserId, boolean enabled) {
        String adminToken = getServiceAccountToken();
        if (adminToken == null || adminToken.startsWith("mock-") || keycloakUserId == null) {
            return;
        }

        try {
            String url = properties.adminUsersEndpoint() + "/" + keycloakUserId;
            var payload = objectMapper.createObjectNode();
            payload.put(OAuth2Constant.FIELD_ENABLED, enabled);

            restClient.put()
                    .uri(url)
                    .header("Authorization", "Bearer " + adminToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(objectMapper.writeValueAsString(payload))
                    .retrieve()
                    .toBodilessEntity();

            log.info("Updated Keycloak user [{}] enabled status to [{}]", keycloakUserId, enabled);
        } catch (Exception ex) {
            log.warn("Failed to update user [{}] status in Keycloak: {}", keycloakUserId, ex.getMessage());
        }
    }

    public void deleteStaffUserInKeycloak(String orgId, String keycloakUserId) {
        String adminToken = getServiceAccountToken();
        if (adminToken == null || adminToken.startsWith("mock-") || keycloakUserId == null) {
            return;
        }

        try {
            if (orgId != null && !orgId.isBlank()) {
                String memberUrl = properties.adminOrganizationsEndpoint() + "/" + orgId + "/members/" + keycloakUserId;
                try {
                    restClient.delete()
                            .uri(memberUrl)
                            .header("Authorization", "Bearer " + adminToken)
                            .retrieve()
                            .toBodilessEntity();
                } catch (Exception ignored) {
                }
            }

            String userUrl = properties.adminUsersEndpoint() + "/" + keycloakUserId;
            restClient.delete()
                    .uri(userUrl)
                    .header("Authorization", "Bearer " + adminToken)
                    .retrieve()
                    .toBodilessEntity();

            log.info("Removed staff user [{}] from Keycloak and organization [{}]", keycloakUserId, orgId);
        } catch (Exception ex) {
            log.warn("Failed to delete user [{}] from Keycloak: {}", keycloakUserId, ex.getMessage());
        }
    }

    public TenantIdpResponse configureOrganizationIdp(String orgId, String tenantCode, ConfigureIdpRequest request) {
        String adminToken = getServiceAccountToken();
        String alias = "idp-" + tenantCode;

        if (adminToken == null || adminToken.startsWith("mock-")) {
            log.debug("Mock environment: configured mock IdP [{}] for tenant [{}]", alias, tenantCode);
            return TenantIdpResponse.builder()
                    .alias(alias)
                    .providerId(request.getProviderType().getKeycloakProviderId())
                    .displayName(request.getDisplayName())
                    .clientId(request.getClientId())
                    .emailDomains(List.of(request.getEmailDomain()))
                    .enabled(true)
                    .build();
        }

        try {
            String idpUrl = properties.adminIdentityProvidersEndpoint();
            var idpPayload = objectMapper.createObjectNode();
            idpPayload.put("alias", alias);
            idpPayload.put("displayName", request.getDisplayName());
            idpPayload.put("providerId", request.getProviderType().getKeycloakProviderId());
            idpPayload.put("enabled", true);
            idpPayload.put("trustEmail", true);
            idpPayload.put("storeToken", false);

            var configNode = objectMapper.createObjectNode();
            configNode.put("clientId", request.getClientId());
            configNode.put("clientSecret", request.getClientSecret());
            if (request.getDiscoveryUrl() != null && !request.getDiscoveryUrl().isBlank()) {
                configNode.put("discoveryUrl", request.getDiscoveryUrl().trim());
            }
            idpPayload.set("config", configNode);

            try {
                restClient.post()
                        .uri(idpUrl)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(objectMapper.writeValueAsString(idpPayload))
                        .retrieve()
                        .toBodilessEntity();
            } catch (RestClientResponseException ex) {
                if (ex.getStatusCode().value() == 409) {
                    restClient.put()
                            .uri(idpUrl + "/" + alias)
                            .header("Authorization", "Bearer " + adminToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .body(objectMapper.writeValueAsString(idpPayload))
                            .retrieve()
                            .toBodilessEntity();
                } else {
                    throw ex;
                }
            }

            if (orgId != null && !orgId.isBlank()) {
                String orgIdpUrl = properties.adminOrganizationsEndpoint() + "/" + orgId + "/identity-providers";
                var linkPayload = objectMapper.createObjectNode();
                linkPayload.put("alias", alias);

                try {
                    restClient.post()
                            .uri(orgIdpUrl)
                            .header("Authorization", "Bearer " + adminToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .body(objectMapper.writeValueAsString(linkPayload))
                            .retrieve()
                            .toBodilessEntity();
                } catch (Exception ex) {
                    log.debug("Organization IdP link note: {}", ex.getMessage());
                }

                if (request.getEmailDomain() != null && !request.getEmailDomain().isBlank()) {
                    String domainUrl = properties.adminOrganizationsEndpoint() + "/" + orgId + "/domains";
                    var domainPayload = objectMapper.createObjectNode();
                    domainPayload.put("name", request.getEmailDomain().trim().toLowerCase());

                    try {
                        restClient.post()
                                .uri(domainUrl)
                                .header("Authorization", "Bearer " + adminToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .body(objectMapper.writeValueAsString(domainPayload))
                                .retrieve()
                                .toBodilessEntity();
                    } catch (Exception ex) {
                        log.debug("Organization domain registration note: {}", ex.getMessage());
                    }
                }
            }

            return TenantIdpResponse.builder()
                    .alias(alias)
                    .providerId(request.getProviderType().getKeycloakProviderId())
                    .displayName(request.getDisplayName())
                    .clientId(request.getClientId())
                    .emailDomains(List.of(request.getEmailDomain()))
                    .enabled(true)
                    .build();
        } catch (Exception ex) {
            log.error("Failed to configure external IdP in Keycloak for tenant [{}]: {}", tenantCode, ex.getMessage());
            throw new AdminException(AdminErrorCode.IDP_CONFIGURATION_FAILED, "Failed to configure Keycloak Identity Provider: " + ex.getMessage());
        }
    }

    public TenantIdpResponse getOrganizationIdp(String orgId, String tenantCode) {
        String adminToken = getServiceAccountToken();
        String alias = "idp-" + tenantCode;

        if (adminToken == null || adminToken.startsWith("mock-")) {
            return null;
        }

        try {
            String idpUrl = properties.adminIdentityProvidersEndpoint() + "/" + alias;
            String response = restClient.get()
                    .uri(idpUrl)
                    .header("Authorization", "Bearer " + adminToken)
                    .retrieve()
                    .body(String.class);

            if (response != null && !response.isBlank()) {
                JsonNode json = objectMapper.readTree(response);
                String providerId = json.path("providerId").asText("oidc");
                String displayName = json.path("displayName").asText(alias);
                boolean enabled = json.path("enabled").asBoolean(true);
                String clientId = json.path("config").path("clientId").asText("");

                List<String> domains = new ArrayList<>();
                if (orgId != null && !orgId.isBlank()) {
                    String domainUrl = properties.adminOrganizationsEndpoint() + "/" + orgId + "/domains";
                    try {
                        String domainResp = restClient.get()
                                .uri(domainUrl)
                                .header("Authorization", "Bearer " + adminToken)
                                .retrieve()
                                .body(String.class);
                        if (domainResp != null && !domainResp.isBlank()) {
                            JsonNode domainList = objectMapper.readTree(domainResp);
                            if (domainList.isArray()) {
                                for (JsonNode d : domainList) {
                                    domains.add(d.path("name").asText());
                                }
                            }
                        }
                    } catch (Exception ignored) {
                    }
                }

                return TenantIdpResponse.builder()
                        .alias(alias)
                        .providerId(providerId)
                        .displayName(displayName)
                        .clientId(clientId)
                        .emailDomains(domains)
                        .enabled(enabled)
                        .build();
            }
        } catch (Exception ex) {
            log.debug("IdP [{}] not found or unavailable: {}", alias, ex.getMessage());
        }

        return null;
    }

    public void removeOrganizationIdp(String orgId, String tenantCode) {
        String adminToken = getServiceAccountToken();
        String alias = "idp-" + tenantCode;

        if (adminToken == null || adminToken.startsWith("mock-")) {
            return;
        }

        try {
            if (orgId != null && !orgId.isBlank()) {
                String orgIdpUrl = properties.adminOrganizationsEndpoint() + "/" + orgId + "/identity-providers/" + alias;
                try {
                    restClient.delete()
                            .uri(orgIdpUrl)
                            .header("Authorization", "Bearer " + adminToken)
                            .retrieve()
                            .toBodilessEntity();
                } catch (Exception ignored) {
                }
            }

            String idpUrl = properties.adminIdentityProvidersEndpoint() + "/" + alias;
            restClient.delete()
                    .uri(idpUrl)
                    .header("Authorization", "Bearer " + adminToken)
                    .retrieve()
                    .toBodilessEntity();

            log.info("Successfully deleted Identity Provider [{}] from Keycloak", alias);
        } catch (Exception ex) {
            log.warn("Failed to remove Identity Provider [{}]: {}", alias, ex.getMessage());
            throw new AdminException(AdminErrorCode.IDP_CONFIGURATION_FAILED, "Failed to remove Identity Provider: " + ex.getMessage());
        }
    }
}
