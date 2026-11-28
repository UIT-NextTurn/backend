package com.nextturn.dto.dashboard;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Response DTO containing a short-lived, single-use opaque token for opening browser {@code EventSource}
 * connections to authenticated Dashboard SSE streams without custom request headers.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Short-lived single-use SSE stream token response for browser EventSource authentication")
public class DashboardStreamTokenResponse {

    @Schema(description = "Opaque single-use token to pass as ?streamToken=... on the SSE URL")
    private String streamToken;

    @Schema(description = "Token lifetime in seconds (default 30s)", example = "30")
    private long expiresInSeconds;

    @Schema(description = "UTC/application timestamp when the stream token expires")
    private LocalDateTime expiresAt;

    @Schema(description = "Bound stream scope (TENANT_DASHBOARD or PLATFORM_DASHBOARD)", example = "TENANT_DASHBOARD")
    private String scope;

    @Schema(description = "Ready-to-use relative SSE stream URL including the streamToken query parameter")
    private String streamUrl;
}
