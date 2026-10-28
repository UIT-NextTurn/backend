package com.nextturn.infrastructure.security.keycloak;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nextturn.dto.auth.AuthTokenResponse;
import com.nextturn.enums.UserRole;
import com.nextturn.exception.auth.AuthErrorCode;
import com.nextturn.exception.auth.AuthException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KeycloakTokenServiceTest {

    private KeycloakTokenService tokenService;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        KeycloakProperties properties = new KeycloakProperties(
                "nextturn-backend",
                "secret-123",
                "nextturn",
                "http://localhost:8180",
                "http://localhost:8180/realms/nextturn",
                "http://localhost:8180/realms/nextturn/protocol/openid-connect/certs"
        );
        tokenService = new KeycloakTokenService(properties, objectMapper);
    }

    private String createRawJwt(Map<String, Object> payloadClaims) throws Exception {
        String header = Base64.getUrlEncoder().withoutPadding().encodeToString("{\"alg\":\"RS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
        String payloadJson = objectMapper.writeValueAsString(payloadClaims);
        String payload = Base64.getUrlEncoder().withoutPadding().encodeToString(payloadJson.getBytes(StandardCharsets.UTF_8));
        String signature = Base64.getUrlEncoder().withoutPadding().encodeToString("mock-signature".getBytes(StandardCharsets.UTF_8));
        return header + "." + payload + "." + signature;
    }

    @Test
    @DisplayName("Should successfully parse and validate SUPERADMIN client role and route to /admin/dashboard")
    void shouldAllowSuperAdminClientRole() throws Exception {
        String token = createRawJwt(Map.of(
                "sub", "sa-uuid-1",
                "email", "sa@nextturn.com",
                "resource_access", Map.of(
                        "nextturn-backend", Map.of(
                                "roles", java.util.List.of("SUPERADMIN")
                        )
                )
        ));

        AuthTokenResponse response = tokenService.parseAndValidateAccessToken(token, "refresh-token-1", "id-token-1", 300L, 1800L, "Bearer");

        assertThat(response).isNotNull();
        assertThat(response.getPrimaryRole()).isEqualTo(UserRole.SUPERADMIN_VALUE);
        assertThat(response.getTargetDashboardUrl()).isEqualTo("/admin/dashboard");
        assertThat(response.getRoles()).contains("SUPERADMIN");
        assertThat(response.getEmail()).isEqualTo("sa@nextturn.com");
    }

    @Test
    @DisplayName("Should successfully parse and validate TENANT_ADMIN client role and route to /tenant/dashboard")
    void shouldAllowTenantAdminClientRole() throws Exception {
        String token = createRawJwt(Map.of(
                "sub", "admin-uuid-2",
                "email", "admin@clinic.vn",
                "tenant_code", "clinic_sai_gon",
                "resource_access", Map.of(
                        "nextturn-backend", Map.of(
                                "roles", java.util.List.of("TENANT_ADMIN")
                        )
                )
        ));

        AuthTokenResponse response = tokenService.parseAndValidateAccessToken(token, "refresh-token-2", null, 300L, 1800L, "Bearer");

        assertThat(response).isNotNull();
        assertThat(response.getPrimaryRole()).isEqualTo(UserRole.TENANT_ADMIN_VALUE);
        assertThat(response.getTargetDashboardUrl()).isEqualTo("/tenant/dashboard");
        assertThat(response.getTenantCode()).isEqualTo("clinic_sai_gon");
    }

    @Test
    @DisplayName("Should successfully parse and validate COUNTER_STAFF client role and route to /staff/counter")
    void shouldAllowCounterStaffClientRole() throws Exception {
        String token = createRawJwt(Map.of(
                "sub", "staff-uuid-3",
                "email", "staff@clinic.vn",
                "tenant_code", "clinic_sai_gon",
                "resource_access", Map.of(
                        "nextturn-backend", Map.of(
                                "roles", java.util.List.of("COUNTER_STAFF")
                        )
                )
        ));

        AuthTokenResponse response = tokenService.parseAndValidateAccessToken(token, "refresh-token-3", null, 300L, 1800L, "Bearer");

        assertThat(response).isNotNull();
        assertThat(response.getPrimaryRole()).isEqualTo(UserRole.COUNTER_STAFF_VALUE);
        assertThat(response.getTargetDashboardUrl()).isEqualTo("/staff/counter");
    }

    @Test
    @DisplayName("Should strictly reject END_USER client role with ROLE_NOT_PERMITTED exception")
    void shouldRejectEndUserRole() throws Exception {
        String token = createRawJwt(Map.of(
                "sub", "guest-uuid-4",
                "email", "guest@gmail.com",
                "resource_access", Map.of(
                        "nextturn-backend", Map.of(
                                "roles", java.util.List.of("END_USER")
                        )
                )
        ));

        assertThatThrownBy(() -> tokenService.parseAndValidateAccessToken(token, "refresh-4", null, 300L, 1800L, "Bearer"))
                .isInstanceOf(AuthException.class)
                .hasMessageContaining("End users (customers) are not permitted to log in via the management portal")
                .satisfies(ex -> {
                    AuthException authEx = (AuthException) ex;
                    assertThat(authEx.getCode()).isEqualTo(AuthErrorCode.ROLE_NOT_PERMITTED.code());
                });
    }

    @Test
    @DisplayName("Should successfully parse token with organization array claim matching Keycloak format")
    void shouldParseTokenWithOrganizationArray() throws Exception {
        String token = createRawJwt(Map.of(
                "sub", "d0c4911d-a83c-42e1-8463-9ba8fec1157f",
                "email", "admin@shop-b.com",
                "organization", java.util.List.of("shop-b"),
                "resource_access", Map.of(
                        "nextturn-backend", Map.of(
                                "roles", java.util.List.of("TENANT_ADMIN")
                        ),
                        "account", Map.of(
                                "roles", java.util.List.of("manage-account", "view-profile")
                        )
                )
        ));

        AuthTokenResponse response = tokenService.parseAndValidateAccessToken(token, "refresh-token-shop-b", null, 300L, 1800L, "Bearer");

        assertThat(response).isNotNull();
        assertThat(response.getUserId()).isEqualTo("d0c4911d-a83c-42e1-8463-9ba8fec1157f");
        assertThat(response.getEmail()).isEqualTo("admin@shop-b.com");
        assertThat(response.getTenantCode()).isEqualTo("shop-b");
        assertThat(response.getPrimaryRole()).isEqualTo(UserRole.TENANT_ADMIN_VALUE);
        assertThat(response.getTargetDashboardUrl()).isEqualTo("/tenant/dashboard");
    }
}
