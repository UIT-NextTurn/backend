package com.nextturn.infrastructure.security.filter;

import com.nextturn.constant.DashboardEndpointConstant;
import com.nextturn.infrastructure.logging.LogContract;
import com.nextturn.infrastructure.logging.LogDomain;
import com.nextturn.infrastructure.logging.LogOutcome;
import com.nextturn.service.dashboard.DashboardStreamTokenService;
import com.nextturn.service.dashboard.DashboardStreamTokenService.ConsumedStreamPrincipal;
import com.nextturn.service.dashboard.DashboardStreamTokenService.StreamScope;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.lang.NonNull;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Security filter authenticating browser {@code EventSource} handshakes to Dashboard SSE streams
 * via a short-lived, single-use {@code ?streamToken=...} query parameter when no {@code Authorization}
 * header is present.
 * <p>
 * Populates a scoped {@link JwtAuthenticationToken} so downstream {@code TenantContextFilter},
 * {@code @RequireTenantAdmin}, {@code @RequireSuperAdmin}, and {@code SecurityContextHelper}
 * operate identically to standard Bearer JWT requests.
 */
@Component
public class DashboardStreamTokenFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(DashboardStreamTokenFilter.class);

    public static final String STREAM_TOKEN_PARAM = "streamToken";
    private static final String TENANT_STREAM_URI =
            DashboardEndpointConstant.TENANT_DASHBOARD_BASE + DashboardEndpointConstant.OVERVIEW_STREAM;
    private static final String PLATFORM_STREAM_URI =
            DashboardEndpointConstant.CENTRAL_DASHBOARD_BASE + DashboardEndpointConstant.OVERVIEW_STREAM;

    private final DashboardStreamTokenService streamTokenService;

    @Autowired
    public DashboardStreamTokenFilter(@Autowired(required = false) DashboardStreamTokenService streamTokenService) {
        this.streamTokenService = streamTokenService;
    }

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain
    ) throws ServletException, IOException {
        if (streamTokenService != null && isEligibleRequest(request)) {
            StreamScope expectedScope = resolveExpectedScope(request.getRequestURI());
            String rawToken = request.getParameter(STREAM_TOKEN_PARAM);

            if (expectedScope != null && rawToken != null && !rawToken.isBlank()) {
                Optional<ConsumedStreamPrincipal> consumedOpt = streamTokenService.consumeToken(rawToken, expectedScope);
                if (consumedOpt.isPresent()) {
                    ConsumedStreamPrincipal principal = consumedOpt.get();
                    JwtAuthenticationToken authentication = buildAuthentication(principal);
                    SecurityContextHolder.getContext().setAuthentication(authentication);

                    LogContract.debug(log, LogDomain.SECURITY, "sse_stream_token_authenticated")
                            .outcome(LogOutcome.SUCCESS)
                            .tenant(principal.tenantCode())
                            .field("scope", expectedScope.name())
                            .emit();
                } else {
                    LogContract.warn(log, LogDomain.SECURITY, "sse_stream_token_rejected")
                            .outcome(LogOutcome.REJECTED)
                            .field("uri", request.getRequestURI())
                            .field("scope", expectedScope.name())
                            .emit();
                }
            }
        }

        filterChain.doFilter(request, response);
    }

    private boolean isEligibleRequest(HttpServletRequest request) {
        if (!HttpMethod.GET.matches(request.getMethod())) {
            return false;
        }
        String authHeader = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (authHeader != null && !authHeader.isBlank()) {
            return false;
        }
        Authentication existing = SecurityContextHolder.getContext().getAuthentication();
        return existing == null || !existing.isAuthenticated();
    }

    private StreamScope resolveExpectedScope(String uri) {
        if (TENANT_STREAM_URI.equals(uri)) {
            return StreamScope.TENANT_DASHBOARD;
        }
        if (PLATFORM_STREAM_URI.equals(uri)) {
            return StreamScope.PLATFORM_DASHBOARD;
        }
        return null;
    }

    private JwtAuthenticationToken buildAuthentication(ConsumedStreamPrincipal principal) {
        Instant now = Instant.now();
        String subject = (principal.userId() != null && !principal.userId().isBlank())
                ? principal.userId()
                : "sse-user";
        Jwt.Builder jwtBuilder = Jwt.withTokenValue("sse-stream-token")
                .header("alg", "none")
                .subject(subject)
                .claim("stream_scope", principal.scope().name())
                .issuedAt(now)
                .expiresAt(now.plusSeconds(30));

        if (principal.email() != null && !principal.email().isBlank()) {
            jwtBuilder.claim("email", principal.email());
        }
        if (principal.tenantCode() != null && !principal.tenantCode().isBlank()) {
            jwtBuilder.claim("tenant_code", principal.tenantCode());
        }

        List<SimpleGrantedAuthority> authorities = principal.authorities().stream()
                .map(SimpleGrantedAuthority::new)
                .toList();

        return new JwtAuthenticationToken(jwtBuilder.build(), authorities, subject);
    }
}
