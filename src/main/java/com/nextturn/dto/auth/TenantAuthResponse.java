package com.nextturn.dto.auth;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Response payload for REQ-AUTH-01 providing tenant details and staff login routing URL.
 * Specifically scoped for tenant personnel (TENANT_ADMIN and COUNTER_STAFF).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class TenantAuthResponse {

    private String tenantId;
    private String tenantCode;
    private String tenantName;
    private String status;
    private String organisationId;
    private String loginUrl;
    private List<String> allowedRoles;
}
