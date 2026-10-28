package com.nextturn.infrastructure.security.service;

import com.nextturn.enums.UserRole;
import com.nextturn.infrastructure.security.principal.AuthenticatedUser;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Utility and Spring component for accessing the current user identity and SecurityContext details.
 */
@Component
public class SecurityContextHelper {

    /**
     * Resolves the current {@link AuthenticatedUser} from the active SecurityContext.
     */
    public Optional<AuthenticatedUser> getCurrentUser() {
        return resolveAuthenticatedUser();
    }

    /**
     * Static utility overload to resolve {@link AuthenticatedUser} from the active SecurityContext.
     */
    public static Optional<AuthenticatedUser> resolveCurrentUser() {
        return resolveAuthenticatedUser();
    }

    /**
     * Retrieves the current user ID (JWT sub claim).
     */
    public Optional<String> getCurrentUserId() {
        return getCurrentUser().map(AuthenticatedUser::userId);
    }

    /**
     * Retrieves the current user email.
     */
    public Optional<String> getCurrentUserEmail() {
        return getCurrentUser().map(AuthenticatedUser::email);
    }

    /**
     * Retrieves the current user's tenant ID, if present.
     */
    public Optional<String> getCurrentTenantId() {
        return getCurrentUser().map(AuthenticatedUser::tenantId).filter(s -> !s.isBlank());
    }

    /**
     * Retrieves the current user roles.
     */
    public Set<UserRole> getCurrentUserRoles() {
        return getCurrentUser().map(AuthenticatedUser::roles).orElseGet(Collections::emptySet);
    }

    /**
     * Retrieves the current JWT token if the authentication is a {@link JwtAuthenticationToken}.
     */
    public Optional<Jwt> getCurrentJwt() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof JwtAuthenticationToken jwtAuth) {
            return Optional.of(jwtAuth.getToken());
        }
        return Optional.empty();
    }

    /**
     * Checks if the current request is authenticated.
     */
    public boolean isAuthenticated() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.isAuthenticated() && !"anonymousUser".equals(auth.getPrincipal());
    }

    private static Optional<AuthenticatedUser> resolveAuthenticatedUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getPrincipal())) {
            return Optional.empty();
        }

        Set<UserRole> roles = extractRoles(auth);

        if (auth instanceof JwtAuthenticationToken jwtAuth) {
            Jwt jwt = jwtAuth.getToken();
            String userId = jwt.getSubject();
            String email = extractEmail(jwt);
            String tenantId = extractTenantId(jwt);
            return Optional.of(new AuthenticatedUser(userId, email, tenantId, roles, jwt));
        }

        // Fallback for non-JWT authentication (e.g. mock test tokens)
        String principalName = auth.getName();
        return Optional.of(new AuthenticatedUser(principalName, principalName, null, roles, null));
    }

    private static Set<UserRole> extractRoles(Authentication auth) {
        if (auth.getAuthorities() == null) {
            return Collections.emptySet();
        }
        return auth.getAuthorities().stream()
                .filter(Objects::nonNull)
                .map(GrantedAuthority::getAuthority)
                .map(UserRole::fromString)
                .flatMap(Optional::stream)
                .collect(Collectors.toSet());
    }

    private static String extractEmail(Jwt jwt) {
        if (jwt.hasClaim("email") && jwt.getClaimAsString("email") != null) {
            return jwt.getClaimAsString("email");
        }
        if (jwt.hasClaim("preferred_username") && jwt.getClaimAsString("preferred_username") != null) {
            return jwt.getClaimAsString("preferred_username");
        }
        return jwt.getSubject();
    }

    private static String extractTenantId(Jwt jwt) {
        if (jwt.hasClaim("tenant_id") && jwt.getClaimAsString("tenant_id") != null) {
            return jwt.getClaimAsString("tenant_id");
        }
        if (jwt.hasClaim("tenantId") && jwt.getClaimAsString("tenantId") != null) {
            return jwt.getClaimAsString("tenantId");
        }
        if (jwt.hasClaim("tenant_code") && jwt.getClaimAsString("tenant_code") != null) {
            return jwt.getClaimAsString("tenant_code");
        }
        if (jwt.hasClaim("tenant") && jwt.getClaimAsString("tenant") != null) {
            return jwt.getClaimAsString("tenant");
        }

        // Support Keycloak organization claim (string, collection, or map)
        Object orgClaim = jwt.getClaim("organization");
        if (orgClaim instanceof String orgStr && !orgStr.isBlank()) {
            return orgStr;
        } else if (orgClaim instanceof Collection<?> orgList && !orgList.isEmpty()) {
            Object first = orgList.iterator().next();
            if (first instanceof String firstStr && !firstStr.isBlank()) {
                return firstStr;
            } else if (first instanceof Map<?, ?> firstMap) {
                Object id = firstMap.get("id");
                if (id != null) return id.toString();
                Object alias = firstMap.get("alias");
                if (alias != null) return alias.toString();
                Object name = firstMap.get("name");
                if (name != null) return name.toString();
            }
        } else if (orgClaim instanceof Map<?, ?> orgMap) {
            Object id = orgMap.get("id");
            if (id != null) {
                return id.toString();
            }
            Object alias = orgMap.get("alias");
            if (alias != null) {
                return alias.toString();
            }
            Object name = orgMap.get("name");
            if (name != null) {
                return name.toString();
            }
            if (!orgMap.isEmpty()) {
                return orgMap.keySet().iterator().next().toString();
            }
        }
        return null;
    }
}
