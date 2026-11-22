package com.nextturn.controller.checkin;

import com.nextturn.constant.CheckInEndpointConstant;
import com.nextturn.constant.TenantHeaderConstant;
import com.nextturn.controller.BaseController;
import com.nextturn.dto.BaseResponse;
import com.nextturn.dto.checkin.CreateReceptionPointRequest;
import com.nextturn.dto.checkin.ReceptionPointResponse;
import com.nextturn.dto.checkin.ReceptionQrResponse;
import com.nextturn.dto.checkin.UpdateReceptionPointStatusRequest;
import com.nextturn.infrastructure.security.annotation.RequireTenantAdmin;
import com.nextturn.service.checkin.TenantReceptionPointService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Authenticated REST controller for managing facility reception points and generating
 * static or 60-second rotating dynamic QR codes (REQ-CHECK-IN-03).
 */
@RestController
@RequestMapping(CheckInEndpointConstant.ADMIN_RECEPTION_BASE)
@Tag(name = "Reception Point & QR Management", description = "Tenant Admin endpoints for configuring reception points and generating static/60s dynamic check-in QR codes (REQ-CHECK-IN-03)")
@SecurityRequirement(name = "bearerAuth")
public class TenantCheckInController extends BaseController {

    private final TenantReceptionPointService receptionPointService;

    public TenantCheckInController(TenantReceptionPointService receptionPointService) {
        this.receptionPointService = receptionPointService;
    }

    @PostMapping
    @RequireTenantAdmin
    @Operation(
            summary = "Tạo điểm tiếp đón và cấu hình mã QR (REQ-CHECK-IN-03)",
            description = "Creates a lobby or service reception point supporting DYNAMIC (60s rotating) or STATIC QR mode."
    )
    public ResponseEntity<BaseResponse<ReceptionPointResponse>> createReceptionPoint(
            @RequestHeader(value = TenantHeaderConstant.X_TENANT_CODE_HEADER, required = false) String headerTenantCode,
            @Valid @RequestBody CreateReceptionPointRequest request
    ) {
        ensureTenantContext(headerTenantCode);
        ReceptionPointResponse response = receptionPointService.createReceptionPoint(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(BaseResponse.created("Khởi tạo điểm tiếp đón thành công", response));
    }

    @GetMapping
    @Operation(
            summary = "Danh sách điểm tiếp đón của cơ sở",
            description = "Lists all active/inactive reception points configured for the current tenant."
    )
    public ResponseEntity<BaseResponse<List<ReceptionPointResponse>>> getAllReceptionPoints(
            @RequestHeader(value = TenantHeaderConstant.X_TENANT_CODE_HEADER, required = false) String headerTenantCode
    ) {
        ensureTenantContext(headerTenantCode);
        List<ReceptionPointResponse> points = receptionPointService.getAllReceptionPoints();
        return ResponseEntity.ok(BaseResponse.ok("Lấy danh sách điểm tiếp đón thành công", points));
    }

    @GetMapping(CheckInEndpointConstant.QR_CODE)
    @Operation(
            summary = "Lấy mã QR điểm tiếp đón hiện tại (tự động xoay mỗi 60s với chế độ DYNAMIC)",
            description = "Returns the current QR token, deep-link check-in URL, and 60-second countdown metadata."
    )
    public ResponseEntity<BaseResponse<ReceptionQrResponse>> getReceptionQr(
            @RequestHeader(value = TenantHeaderConstant.X_TENANT_CODE_HEADER, required = false) String headerTenantCode,
            @PathVariable String id
    ) {
        ensureTenantContext(headerTenantCode);
        ReceptionQrResponse qr = receptionPointService.getReceptionQr(id);
        return ResponseEntity.ok(BaseResponse.ok("Lấy mã QR điểm tiếp đón thành công", qr));
    }

    @PostMapping(CheckInEndpointConstant.ROTATE_STATIC_QR)
    @RequireTenantAdmin
    @Operation(
            summary = "Xoay/cấp lại mã bí mật & token tĩnh cho điểm tiếp đón",
            description = "Regenerates both the HMAC QR secret and persistent static token (invalidating old printed posters)."
    )
    public ResponseEntity<BaseResponse<ReceptionQrResponse>> rotateStaticQr(
            @RequestHeader(value = TenantHeaderConstant.X_TENANT_CODE_HEADER, required = false) String headerTenantCode,
            @PathVariable String id
    ) {
        ensureTenantContext(headerTenantCode);
        ReceptionQrResponse qr = receptionPointService.rotateStaticQr(id);
        return ResponseEntity.ok(BaseResponse.ok("Cấp lại mã QR điểm tiếp đón thành công", qr));
    }

    @PatchMapping(CheckInEndpointConstant.UPDATE_STATUS)
    @RequireTenantAdmin
    @Operation(
            summary = "Cập nhật trạng thái hoạt động điểm tiếp đón",
            description = "Toggles a reception point between ACTIVE and INACTIVE."
    )
    public ResponseEntity<BaseResponse<ReceptionPointResponse>> updateStatus(
            @RequestHeader(value = TenantHeaderConstant.X_TENANT_CODE_HEADER, required = false) String headerTenantCode,
            @PathVariable String id,
            @Valid @RequestBody UpdateReceptionPointStatusRequest request
    ) {
        ensureTenantContext(headerTenantCode);
        ReceptionPointResponse updated = receptionPointService.updateStatus(id, request);
        return ResponseEntity.ok(BaseResponse.ok("Cập nhật trạng thái điểm tiếp đón thành công", updated));
    }
}
