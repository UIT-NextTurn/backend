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

    private final com.nextturn.repository.auth.CentralTenantRepository centralTenantRepository;

    public TenantContextFilter(
            @org.springframework.beans.factory.annotation.Autowired(required = false)
            com.nextturn.repository.auth.CentralTenantRepository centralTenantRepository
    ) {
        this.centralTenantRepository = centralTenantRepository;
    }

    public TenantContextFilter() {
        this(null);
    }

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
        Jwt jwt = null;
        if (authentication instanceof JwtAuthenticationToken jwtAuth) {
            jwt = jwtAuth.getToken();
        } else if (authentication != null && authentication.getPrincipal() instanceof Jwt principalJwt) {
            jwt = principalJwt;
        }

        if (jwt != null) {
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

        // 3. Fallback to Central Tenant lookup by authenticated user's email
        if (centralTenantRepository != null && jwt != null && jwt.hasClaim("email")) {
            String email = jwt.getClaimAsString("email");
            if (email != null && !email.isBlank()) {
                String cleanEmail = email.trim();
                var tenantOpt = centralTenantRepository.findByAdminEmailIgnoreCaseAndIsDeletedFalse(cleanEmail);
                if (tenantOpt.isPresent()) {
                    return tenantOpt.get().getTenantCode();
                }

                // Check domain format e.g. admin@shop-b.internal or counter01@shop-b.internal
                if (cleanEmail.contains("@") && cleanEmail.endsWith(".internal")) {
                    String domain = cleanEmail.substring(cleanEmail.indexOf('@') + 1);
                    String potentialCode = domain.substring(0, domain.indexOf(".internal")).trim();
                    if (centralTenantRepository.existsByTenantCodeIgnoreCaseAndIsDeletedFalse(potentialCode)) {
                        return potentialCode;
                    }
                }
            }
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

        // 3. Keycloak organization claim (string, map, or collection)
        Object orgClaim = jwt.getClaim(ORGANIZATION_CLAIM);
        if (orgClaim instanceof String orgStr && !orgStr.isBlank()) {
            return orgStr.trim();
        } else if (orgClaim instanceof java.util.Collection<?> orgColl && !orgColl.isEmpty()) {
            Object first = orgColl.iterator().next();
            if (first instanceof String firstStr && !firstStr.isBlank()) {
                return firstStr.trim();
            } else if (first instanceof Map<?, ?> firstMap) {
                String parsed = parseOrgMap(firstMap);
                if (parsed != null) return parsed;
            }
        } else if (orgClaim instanceof Map<?, ?> orgMap) {
            String parsed = parseOrgMap(orgMap);
            if (parsed != null) return parsed;
            if (!orgMap.isEmpty()) {
                Object firstKey = orgMap.keySet().iterator().next();
                if (firstKey != null && !firstKey.toString().isBlank()) {
                    return firstKey.toString().trim();
                }
            }
        }

        return null;
    }

    private static String parseOrgMap(Map<?, ?> orgMap) {
        if (orgMap.containsKey("alias") && orgMap.get("alias") != null) {
            return orgMap.get("alias").toString().trim();
        }
        if (orgMap.containsKey("name") && orgMap.get("name") != null) {
            return orgMap.get("name").toString().trim();
        }
        if (orgMap.containsKey("id") && orgMap.get("id") != null) {
            return orgMap.get("id").toString().trim();
        }
        return null;
    }
}
