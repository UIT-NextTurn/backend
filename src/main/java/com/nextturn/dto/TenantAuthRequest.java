package com.nextturn.dto;

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
    @Size(min = 2, max = 50, message = "Tenant code must be between 2 and 50 characters")
    @Pattern(regexp = "^[a-zA-Z0-9_-]+$", message = "Tenant code must contain only letters, numbers, hyphens, or underscores")
    private String tenantCode;
}
