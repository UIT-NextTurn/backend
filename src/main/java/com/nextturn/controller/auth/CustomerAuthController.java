package com.nextturn.controller.auth;

import com.nextturn.dto.BaseResponse;
import com.nextturn.dto.auth.AuthTokenResponse;
import com.nextturn.dto.auth.CustomerOtpResponse;
import com.nextturn.dto.auth.CustomerSendOtpRequest;
import com.nextturn.dto.auth.CustomerVerifyOtpRequest;
import com.nextturn.service.auth.CustomerAuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller managing passwordless authentication for End Users (Public Customers).
 * <p>
 * Implements:
 * 1. Email OTP request / dispatch.
 * 2. OTP verification with idempotent database user lookup (preserving history).
 * 3. Centralized Keycloak authentication returning official tokens with END_USER role.
 */
@RestController
@RequestMapping("/api/v1/auth/customer")
@Tag(name = "Customer Authentication", description = "Passwordless Email OTP authentication for public customers (END_USER)")
@SecurityRequirements
public class CustomerAuthController {

    private final CustomerAuthService customerAuthService;

    public CustomerAuthController(CustomerAuthService customerAuthService) {
        this.customerAuthService = customerAuthService;
    }

    /**
     * Dispatches an OTP verification code to the customer's email.
     *
     * @param request email payload
     * @return {@link BaseResponse} containing {@link CustomerOtpResponse}
     */
    @PostMapping({"/otp/send", "/otp/request"})
    @Operation(
            summary = "Request email OTP verification code",
            description = "Generates and sends a 6-digit OTP code to the customer's email. If the email already exists in the database, it flags it as an existing account."
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "OTP sent successfully",
                    content = @Content(schema = @Schema(implementation = BaseResponse.class))
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "Invalid or blank email format",
                    content = @Content(schema = @Schema(implementation = BaseResponse.class))
            ),
            @ApiResponse(
                    responseCode = "403",
                    description = "User account is suspended or blocked",
                    content = @Content(schema = @Schema(implementation = BaseResponse.class))
            )
    })
    public ResponseEntity<BaseResponse<CustomerOtpResponse>> sendOtp(
            @Valid @RequestBody CustomerSendOtpRequest request
    ) {
        CustomerOtpResponse response = customerAuthService.sendOtp(request);
        return ResponseEntity.ok(BaseResponse.ok("Verification code dispatched successfully", response));
    }

    /**
     * Verifies the OTP, resolves existing or creates new user record, and issues Keycloak tokens.
     *
     * @param request email and OTP payload
     * @return {@link BaseResponse} containing {@link AuthTokenResponse}
     */
    @PostMapping("/otp/verify")
    @Operation(
            summary = "Verify OTP and authenticate as END_USER",
            description = "Verifies the 6-digit OTP. Reuses existing central user record to preserve booking history, or auto-provisions a new account if the email is new. Issues official Keycloak tokens."
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "Successfully authenticated",
                    content = @Content(schema = @Schema(implementation = BaseResponse.class))
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "Invalid or expired OTP code",
                    content = @Content(schema = @Schema(implementation = BaseResponse.class))
            ),
            @ApiResponse(
                    responseCode = "403",
                    description = "User account is suspended or blocked",
                    content = @Content(schema = @Schema(implementation = BaseResponse.class))
            ),
            @ApiResponse(
                    responseCode = "429",
                    description = "Maximum OTP verification attempts exceeded",
                    content = @Content(schema = @Schema(implementation = BaseResponse.class))
            )
    })
    public ResponseEntity<BaseResponse<AuthTokenResponse>> verifyOtp(
            @Valid @RequestBody CustomerVerifyOtpRequest request
    ) {
        AuthTokenResponse response = customerAuthService.verifyOtp(request);
        return ResponseEntity.ok(BaseResponse.ok("Customer authenticated successfully", response));
    }
}
