package com.nextturn.security;

import com.nextturn.enums.UserRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class KeycloakJwtAuthenticationConverterTest {

    private KeycloakJwtGrantedAuthoritiesConverter authoritiesConverter;
    private KeycloakJwtAuthenticationConverter authenticationConverter;
    private SecurityService securityService;

    @BeforeEach
    void setUp() {
        authoritiesConverter = new KeycloakJwtGrantedAuthoritiesConverter();
        authenticationConverter = new KeycloakJwtAuthenticationConverter(authoritiesConverter);
        securityService = new SecurityService();
    }

    @Test
    @DisplayName("Should extract SUPERADMIN role and claims from user Keycloak token")
    void shouldExtractSuperAdminRoleFromKeycloakToken() {
        // Arrange - Construct JWT matching exact token payload from user
        Jwt jwt = Jwt.withTokenValue("mock-bearer-token")
                .header("alg", "RS256")
                .header("typ", "JWT")
                .issuer("http://ryan-a1:8180/realms/nextturn")
                .subject("1eebb091-3264-4c21-92d5-40eba7ea15ae")
                .issuedAt(Instant.ofEpochSecond(1792578146))
                .expiresAt(Instant.ofEpochSecond(1792578446))
                .claim("jti", "onrtna:c759ce22-847b-d1d9-80e8-00208490b088")
                .claim("azp", "nextturn-backend")
                .claim("sid", "uxo8aAc9Jtdmv0zJogjLy9dm")
                .claim("email", "sa@nextturn.com")
                .claim("scope", "openid organization")
                .claim("realm_access", Map.of(
                        "roles", List.of("default-roles-nextturn", "offline_access", "uma_authorization")
                ))
                .claim("resource_access", Map.of(
                        "nextturn-backend", Map.of(
                                "roles", List.of("SUPERADMIN")
                        ),
                        "account", Map.of(
                                "roles", List.of("manage-account", "manage-account-links", "view-profile")
                        )
                ))
                .build();

        // Act
        AbstractAuthenticationToken authToken = authenticationConverter.convert(jwt);
        assertThat(authToken).isNotNull();

        SecurityContextHolder.getContext().setAuthentication(authToken);

        // Assert Authorities
        Collection<GrantedAuthority> authorities = authToken.getAuthorities();
        List<String> authorityNames = authorities.stream().map(GrantedAuthority::getAuthority).toList();

        assertThat(authorityNames).contains(
                "ROLE_SUPERADMIN",
                "ROLE_default-roles-nextturn",
                "SCOPE_openid",
                "SCOPE_organization"
        );

        // Assert Principal Name extracted from email claim
        assertThat(authToken.getName()).isEqualTo("sa@nextturn.com");

        // Assert SecurityService helper checks for SUPERADMIN
        assertThat(securityService.hasRole("SUPERADMIN")).isTrue();
        assertThat(securityService.hasRole(UserRole.SUPERADMIN)).isTrue();
        assertThat(securityService.isSuperAdmin()).isTrue();
        assertThat(securityService.isTenantAdmin()).isTrue();
        assertThat(securityService.isCounterStaff()).isTrue();
        assertThat(securityService.isEndUser()).isTrue();

        // Other roles are NOT held
        assertThat(securityService.hasRole("TENANT_ADMIN")).isFalse();
        assertThat(securityService.hasRole(UserRole.TENANT_ADMIN)).isFalse();
        assertThat(securityService.hasRole("COUNTER_STAFF")).isFalse();
        assertThat(securityService.hasRole("END_USER")).isFalse();

        // Identity claims
        assertThat(securityService.getCurrentUserEmail()).contains("sa@nextturn.com");
        assertThat(securityService.getCurrentUserId()).contains("1eebb091-3264-4c21-92d5-40eba7ea15ae");
        assertThat(securityService.getCurrentUserRoles()).containsExactly(UserRole.SUPERADMIN);
    }

    @Test
    @DisplayName("Should extract TENANT_ADMIN role correctly")
    void shouldExtractTenantAdminRole() {
        Jwt jwt = createMockJwtWithRole("TENANT_ADMIN", "admin@tenant1.com");
        AbstractAuthenticationToken authToken = authenticationConverter.convert(jwt);
        SecurityContextHolder.getContext().setAuthentication(authToken);

        assertThat(securityService.hasRole(UserRole.TENANT_ADMIN)).isTrue();
        assertThat(securityService.isTenantAdmin()).isTrue();
        assertThat(securityService.isSuperAdmin()).isFalse();
        assertThat(securityService.getCurrentUserRoles()).containsExactly(UserRole.TENANT_ADMIN);
    }

    @Test
    @DisplayName("Should extract COUNTER_STAFF role correctly")
    void shouldExtractCounterStaffRole() {
        Jwt jwt = createMockJwtWithRole("COUNTER_STAFF", "staff1@tenant1.com");
        AbstractAuthenticationToken authToken = authenticationConverter.convert(jwt);
        SecurityContextHolder.getContext().setAuthentication(authToken);

        assertThat(securityService.hasRole(UserRole.COUNTER_STAFF)).isTrue();
        assertThat(securityService.isCounterStaff()).isTrue();
        assertThat(securityService.isTenantAdmin()).isFalse();
        assertThat(securityService.isSuperAdmin()).isFalse();
        assertThat(securityService.getCurrentUserRoles()).containsExactly(UserRole.COUNTER_STAFF);
    }

    @Test
    @DisplayName("Should extract END_USER role correctly")
    void shouldExtractEndUserRole() {
        Jwt jwt = createMockJwtWithRole("END_USER", "customer@gmail.com");
        AbstractAuthenticationToken authToken = authenticationConverter.convert(jwt);
        SecurityContextHolder.getContext().setAuthentication(authToken);

        assertThat(securityService.hasRole(UserRole.END_USER)).isTrue();
        assertThat(securityService.isEndUser()).isTrue();
        assertThat(securityService.isCounterStaff()).isFalse();
        assertThat(securityService.isTenantAdmin()).isFalse();
        assertThat(securityService.isSuperAdmin()).isFalse();
        assertThat(securityService.getCurrentUserRoles()).containsExactly(UserRole.END_USER);
    }

    private Jwt createMockJwtWithRole(String role, String email) {
        return Jwt.withTokenValue("mock-token")
                .header("alg", "RS256")
                .issuer("http://ryan-a1:8180/realms/nextturn")
                .subject("user-uuid-12345")
                .claim("azp", "nextturn-backend")
                .claim("email", email)
                .claim("resource_access", Map.of(
                        "nextturn-backend", Map.of(
                                "roles", List.of(role)
                        )
                ))
                .build();
    }
}
