package com.nextturn.infrastructure.security.keycloak;

import com.nextturn.constant.KeycloakClaimConstant;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Custom JWT GrantedAuthoritiesConverter for Keycloak access tokens.
 * Extracts client-specific roles from `resource_access[clientId].roles`
 * (e.g. `resource_access.nextturn-backend.roles` -> SUPERADMIN)
 * and realm roles from `realm_access.roles`, mapping them to Spring Security `ROLE_*` authorities.
 */
@Component
public class KeycloakJwtGrantedAuthoritiesConverter implements Converter<Jwt, Collection<GrantedAuthority>> {

    private static final String DEFAULT_CLIENT_ID = KeycloakClaimConstant.DEFAULT_CLIENT_ID;
    private static final String RESOURCE_ACCESS_CLAIM = KeycloakClaimConstant.RESOURCE_ACCESS_CLAIM;
    private static final String REALM_ACCESS_CLAIM = KeycloakClaimConstant.REALM_ACCESS_CLAIM;
    private static final String ROLES_CLAIM = KeycloakClaimConstant.ROLES_CLAIM;
    private static final String AZP_CLAIM = KeycloakClaimConstant.AZP_CLAIM;
    private static final String ROLE_PREFIX = KeycloakClaimConstant.ROLE_PREFIX;

    private final JwtGrantedAuthoritiesConverter defaultScopesConverter = new JwtGrantedAuthoritiesConverter();
    private final KeycloakProperties keycloakProperties;

    @org.springframework.beans.factory.annotation.Autowired
    public KeycloakJwtGrantedAuthoritiesConverter(KeycloakProperties keycloakProperties) {
        this.keycloakProperties = keycloakProperties;
    }

    public KeycloakJwtGrantedAuthoritiesConverter() {
        this(new KeycloakProperties("nextturn-backend", "", "nextturn", "http://localhost:8180", null, null));
    }

    @Override
    public Collection<GrantedAuthority> convert(Jwt jwt) {
        Set<GrantedAuthority> authorities = new HashSet<>();

        // 1. Include default OAuth2 scopes (e.g. SCOPE_openid, SCOPE_organization)
        Collection<GrantedAuthority> scopeAuthorities = defaultScopesConverter.convert(jwt);
        if (scopeAuthorities != null) {
            authorities.addAll(scopeAuthorities);
        }

        // 2. Extract Client Roles from resource_access (primary location for NextTurn client roles)
        extractClientRoles(jwt, authorities);

        // 3. Extract Realm Roles from realm_access
        extractRealmRoles(jwt, authorities);

        return authorities;
    }

    @SuppressWarnings("unchecked")
    private void extractClientRoles(Jwt jwt, Set<GrantedAuthority> authorities) {
        Map<String, Object> resourceAccess = jwt.getClaim(RESOURCE_ACCESS_CLAIM);
        if (resourceAccess == null || resourceAccess.isEmpty()) {
            return;
        }

        String targetClientId = keycloakProperties != null && keycloakProperties.clientId() != null
                ? keycloakProperties.clientId()
                : DEFAULT_CLIENT_ID;

        // Check configured client ID
        extractRolesFromClientEntry(resourceAccess.get(targetClientId), authorities);

        // Also check azp (Authorized Party) if distinct from configured client ID
        String azp = jwt.getClaimAsString(AZP_CLAIM);
        if (azp != null && !azp.equalsIgnoreCase(targetClientId)) {
            extractRolesFromClientEntry(resourceAccess.get(azp), authorities);
        }
    }

    @SuppressWarnings("unchecked")
    private void extractRolesFromClientEntry(Object clientEntry, Set<GrantedAuthority> authorities) {
        if (!(clientEntry instanceof Map<?, ?> clientMap)) {
            return;
        }
        Object rolesObj = clientMap.get(ROLES_CLAIM);
        if (rolesObj instanceof List<?> roleList) {
            for (Object role : roleList) {
                if (role instanceof String roleStr && !roleStr.isBlank()) {
                    authorities.add(mapToRoleAuthority(roleStr));
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void extractRealmRoles(Jwt jwt, Set<GrantedAuthority> authorities) {
        Map<String, Object> realmAccess = jwt.getClaim(REALM_ACCESS_CLAIM);
        if (realmAccess == null || realmAccess.isEmpty()) {
            return;
        }
        Object rolesObj = realmAccess.get(ROLES_CLAIM);
        if (rolesObj instanceof List<?> roleList) {
            for (Object role : roleList) {
                if (role instanceof String roleStr && !roleStr.isBlank()) {
                    authorities.add(mapToRoleAuthority(roleStr));
                }
            }
        }
    }

    private GrantedAuthority mapToRoleAuthority(String rawRole) {
        String cleanRole = rawRole.trim();
        if (cleanRole.startsWith(ROLE_PREFIX)) {
            return new SimpleGrantedAuthority(cleanRole);
        }
        return new SimpleGrantedAuthority(ROLE_PREFIX + cleanRole);
    }
}
