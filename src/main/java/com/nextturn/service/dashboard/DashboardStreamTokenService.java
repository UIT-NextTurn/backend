package com.nextturn.service.dashboard;

import com.nextturn.dto.dashboard.DashboardStreamTokenResponse;
import com.nextturn.infrastructure.security.principal.AuthenticatedUser;
import org.springframework.security.core.GrantedAuthority;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Contract for minting and atomically consuming short-lived, single-use opaque tokens bound to
 * {@code (userId, tenantCode, streamScope)} so browser {@code EventSource} clients can open
 * authenticated Dashboard SSE streams without sending an {@code Authorization} header.
 */
public interface DashboardStreamTokenService {

    enum StreamScope {
        TENANT_DASHBOARD,
        PLATFORM_DASHBOARD
    }

    record ConsumedStreamPrincipal(
            String userId,
            String email,
            String tenantCode,
            StreamScope scope,
            List<String> authorities
    ) {
    }

    /**
     * Issues a short-lived single-use stream token bound to the caller's identity, tenant, role authorities, and scope.
     */
    DashboardStreamTokenResponse issueToken(
            AuthenticatedUser caller,
            Collection<? extends GrantedAuthority> authorities,
            StreamScope scope,
            String tenantCode
    );

    /**
     * Atomically consumes and validates a single-use stream token for the expected stream scope.
     * Returns empty if the token is missing, unknown, expired, already used, or bound to a different scope.
     */
    Optional<ConsumedStreamPrincipal> consumeToken(String rawToken, StreamScope expectedScope);
}
