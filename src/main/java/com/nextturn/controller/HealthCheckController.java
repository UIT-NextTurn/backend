package com.nextturn.controller;

import com.nextturn.dto.BaseResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Controller providing system health check and readiness telemetry probe.
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "System Telemetry", description = "Endpoints for platform heartbeat, readiness probes, and operational status")
@SecurityRequirements
public class HealthCheckController {

    @GetMapping("/health")
    @Operation(summary = "System health check probe", description = "Returns the operational heartbeat, status, and running version of the backend service.")
    @SecurityRequirements
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Service is active and healthy")
    })
    public ResponseEntity<BaseResponse<Map<String, Object>>> healthCheck() {
        Map<String, Object> healthInfo = Map.of(
                "status", "UP",
                "system", "NextTurn Virtual Queue & Booking Platform",
                "version", "1.0.0-SNAPSHOT"
        );
        return ResponseEntity.ok(BaseResponse.ok("Service is healthy and running", healthInfo));
    }
}
