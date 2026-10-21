package com.nextturn.security;

import com.nextturn.security.principal.AuthenticatedUser;
import com.nextturn.security.service.SecurityContextHelper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class SecurityContextHelperTest {

    private SecurityContextHelper helper;

    @BeforeEach
    void setUp() {
        helper = new SecurityContextHelper();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("Should return empty when SecurityContext is empty")
    void shouldReturnEmptyWhenNoAuth() {
        assertThat(helper.getCurrentUser()).isEmpty();
        assertThat(helper.getCurrentUserId()).isEmpty();
        assertThat(helper.getCurrentUserEmail()).isEmpty();
        assertThat(helper.getCurrentTenantId()).isEmpty();
        assertThat(helper.isAuthenticated()).isFalse();
    }

    @Test
    @DisplayName("Should return empty when user is anonymous")
    void shouldReturnEmptyForAnonymousUser() {
        AnonymousAuthenticationToken anon = new AnonymousAuthenticationToken(
                "key", "anonymousUser", AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS")
        );
        SecurityContextHolder.getContext().setAuthentication(anon);

        assertThat(helper.getCurrentUser()).isEmpty();
        assertThat(helper.isAuthenticated()).isFalse();
    }

    @Test
    @DisplayName("Should extract tenantId from various claim formats")
    void shouldExtractTenantIdFromDifferentClaims() {
        // Test organization map claim
        Jwt jwtWithOrgMap = Jwt.withTokenValue("mock")
                .header("alg", "none")
                .subject("u-1")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60))
                .claim("email", "doc@clinic.com")
                .claim("organization", Map.of("id", "org-uuid-99", "name", "Downtown Clinic"))
                .build();

        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwtWithOrgMap, Collections.emptyList()));
        Optional<AuthenticatedUser> user1 = helper.getCurrentUser();
        assertThat(user1).isPresent();
        assertThat(user1.get().tenantId()).isEqualTo("org-uuid-99");
        assertThat(helper.getCurrentTenantId()).contains("org-uuid-99");

        // Test tenant_code claim
        Jwt jwtWithTenantCode = Jwt.withTokenValue("mock2")
                .header("alg", "none")
                .subject("u-2")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60))
                .claim("tenant_code", "hospital-north")
                .build();

        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwtWithTenantCode, Collections.emptyList()));
        Optional<AuthenticatedUser> user2 = helper.getCurrentUser();
        assertThat(user2).isPresent();
        assertThat(user2.get().tenantId()).isEqualTo("hospital-north");
    }
}
