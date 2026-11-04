package com.nextturn.controller.calendar;

import com.nextturn.constant.CalendarEndpointConstant;
import com.nextturn.constant.TenantHeaderConstant;
import com.nextturn.dto.BaseResponse;
import com.nextturn.dto.calendar.CalendarSettingsResponse;
import com.nextturn.dto.calendar.ConfirmSlotRequest;
import com.nextturn.dto.calendar.HoldSlotRequest;
import com.nextturn.dto.calendar.ReleaseSlotRequest;
import com.nextturn.dto.calendar.SlotHoldingResponse;
import com.nextturn.dto.calendar.TimeSlotResponse;
import com.nextturn.infrastructure.multitenancy.TenantContextHolder;
import com.nextturn.service.calendar.TenantCalendarSettingsService;
import com.nextturn.service.calendar.TenantTimeSlotHoldingService;
import com.nextturn.service.calendar.TenantTimeSlotQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/**
 * Public and Customer-facing REST controller for browsing available booking time slots,
 * temporarily holding a slot during booking checkout, and retrieving booking policies.
 */
@RestController
@RequestMapping(CalendarEndpointConstant.PUBLIC_CALENDAR_BASE)
@Tag(name = "Public Calendar & Booking", description = "Public / Customer endpoints for viewing available booking slots, temporary holding, and facility booking policies")
public class PublicCalendarController {

    private final TenantTimeSlotQueryService timeSlotQueryService;
    private final TenantTimeSlotHoldingService timeSlotHoldingService;
    private final TenantCalendarSettingsService calendarSettingsService;

    public PublicCalendarController(
            TenantTimeSlotQueryService timeSlotQueryService,
            TenantTimeSlotHoldingService timeSlotHoldingService,
            TenantCalendarSettingsService calendarSettingsService
    ) {
        this.timeSlotQueryService = timeSlotQueryService;
        this.timeSlotHoldingService = timeSlotHoldingService;
        this.calendarSettingsService = calendarSettingsService;
    }

    @GetMapping(CalendarEndpointConstant.SLOTS)
    @Operation(
            summary = "Tra cứu khung giờ trống công khai",
            description = "Retrieves available capacity booking time slots for customer online scheduling."
    )
    public ResponseEntity<BaseResponse<List<TimeSlotResponse>>> getPublicSlots(
            @Parameter(description = "Mã định danh cơ sở (header)")
            @RequestHeader(value = TenantHeaderConstant.X_TENANT_CODE_HEADER, required = false) String headerTenantCode,
            @Parameter(description = "Mã định danh cơ sở (query param)")
            @RequestParam(required = false) String tenantCode,
            @Parameter(description = "Service UUID", required = true)
            @RequestParam String serviceId,
            @Parameter(description = "Work date (YYYY-MM-DD)", required = true)
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate workDate
    ) {
        ensureTenantContext(headerTenantCode, tenantCode);
        List<TimeSlotResponse> slots = timeSlotQueryService.getAvailableSlots(serviceId, workDate);
        return ResponseEntity.ok(BaseResponse.ok("Lấy danh sách khung giờ trống khả dụng thành công", slots));
    }

    @PostMapping(CalendarEndpointConstant.SLOT_HOLD)
    @Operation(
            summary = "Khóa giữ chỗ tạm thời (Customer checkout)",
            description = "Temporarily holds an available time slot for customer booking checkout (default 10 minutes)."
    )
    public ResponseEntity<BaseResponse<SlotHoldingResponse>> holdSlot(
            @Parameter(description = "Mã định danh cơ sở (header)")
            @RequestHeader(value = TenantHeaderConstant.X_TENANT_CODE_HEADER, required = false) String headerTenantCode,
            @Parameter(description = "Mã định danh cơ sở (query param)")
            @RequestParam(required = false) String tenantCode,
            @Parameter(description = "Time slot UUID") @PathVariable String slotId,
            @Valid @RequestBody(required = false) HoldSlotRequest request
    ) {
        ensureTenantContext(headerTenantCode, tenantCode);
        int duration = (request != null && request.getHoldingMinutes() != null) ? request.getHoldingMinutes() : 10;
        SlotHoldingResponse response = timeSlotHoldingService.holdSlot(slotId, duration);
        return ResponseEntity.ok(BaseResponse.ok(response.getMessage(), response));
    }

    @PostMapping(CalendarEndpointConstant.SLOT_RELEASE)
    @Operation(
            summary = "Hủy giữ chỗ khung giờ (Customer release)",
            description = "Releases a held time slot back to AVAILABLE status when customer cancels checkout."
    )
    public ResponseEntity<BaseResponse<Void>> releaseSlot(
            @Parameter(description = "Mã định danh cơ sở (header)")
            @RequestHeader(value = TenantHeaderConstant.X_TENANT_CODE_HEADER, required = false) String headerTenantCode,
            @Parameter(description = "Mã định danh cơ sở (query param)")
            @RequestParam(required = false) String tenantCode,
            @Parameter(description = "Time slot UUID") @PathVariable String slotId,
            @Valid @RequestBody ReleaseSlotRequest request
    ) {
        ensureTenantContext(headerTenantCode, tenantCode);
        timeSlotHoldingService.releaseHeldSlot(slotId, request.getHoldingToken());
        return ResponseEntity.ok(BaseResponse.ok("Hủy giữ chỗ thành công", null));
    }

    @PostMapping(CalendarEndpointConstant.SLOT_CONFIRM)
    @Operation(
            summary = "Xác nhận đặt hẹn khung giờ",
            description = "Confirms booking on a held slot when customer completes checkout."
    )
    public ResponseEntity<BaseResponse<Void>> confirmSlot(
            @Parameter(description = "Mã định danh cơ sở (header)")
            @RequestHeader(value = TenantHeaderConstant.X_TENANT_CODE_HEADER, required = false) String headerTenantCode,
            @Parameter(description = "Mã định danh cơ sở (query param)")
            @RequestParam(required = false) String tenantCode,
            @Parameter(description = "Time slot UUID") @PathVariable String slotId,
            @Valid @RequestBody ConfirmSlotRequest request
    ) {
        ensureTenantContext(headerTenantCode, tenantCode);
        timeSlotHoldingService.confirmSlot(slotId, request.getHoldingToken());
        return ResponseEntity.ok(BaseResponse.ok("Xác nhận khung giờ đặt lịch thành công", null));
    }

    @GetMapping(CalendarEndpointConstant.SETTINGS)
    @Operation(
            summary = "Xem chính sách đặt hẹn của cơ sở",
            description = "Retrieves facility booking rules such as min lead time and max advance booking limit."
    )
    public ResponseEntity<BaseResponse<CalendarSettingsResponse>> getSettings(
            @Parameter(description = "Mã định danh cơ sở (header)")
            @RequestHeader(value = TenantHeaderConstant.X_TENANT_CODE_HEADER, required = false) String headerTenantCode,
            @Parameter(description = "Mã định danh cơ sở (query param)")
            @RequestParam(required = false) String tenantCode
    ) {
        ensureTenantContext(headerTenantCode, tenantCode);
        CalendarSettingsResponse response = calendarSettingsService.getSettings();
        return ResponseEntity.ok(BaseResponse.ok("Lấy chính sách đặt hẹn cơ sở thành công", response));
    }

    private void ensureTenantContext(String headerTenantCode, String paramTenantCode) {
        String effective = (headerTenantCode != null && !headerTenantCode.isBlank())
                ? headerTenantCode.trim()
                : (paramTenantCode != null && !paramTenantCode.isBlank() ? paramTenantCode.trim() : null);

        if (effective != null && !effective.isBlank()) {
            TenantContextHolder.setTenantCode(effective);
        }
    }
}

