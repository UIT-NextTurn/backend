package com.nextturn.security.keycloak;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

import java.util.Collection;

/**
 * Custom JWT Authentication Converter for Keycloak resource server.
 * Uses KeycloakJwtGrantedAuthoritiesConverter to extract roles and scopes,
 * and extracts the user identifier (email, preferred_username, or sub) as the principal name.
 */
@Component
public class KeycloakJwtAuthenticationConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    private final KeycloakJwtGrantedAuthoritiesConverter authoritiesConverter;

    public KeycloakJwtAuthenticationConverter(KeycloakJwtGrantedAuthoritiesConverter authoritiesConverter) {
        this.authoritiesConverter = authoritiesConverter;
    }

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        Collection<GrantedAuthority> authorities = authoritiesConverter.convert(jwt);
        String principalClaimValue = extractPrincipalName(jwt);
        return new JwtAuthenticationToken(jwt, authorities, principalClaimValue);
    }

    private String extractPrincipalName(Jwt jwt) {
        if (jwt.hasClaim("email") && jwt.getClaimAsString("email") != null) {
            return jwt.getClaimAsString("email");
        }
        if (jwt.hasClaim("preferred_username") && jwt.getClaimAsString("preferred_username") != null) {
            return jwt.getClaimAsString("preferred_username");
        }
        return jwt.getSubject();
    }
}
