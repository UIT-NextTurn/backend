package com.nextturn.controller.appointment;

import com.nextturn.constant.AppointmentEndpointConstant;
import com.nextturn.constant.TenantHeaderConstant;
import com.nextturn.controller.BaseController;
import com.nextturn.dto.BaseResponse;
import com.nextturn.dto.appointment.AppointmentResponse;
import com.nextturn.dto.appointment.CancelAppointmentRequest;
import com.nextturn.dto.appointment.RescheduleAppointmentRequest;
import com.nextturn.infrastructure.security.annotation.CurrentUser;
import com.nextturn.infrastructure.security.principal.AuthenticatedUser;
import com.nextturn.service.appointment.TenantAppointmentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Authenticated REST controller for Customer appointment history (REQ-APT-05)
 * and Tenant Admin / Counter Staff appointment management (REQ-APT-04).
 */
@RestController
@RequestMapping({AppointmentEndpointConstant.APPOINTMENTS_BASE, AppointmentEndpointConstant.CUSTOMER_APPOINTMENTS_BASE})
@Tag(name = "Tenant & Customer Appointments", description = "Authenticated endpoints for customer appointment history (REQ-APT-05) and facility appointment management")
@SecurityRequirement(name = "bearerAuth")
public class TenantAppointmentController extends BaseController {

    private final TenantAppointmentService appointmentService;

    public TenantAppointmentController(TenantAppointmentService appointmentService) {
        this.appointmentService = appointmentService;
    }

    @GetMapping(AppointmentEndpointConstant.MY_APPOINTMENTS)
    @Operation(
            summary = "Tra cứu lịch sử cuộc hẹn của tôi theo cơ sở đăng nhập (REQ-APT-05)",
            description = "Retrieves past, current, and future appointments of the logged-in customer at the current facility via central__global_user_appointments."
    )
    public ResponseEntity<BaseResponse<List<AppointmentResponse>>> getMyAppointments(
            @RequestHeader(value = TenantHeaderConstant.X_TENANT_CODE_HEADER, required = false) String headerTenantCode,
            @RequestParam(required = false) String tenantCode,
            @CurrentUser AuthenticatedUser currentUser
    ) {
        ensureTenantContext(headerTenantCode, tenantCode);
        String userId = extractUserId(currentUser);
        List<AppointmentResponse> appointments = appointmentService.getCustomerAppointmentsForCurrentTenant(userId);
        return ResponseEntity.ok(BaseResponse.ok("Lấy danh sách lịch sử cuộc hẹn thành công", appointments));
    }

    @GetMapping
    @Operation(
            summary = "Danh sách toàn bộ lịch hẹn của cơ sở (Admin / Nhân viên quầy)",
            description = "Returns all appointments for the current facility."
    )
    public ResponseEntity<BaseResponse<List<AppointmentResponse>>> getAllAppointments(
            @RequestHeader(value = TenantHeaderConstant.X_TENANT_CODE_HEADER, required = false) String headerTenantCode
    ) {
        ensureTenantContext(headerTenantCode, null);
        List<AppointmentResponse> appointments = appointmentService.getAllTenantAppointments();
        return ResponseEntity.ok(BaseResponse.ok("Lấy danh sách cuộc hẹn của cơ sở thành công", appointments));
    }

    @GetMapping(AppointmentEndpointConstant.APPOINTMENT_BY_ID)
    @Operation(
            summary = "Chi tiết cuộc hẹn theo ID hoặc mã cuộc hẹn",
            description = "Returns detailed appointment information."
    )
    public ResponseEntity<BaseResponse<AppointmentResponse>> getAppointmentById(
            @RequestHeader(value = TenantHeaderConstant.X_TENANT_CODE_HEADER, required = false) String headerTenantCode,
            @PathVariable String appointmentId
    ) {
        ensureTenantContext(headerTenantCode, null);
        AppointmentResponse response = appointmentService.getAppointmentById(appointmentId);
        return ResponseEntity.ok(BaseResponse.ok("Lấy thông tin chi tiết cuộc hẹn thành công", response));
    }

    @PostMapping(AppointmentEndpointConstant.APPOINTMENT_CANCEL)
    @Operation(
            summary = "Hủy cuộc hẹn (Khách hàng đã đăng nhập hoặc Admin cơ sở) (REQ-APT-04 & REQ-APT-05)",
            description = "Cancels a confirmed appointment. Enforces cancellation_deadline_hours for customers; admins/staff can override."
    )
    public ResponseEntity<BaseResponse<AppointmentResponse>> cancelAppointment(
            @RequestHeader(value = TenantHeaderConstant.X_TENANT_CODE_HEADER, required = false) String headerTenantCode,
            @PathVariable String appointmentId,
            @Valid @RequestBody CancelAppointmentRequest request,
            @CurrentUser AuthenticatedUser currentUser
    ) {
        ensureTenantContext(headerTenantCode);
        String userId = extractUserId(currentUser);
        boolean isStaff = isStaffUser(currentUser);
        AppointmentResponse response = appointmentService.cancelAppointment(appointmentId, request, userId, isStaff);
        return ResponseEntity.ok(BaseResponse.ok("Hủy lịch hẹn thành công", response));
    }

    @PutMapping(AppointmentEndpointConstant.APPOINTMENT_RESCHEDULE)
    @Operation(
            summary = "Đổi khung giờ cuộc hẹn (Khách hàng đã đăng nhập hoặc Admin cơ sở) (REQ-APT-04 & REQ-APT-05)",
            description = "Atomically reschedules a confirmed appointment to a new available slot."
    )
    public ResponseEntity<BaseResponse<AppointmentResponse>> rescheduleAppointment(
            @RequestHeader(value = TenantHeaderConstant.X_TENANT_CODE_HEADER, required = false) String headerTenantCode,
            @PathVariable String appointmentId,
            @Valid @RequestBody RescheduleAppointmentRequest request,
            @CurrentUser AuthenticatedUser currentUser
    ) {
        ensureTenantContext(headerTenantCode);
        String userId = extractUserId(currentUser);
        boolean isStaff = isStaffUser(currentUser);
        AppointmentResponse response = appointmentService.rescheduleAppointment(appointmentId, request, userId, isStaff);
        return ResponseEntity.ok(BaseResponse.ok("Đổi khung giờ lịch hẹn thành công", response));
    }
}
