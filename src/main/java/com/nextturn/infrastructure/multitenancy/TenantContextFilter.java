package com.nextturn.infrastructure.multitenancy;

import com.nextturn.constant.TenantClaimConstant;
import com.nextturn.constant.TenantHeaderConstant;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.NonNull;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Map;

/**
 * Filter that resolves tenant context from the authenticated JWT token claims
 * (specifically {@code tenant_code}) and sets it in {@link TenantContextHolder}.
 * <p>
 * Ensures {@link TenantContextHolder#clear()} is called in a {@code finally} block
 * to prevent thread-pool memory leaks (*rò rỉ bộ nhớ luồng*).
 */
@Component
public class TenantContextFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(TenantContextFilter.class);

    // Multi-tenancy constants delegated to dedicated constant classes
    public static final String TENANT_CODE_CLAIM = TenantClaimConstant.TENANT_CODE_CLAIM;
    public static final String TENANT_ID_CLAIM = TenantClaimConstant.TENANT_ID_CLAIM;
    public static final String TENANT_ID_CAMEL_CLAIM = TenantClaimConstant.TENANT_ID_CAMEL_CLAIM;
    public static final String TENANT_CLAIM = TenantClaimConstant.TENANT_CLAIM;
    public static final String ORGANIZATION_CLAIM = TenantClaimConstant.ORGANIZATION_CLAIM;
    public static final String X_TENANT_CODE_HEADER = TenantHeaderConstant.X_TENANT_CODE_HEADER;
    public static final String X_TENANT_ID_HEADER = TenantHeaderConstant.X_TENANT_ID_HEADER;

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain
    ) throws ServletException, IOException {
        try {
            String tenantCode = resolveTenantCode(request);
            if (tenantCode != null && !tenantCode.isBlank()) {
                TenantContextHolder.setTenantCode(tenantCode.trim());
                log.debug("Bound tenant context: [{}] for URI: [{}]", tenantCode, request.getRequestURI());
            }
            filterChain.doFilter(request, response);
        } finally {
            TenantContextHolder.clear();
            log.trace("Cleared tenant context for URI: [{}]", request.getRequestURI());
        }
    }

    /**
     * Resolves the tenant code from the active SecurityContext JWT or fallback headers.
     *
     * @param request the current HTTP servlet request
     * @return the extracted tenant code, or {@code null} if none is found
     */
    protected String resolveTenantCode(HttpServletRequest request) {
        // 1. Extract from active SecurityContext authentication
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof JwtAuthenticationToken jwtAuth) {
            String tenantFromJwt = extractTenantCodeFromJwt(jwtAuth.getToken());
            if (tenantFromJwt != null && !tenantFromJwt.isBlank()) {
                return tenantFromJwt;
            }
        } else if (authentication != null && authentication.getPrincipal() instanceof Jwt jwt) {
            String tenantFromJwt = extractTenantCodeFromJwt(jwt);
            if (tenantFromJwt != null && !tenantFromJwt.isBlank()) {
                return tenantFromJwt;
            }
        }

        // 2. Fallback to custom HTTP headers
        String headerTenant = request.getHeader(X_TENANT_CODE_HEADER);
        if (headerTenant != null && !headerTenant.isBlank()) {
            return headerTenant.trim();
        }
        String headerTenantId = request.getHeader(X_TENANT_ID_HEADER);
        if (headerTenantId != null && !headerTenantId.isBlank()) {
            return headerTenantId.trim();
        }

        return null;
    }

    /**
     * Extracts the tenant identifier from JWT claims, with highest precedence on {@code tenant_code}.
     *
     * @param jwt the decoded JWT token
     * @return the tenant identifier or {@code null}
     */
    public static String extractTenantCodeFromJwt(Jwt jwt) {
        if (jwt == null) {
            return null;
        }

        // 1. Primary claim: "tenant_code"
        if (jwt.hasClaim(TENANT_CODE_CLAIM)) {
            String val = jwt.getClaimAsString(TENANT_CODE_CLAIM);
            if (val != null && !val.isBlank()) {
                return val.trim();
            }
        }

        // 2. Secondary standard fallbacks: "tenant_id", "tenantId", "tenant"
        if (jwt.hasClaim(TENANT_ID_CLAIM)) {
            String val = jwt.getClaimAsString(TENANT_ID_CLAIM);
            if (val != null && !val.isBlank()) {
                return val.trim();
            }
        }
        if (jwt.hasClaim(TENANT_ID_CAMEL_CLAIM)) {
            String val = jwt.getClaimAsString(TENANT_ID_CAMEL_CLAIM);
            if (val != null && !val.isBlank()) {
                return val.trim();
            }
        }
        if (jwt.hasClaim(TENANT_CLAIM)) {
            String val = jwt.getClaimAsString(TENANT_CLAIM);
            if (val != null && !val.isBlank()) {
                return val.trim();
            }
        }

        // 3. Keycloak organization claim (string or map)
        Object orgClaim = jwt.getClaim(ORGANIZATION_CLAIM);
        if (orgClaim instanceof String orgStr && !orgStr.isBlank()) {
            return orgStr.trim();
        } else if (orgClaim instanceof Map<?, ?> orgMap) {
            Object id = orgMap.get("id");
            if (id != null && !id.toString().isBlank()) {
                return id.toString().trim();
            }
            Object name = orgMap.get("name");
            if (name != null && !name.toString().isBlank()) {
                return name.toString().trim();
            }
        }

        return null;
    }
}
