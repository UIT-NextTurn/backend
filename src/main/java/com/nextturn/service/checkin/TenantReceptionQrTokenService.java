package com.nextturn.service.checkin;

import com.nextturn.dto.checkin.ReceptionQrResponse;
import com.nextturn.entity.TenantReceptionPoint;

/**
 * Contract for generating and validating static or 60-second rotating dynamic reception QR tokens
 * (REQ-CHECK-IN-03).
 */
public interface TenantReceptionQrTokenService {

    /**
     * Generates a high-entropy HMAC secret key for a newly registered reception point.
     */
    String generateQrSecret();

    /**
     * Builds a persistent static QR token scoped to the tenant code.
     */
    String buildStaticToken(String tenantCode);

    /**
     * Extracts the embedded tenant code from a dynamic ({@code NTRD.*}) or static ({@code NTRS.*})
     * reception QR token, returning {@code null} if not encoded.
     */
    String extractTenantCodeFromToken(String rawToken);

    /**
     * Generates the active QR token, deep-link URL, and 60-second rotation countdown metadata
     * for the given reception point.
     */
    ReceptionQrResponse generateQrPayload(TenantReceptionPoint point, String tenantCode);

    /**
     * Validates the authenticity, active status, and 60-second dynamic expiration window
     * of a scanned reception QR token and returns its backing {@link TenantReceptionPoint}.
     */
    TenantReceptionPoint validateAndResolveToken(String rawToken);
}
