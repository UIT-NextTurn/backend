package com.nextturn.security;

import com.nextturn.enums.UserRole;
import com.nextturn.security.keycloak.KeycloakJwtAuthenticationConverter;
import com.nextturn.security.keycloak.KeycloakJwtGrantedAuthoritiesConverter;
import com.nextturn.security.keycloak.KeycloakProperties;
import com.nextturn.security.principal.AuthenticatedUser;
import com.nextturn.security.service.SecurityContextHelper;
import com.nextturn.security.service.SecurityService;
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
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class KeycloakJwtAuthenticationConverterTest {

    private KeycloakJwtGrantedAuthoritiesConverter authoritiesConverter;
    private KeycloakJwtAuthenticationConverter authenticationConverter;
    private SecurityContextHelper securityContextHelper;
    private SecurityService securityService;

    @BeforeEach
    void setUp() {
        KeycloakProperties properties = new KeycloakProperties(
                "nextturn-backend",
                "nextturn",
                "http://localhost:8180",
                "http://localhost:8180/realms/nextturn",
                "http://localhost:8180/realms/nextturn/protocol/openid-connect/certs"
        );
        authoritiesConverter = new KeycloakJwtGrantedAuthoritiesConverter(properties);
        authenticationConverter = new KeycloakJwtAuthenticationConverter(authoritiesConverter);
        securityContextHelper = new SecurityContextHelper();
        securityService = new SecurityService(securityContextHelper);
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
        assertThat(securityService.isTenantAdmin()).isFalse();
        assertThat(securityService.isCounterStaff()).isFalse();
        assertThat(securityService.isStaff()).isFalse();
        assertThat(securityService.isEndUser()).isFalse();

        // Other roles are NOT held
        assertThat(securityService.hasRole("TENANT_ADMIN")).isFalse();
        assertThat(securityService.hasRole(UserRole.TENANT_ADMIN)).isFalse();
        assertThat(securityService.hasRole("COUNTER_STAFF")).isFalse();
        assertThat(securityService.hasRole("END_USER")).isFalse();

        // Identity claims
        assertThat(securityService.getCurrentUserEmail()).contains("sa@nextturn.com");
        assertThat(securityService.getCurrentUserId()).contains("1eebb091-3264-4c21-92d5-40eba7ea15ae");
        assertThat(securityService.getCurrentUserRoles()).containsExactly(UserRole.SUPERADMIN);

        // SecurityContextHelper & AuthenticatedUser record
        Optional<AuthenticatedUser> userOpt = securityContextHelper.getCurrentUser();
        assertThat(userOpt).isPresent();
        AuthenticatedUser user = userOpt.get();
        assertThat(user.userId()).isEqualTo("1eebb091-3264-4c21-92d5-40eba7ea15ae");
        assertThat(user.email()).isEqualTo("sa@nextturn.com");
        assertThat(user.isSuperAdmin()).isTrue();
        assertThat(user.isTenantAdmin()).isFalse();
        assertThat(user.isCounterStaff()).isFalse();
        assertThat(user.isStaff()).isFalse();
        assertThat(user.isEndUser()).isFalse();
        assertThat(user.jwt()).isEqualTo(jwt);
    }

    @Test
    @DisplayName("Should extract TENANT_ADMIN role correctly")
    void shouldExtractTenantAdminRole() {
        Jwt jwt = createMockJwtWithRole("TENANT_ADMIN", "admin@tenant1.com", "clinic-01");
        AbstractAuthenticationToken authToken = authenticationConverter.convert(jwt);
        SecurityContextHolder.getContext().setAuthentication(authToken);

        assertThat(securityService.hasRole(UserRole.TENANT_ADMIN)).isTrue();
        assertThat(securityService.isTenantAdmin()).isTrue();
        assertThat(securityService.isStaff()).isTrue();
        assertThat(securityService.isSuperAdmin()).isFalse();
        assertThat(securityService.isCounterStaff()).isFalse();
        assertThat(securityService.isEndUser()).isFalse();
        assertThat(securityService.getCurrentUserRoles()).containsExactly(UserRole.TENANT_ADMIN);

        Optional<AuthenticatedUser> userOpt = securityContextHelper.getCurrentUser();
        assertThat(userOpt).isPresent();
        AuthenticatedUser user = userOpt.get();
        assertThat(user.tenantId()).isEqualTo("clinic-01");
        assertThat(user.isTenantAdmin()).isTrue();
        assertThat(user.isStaff()).isTrue();
        assertThat(user.isSuperAdmin()).isFalse();
        assertThat(user.isCounterStaff()).isFalse();
        assertThat(user.isEndUser()).isFalse();
    }

    @Test
    @DisplayName("Should extract COUNTER_STAFF role correctly")
    void shouldExtractCounterStaffRole() {
        Jwt jwt = createMockJwtWithRole("COUNTER_STAFF", "staff1@tenant1.com", "branch-a");
        AbstractAuthenticationToken authToken = authenticationConverter.convert(jwt);
        SecurityContextHolder.getContext().setAuthentication(authToken);

        assertThat(securityService.hasRole(UserRole.COUNTER_STAFF)).isTrue();
        assertThat(securityService.isCounterStaff()).isTrue();
        assertThat(securityService.isStaff()).isTrue();
        assertThat(securityService.isTenantAdmin()).isFalse();
        assertThat(securityService.isSuperAdmin()).isFalse();
        assertThat(securityService.getCurrentUserRoles()).containsExactly(UserRole.COUNTER_STAFF);

        Optional<AuthenticatedUser> userOpt = securityContextHelper.getCurrentUser();
        assertThat(userOpt).isPresent();
        AuthenticatedUser user = userOpt.get();
        assertThat(user.tenantId()).isEqualTo("branch-a");
        assertThat(user.isCounterStaff()).isTrue();
        assertThat(user.isStaff()).isTrue();
        assertThat(user.isTenantAdmin()).isFalse();
        assertThat(user.isSuperAdmin()).isFalse();
        assertThat(user.isEndUser()).isFalse();
    }

    @Test
    @DisplayName("Should extract END_USER role correctly")
    void shouldExtractEndUserRole() {
        Jwt jwt = createMockJwtWithRole("END_USER", "customer@gmail.com", null);
        AbstractAuthenticationToken authToken = authenticationConverter.convert(jwt);
        SecurityContextHolder.getContext().setAuthentication(authToken);

        assertThat(securityService.hasRole(UserRole.END_USER)).isTrue();
        assertThat(securityService.isEndUser()).isTrue();
        assertThat(securityService.isStaff()).isFalse();
        assertThat(securityService.isCounterStaff()).isFalse();
        assertThat(securityService.isTenantAdmin()).isFalse();
        assertThat(securityService.isSuperAdmin()).isFalse();
        assertThat(securityService.getCurrentUserRoles()).containsExactly(UserRole.END_USER);

        Optional<AuthenticatedUser> userOpt = securityContextHelper.getCurrentUser();
        assertThat(userOpt).isPresent();
        AuthenticatedUser user = userOpt.get();
        assertThat(user.isEndUser()).isTrue();
        assertThat(user.isStaff()).isFalse();
        assertThat(user.isCounterStaff()).isFalse();
        assertThat(user.isTenantAdmin()).isFalse();
        assertThat(user.isSuperAdmin()).isFalse();
    }

    private Jwt createMockJwtWithRole(String role, String email, String tenantId) {
        var jwtBuilder = Jwt.withTokenValue("mock-token")
                .header("alg", "RS256")
                .issuer("http://ryan-a1:8180/realms/nextturn")
                .subject("user-uuid-12345")
                .claim("azp", "nextturn-backend")
                .claim("email", email)
                .claim("resource_access", Map.of(
                        "nextturn-backend", Map.of(
                                "roles", List.of(role)
                        )
                ));

        if (tenantId != null) {
            jwtBuilder.claim("tenant_id", tenantId);
        }

        return jwtBuilder.build();
    }
}
