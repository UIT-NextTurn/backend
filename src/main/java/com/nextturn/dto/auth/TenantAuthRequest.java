package com.nextturn.dto.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request payload for REQ-AUTH-01: Verifying tenant code and resolving staff login route.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TenantAuthRequest {

    @NotBlank(message = "Tenant code is required")
    @Size(min = 3, max = 30, message = "Tenant code must be between 3 and 30 characters")
    @Pattern(regexp = "^[a-z0-9-]+$", message = "Tenant code must contain only lowercase letters, numbers, and hyphens")
    private String tenantCode;
}
