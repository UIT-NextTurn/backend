package com.nextturn.controller.tenant;

import com.nextturn.dto.BaseResponse;
import com.nextturn.dto.tenant.TenantRegistrationRequest;
import com.nextturn.dto.tenant.TenantResponse;
import com.nextturn.service.tenant.TenantService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public endpoint exposing enterprise tenant self-registration (REQ-TNT-05).
 * Allows business representatives to submit their clinic/enterprise registration application without authentication.
 */
@RestController
@RequestMapping("/api/v1/public/tenants")
@Tag(name = "Tenant Public Registration", description = "Public self-registration API for enterprise organizations (REQ-TNT-05)")
@SecurityRequirements
public class TenantPublicRegistrationController {

    private static final Logger log = LoggerFactory.getLogger(TenantPublicRegistrationController.class);

    private final TenantService tenantService;

    public TenantPublicRegistrationController(TenantService tenantService) {
        this.tenantService = tenantService;
    }

    @PostMapping("/register")
    @Operation(
            summary = "Enterprise tenant self-registration (REQ-TNT-05)",
            description = "Submits a new facility registration application. The tenant is placed in PENDING_APPROVAL status awaiting Superadministrator review."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Application successfully received and awaiting approval",
                    content = @Content(schema = @Schema(implementation = BaseResponse.class))),
            @ApiResponse(responseCode = "400", description = "Invalid request format or input validation error"),
            @ApiResponse(responseCode = "409", description = "Tenant code or administrator email already registered")
    })
    public ResponseEntity<BaseResponse<TenantResponse>> registerTenant(
            @Valid @RequestBody TenantRegistrationRequest request
    ) {
        log.info("Public tenant registration request received for code [{}] by email [{}]",
                request.getTenantCode(), request.getAdminEmail());

        TenantResponse response = tenantService.registerTenant(request);

        return ResponseEntity.status(HttpStatus.CREATED).body(
                BaseResponse.<TenantResponse>builder()
                        .success(true)
                        .message("Tenant registration application submitted successfully and is awaiting administrator review.")
                        .data(response)
                        .build()
        );
    }
}
