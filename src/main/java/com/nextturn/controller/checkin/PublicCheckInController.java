package com.nextturn.controller.checkin;

import com.nextturn.constant.CheckInEndpointConstant;
import com.nextturn.constant.TenantHeaderConstant;
import com.nextturn.controller.BaseController;
import com.nextturn.dto.BaseResponse;
import com.nextturn.dto.checkin.AppointmentCheckInRequest;
import com.nextturn.dto.checkin.ResolveReceptionQrResponse;
import com.nextturn.dto.checkin.WalkInCheckInRequest;
import com.nextturn.dto.ticket.TicketResponse;
import com.nextturn.service.checkin.TenantCheckInService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public customer-facing REST controller for unified lobby check-in flows:
 * resolving lobby reception QR codes (REQ-CHECK-IN-03), pre-booked appointment QR check-in (REQ-CHECK-IN-01),
 * and walk-in ticket registration (REQ-CHECK-IN-02).
 */
@RestController
@RequestMapping(CheckInEndpointConstant.PUBLIC_CHECKIN_BASE)
@Tag(name = "Public Lobby Check-in", description = "Customer endpoints for scanning reception point QR codes, checking in pre-booked appointments, and registering walk-in turns (REQ-CHECK-IN-01 to REQ-CHECK-IN-03)")
public class PublicCheckInController extends BaseController {

    private final TenantCheckInService checkInService;

    public PublicCheckInController(TenantCheckInService checkInService) {
        this.checkInService = checkInService;
    }

    @GetMapping(CheckInEndpointConstant.RESOLVE_QR)
    @Operation(
            summary = "Giải mã và xác thực mã QR điểm tiếp đón (REQ-CHECK-IN-03)",
            description = "Validates a static or 60-second dynamic reception QR token and returns facility/service intake options."
    )
    public ResponseEntity<BaseResponse<ResolveReceptionQrResponse>> resolveReceptionQr(
            @RequestHeader(value = TenantHeaderConstant.X_TENANT_CODE_HEADER, required = false) String headerTenantCode,
            @RequestParam(required = false) String tenantCode,
            @Parameter(description = "Reception QR token scanned from lobby screen or standee", required = true)
            @RequestParam String token
    ) {
        ensureTenantContext(headerTenantCode, tenantCode);
        ResolveReceptionQrResponse resolved = checkInService.resolveReceptionQr(token);
        return ResponseEntity.ok(BaseResponse.ok("Xác thực mã QR điểm tiếp đón thành công", resolved));
    }

    @PostMapping(CheckInEndpointConstant.APPOINTMENT_CHECKIN)
    @Operation(
            summary = "Check-in cuộc hẹn đặt trước qua QR/Mã hẹn (REQ-CHECK-IN-01)",
            description = "Validates confirmed appointment within [-15m, +10m], transitions to CHECKED_IN, and issues an APPOINTMENT queue ticket."
    )
    public ResponseEntity<BaseResponse<TicketResponse>> checkInAppointment(
            @RequestHeader(value = TenantHeaderConstant.X_TENANT_CODE_HEADER, required = false) String headerTenantCode,
            @RequestParam(required = false) String tenantCode,
            @Valid @RequestBody AppointmentCheckInRequest request
    ) {
        ensureTenantContext(headerTenantCode, tenantCode);
        TicketResponse response = checkInService.checkInAppointment(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(BaseResponse.created("Check-in lịch hẹn thành công", response));
    }

    @PostMapping(CheckInEndpointConstant.WALKIN_CHECKIN)
    @Operation(
            summary = "Đăng ký lượt và cấp vé cho khách vãng lai tại sảnh (REQ-CHECK-IN-02)",
            description = "Validates 10-digit phone, active shift window/capacity, and issues a WALK_IN queue ticket."
    )
    public ResponseEntity<BaseResponse<TicketResponse>> checkInWalkIn(
            @RequestHeader(value = TenantHeaderConstant.X_TENANT_CODE_HEADER, required = false) String headerTenantCode,
            @RequestParam(required = false) String tenantCode,
            @Valid @RequestBody WalkInCheckInRequest request
    ) {
        ensureTenantContext(headerTenantCode, tenantCode);
        TicketResponse response = checkInService.checkInWalkIn(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(BaseResponse.created("Đăng ký lượt vãng lai thành công", response));
    }
}
