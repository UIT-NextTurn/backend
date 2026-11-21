package com.nextturn.controller.appointment;

import com.nextturn.constant.AppointmentEndpointConstant;
import com.nextturn.constant.TenantHeaderConstant;
import com.nextturn.controller.BaseController;
import com.nextturn.dto.BaseResponse;
import com.nextturn.dto.appointment.AppointmentResponse;
import com.nextturn.dto.appointment.CancelAppointmentRequest;
import com.nextturn.dto.appointment.CreateAppointmentRequest;
import com.nextturn.dto.appointment.RescheduleAppointmentRequest;
import com.nextturn.dto.calendar.HoldSlotRequest;
import com.nextturn.dto.calendar.SlotHoldingResponse;
import com.nextturn.dto.calendar.TimeSlotResponse;
import com.nextturn.infrastructure.security.annotation.CurrentUser;
import com.nextturn.infrastructure.security.principal.AuthenticatedUser;
import com.nextturn.service.appointment.TenantAppointmentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
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

import java.time.LocalDate;
import java.util.List;

/**
 * Customer-facing and Public REST controller for online appointment scheduling:
 * policy-filtered slot lookup (REQ-APT-01), temporary slot holding (REQ-APT-02),
 * appointment confirmation (REQ-APT-03), lookup/reschedule/cancel (REQ-APT-04),
 * and customer appointment history (REQ-APT-05).
 */
@RestController
@RequestMapping(AppointmentEndpointConstant.PUBLIC_APPOINTMENTS_BASE)
@Tag(name = "Public & Customer Appointments", description = "Customer endpoints for slot lookup, temporary holding, booking creation, rescheduling, and cancellation (REQ-APT-01 to REQ-APT-05)")
public class PublicAppointmentController extends BaseController {

    private final TenantAppointmentService appointmentService;

    public PublicAppointmentController(TenantAppointmentService appointmentService) {
        this.appointmentService = appointmentService;
    }

    @GetMapping("/slots")
    @Operation(
            summary = "Tra cứu khung giờ trống theo dịch vụ và chính sách cơ sở (REQ-APT-01)",
            description = "Returns available time slots for a service and date filtered by facility min_lead_time_hours and max_advance_booking_days."
    )
    public ResponseEntity<BaseResponse<List<TimeSlotResponse>>> getAvailableSlots(
            @RequestHeader(value = TenantHeaderConstant.X_TENANT_CODE_HEADER, required = false) String headerTenantCode,
            @RequestParam(required = false) String tenantCode,
            @Parameter(description = "Service UUID", required = true) @RequestParam String serviceId,
            @Parameter(description = "Appointment date (YYYY-MM-DD)", required = true)
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate appointmentDate
    ) {
        ensureTenantContext(headerTenantCode, tenantCode);
        List<TimeSlotResponse> slots = appointmentService.getAvailableSlotsForBooking(serviceId, appointmentDate);
        return ResponseEntity.ok(BaseResponse.ok("Lấy danh sách khung giờ trống khả dụng thành công", slots));
    }

    @PostMapping("/slots/{slotId}/hold")
    @Operation(
            summary = "Khóa giữ chỗ khung giờ tạm thời 10 phút (REQ-APT-02)",
            description = "Locks an available time slot in HOLDING status for 10 minutes using Valkey distributed lock and returns a holding_session_token."
    )
    public ResponseEntity<BaseResponse<SlotHoldingResponse>> holdSlot(
            @RequestHeader(value = TenantHeaderConstant.X_TENANT_CODE_HEADER, required = false) String headerTenantCode,
            @RequestParam(required = false) String tenantCode,
            @PathVariable String slotId,
            @Valid @RequestBody(required = false) HoldSlotRequest request
    ) {
        ensureTenantContext(headerTenantCode, tenantCode);
        Integer holdingMinutes = request != null ? request.getHoldingMinutes() : 10;
        SlotHoldingResponse response = appointmentService.holdSlotForBooking(slotId, holdingMinutes);
        return ResponseEntity.ok(BaseResponse.ok(response.getMessage(), response));
    }

    @PostMapping
    @Operation(
            summary = "Xác nhận thông tin và khởi tạo cuộc hẹn (REQ-APT-03)",
            description = "Validates holding_session_token and min_lead_time_hours, creates a CONFIRMED appointment (APT-YYYYMMDD-XXXX) with QR check-in token, and dispatches confirmation email."
    )
    public ResponseEntity<BaseResponse<AppointmentResponse>> createAppointment(
            @RequestHeader(value = TenantHeaderConstant.X_TENANT_CODE_HEADER, required = false) String headerTenantCode,
            @RequestParam(required = false) String tenantCode,
            @Valid @RequestBody CreateAppointmentRequest request,
            @CurrentUser AuthenticatedUser currentUser
    ) {
        ensureTenantContext(headerTenantCode, tenantCode);
        String userId = extractUserId(currentUser);
        AppointmentResponse response = appointmentService.createAppointment(request, userId);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(BaseResponse.created("Đặt lịch hẹn thành công", response));
    }

    @GetMapping(AppointmentEndpointConstant.APPOINTMENT_LOOKUP)
    @Operation(
            summary = "Tra cứu thông tin lịch hẹn qua mã cuộc hẹn và số điện thoại (REQ-APT-04)",
            description = "Looks up an appointment by exact match of appointmentCode and customerPhone."
    )
    public ResponseEntity<BaseResponse<AppointmentResponse>> lookupAppointment(
            @RequestHeader(value = TenantHeaderConstant.X_TENANT_CODE_HEADER, required = false) String headerTenantCode,
            @RequestParam(required = false) String tenantCode,
            @Parameter(description = "Appointment code (e.g. APT-20261122-0001)", required = true)
            @RequestParam String appointmentCode,
            @Parameter(description = "Customer 10-digit phone number", required = true)
            @RequestParam String customerPhone
    ) {
        ensureTenantContext(headerTenantCode, tenantCode);
        AppointmentResponse response = appointmentService.lookupAppointment(appointmentCode, customerPhone);
        return ResponseEntity.ok(BaseResponse.ok("Tra cứu thông tin lịch hẹn thành công", response));
    }

    @PostMapping(AppointmentEndpointConstant.APPOINTMENT_CANCEL)
    @Operation(
            summary = "Khách hàng hủy lịch hẹn trực tuyến (REQ-APT-04)",
            description = "Cancels a CONFIRMED appointment if within cancellation_deadline_hours, restores slot capacity, and sends cancellation email."
    )
    public ResponseEntity<BaseResponse<AppointmentResponse>> cancelAppointment(
            @RequestHeader(value = TenantHeaderConstant.X_TENANT_CODE_HEADER, required = false) String headerTenantCode,
            @RequestParam(required = false) String tenantCode,
            @PathVariable String appointmentId,
            @Valid @RequestBody CancelAppointmentRequest request,
            @CurrentUser AuthenticatedUser currentUser
    ) {
        ensureTenantContext(headerTenantCode, tenantCode);
        String userId = extractUserId(currentUser);
        AppointmentResponse response = appointmentService.cancelAppointment(appointmentId, request, userId, false);
        return ResponseEntity.ok(BaseResponse.ok("Hủy lịch hẹn thành công", response));
    }

    @PutMapping(AppointmentEndpointConstant.APPOINTMENT_RESCHEDULE)
    @Operation(
            summary = "Khách hàng đổi khung giờ lịch hẹn trực tuyến (REQ-APT-04)",
            description = "Atomically transfers a CONFIRMED appointment to a new available slot while releasing the old slot if within cancellation_deadline_hours."
    )
    public ResponseEntity<BaseResponse<AppointmentResponse>> rescheduleAppointment(
            @RequestHeader(value = TenantHeaderConstant.X_TENANT_CODE_HEADER, required = false) String headerTenantCode,
            @RequestParam(required = false) String tenantCode,
            @PathVariable String appointmentId,
            @Valid @RequestBody RescheduleAppointmentRequest request,
            @CurrentUser AuthenticatedUser currentUser
    ) {
        ensureTenantContext(headerTenantCode, tenantCode);
        String userId = extractUserId(currentUser);
        AppointmentResponse response = appointmentService.rescheduleAppointment(appointmentId, request, userId, false);
        return ResponseEntity.ok(BaseResponse.ok("Đổi khung giờ lịch hẹn thành công", response));
    }
}
