package com.nextturn.controller.calendar;

import com.nextturn.constant.CalendarEndpointConstant;
import com.nextturn.dto.BaseResponse;
import com.nextturn.dto.calendar.CalendarSettingsRequest;
import com.nextturn.dto.calendar.CalendarSettingsResponse;
import com.nextturn.dto.calendar.ConfirmSlotRequest;
import com.nextturn.dto.calendar.CreateShiftRequest;
import com.nextturn.dto.calendar.HoldSlotRequest;
import com.nextturn.dto.calendar.ReleaseSlotRequest;
import com.nextturn.dto.calendar.ShiftResponse;
import com.nextturn.dto.calendar.SlotHoldingResponse;
import com.nextturn.dto.calendar.TimeSlotResponse;
import com.nextturn.dto.calendar.UpdateShiftRequest;
import com.nextturn.enums.ShiftStatus;
import com.nextturn.infrastructure.security.annotation.RequireTenantAdmin;
import com.nextturn.service.calendar.TenantCalendarService;
import com.nextturn.service.calendar.TenantCalendarSettingsService;
import com.nextturn.service.calendar.TenantTimeSlotHoldingService;
import com.nextturn.service.calendar.TenantTimeSlotQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/**
 * REST controller for Tenant Administrators to manage operational work schedules on the calendar,
 * allocate shifts, adjust shift operational windows, query capacity booking time slots,
 * and configure booking policies.
 */
@RestController
@RequestMapping(CalendarEndpointConstant.CALENDAR_BASE)
@RequireTenantAdmin
@Tag(name = "Tenant Calendar Management", description = "Endpoints for managing calendar work shifts, capacity booking time slots, and facility booking policies")
@SecurityRequirement(name = "bearerAuth")
public class TenantCalendarController {

    private final TenantCalendarService calendarService;
    private final TenantTimeSlotQueryService timeSlotQueryService;
    private final TenantTimeSlotHoldingService timeSlotHoldingService;
    private final TenantCalendarSettingsService calendarSettingsService;

    public TenantCalendarController(
            TenantCalendarService calendarService,
            TenantTimeSlotQueryService timeSlotQueryService,
            TenantTimeSlotHoldingService timeSlotHoldingService,
            TenantCalendarSettingsService calendarSettingsService
    ) {
        this.calendarService = calendarService;
        this.timeSlotQueryService = timeSlotQueryService;
        this.timeSlotHoldingService = timeSlotHoldingService;
        this.calendarSettingsService = calendarSettingsService;
    }

    @PostMapping(CalendarEndpointConstant.SHIFTS)
    @Operation(
            summary = "Khởi tạo và phân bổ ca làm việc",
            description = "Allocates a new operational work shift on the calendar, checks for scheduling overlaps, and automatically generates sequential capacity time slots."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Work shift(s) successfully created and time slots generated",
                    content = @Content(schema = @Schema(implementation = BaseResponse.class))),
            @ApiResponse(responseCode = "400", description = "Invalid request payload, duration too short, or date in the past",
                    content = @Content(schema = @Schema(implementation = BaseResponse.class))),
            @ApiResponse(responseCode = "404", description = "Staff or service not found",
                    content = @Content(schema = @Schema(implementation = BaseResponse.class))),
            @ApiResponse(responseCode = "409", description = "Overlapping shift detected for staff member",
                    content = @Content(schema = @Schema(implementation = BaseResponse.class)))
    })
    public ResponseEntity<BaseResponse<List<ShiftResponse>>> createShifts(
            @Valid @RequestBody CreateShiftRequest request
    ) {
        List<ShiftResponse> response = calendarService.createShifts(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(BaseResponse.created(
                "Ca làm việc đã được khởi tạo và phân rã khung giờ trống thành công",
                response
        ));
    }

    @GetMapping(CalendarEndpointConstant.SHIFT_BY_ID)
    @Operation(summary = "Xem chi tiết ca làm việc", description = "Retrieves details of a specific staff work shift by ID.")
    public ResponseEntity<BaseResponse<ShiftResponse>> getShiftById(
            @Parameter(description = "Shift unique UUID") @PathVariable String shiftId
    ) {
        ShiftResponse response = calendarService.getShiftById(shiftId);
        return ResponseEntity.ok(BaseResponse.ok("Lấy thông tin ca làm việc thành công", response));
    }

    @GetMapping(CalendarEndpointConstant.SHIFTS)
    @Operation(summary = "Tìm kiếm danh sách ca làm việc", description = "Queries calendar shifts by date range, staff ID, service ID, or status.")
    public ResponseEntity<BaseResponse<List<ShiftResponse>>> searchShifts(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate workDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(required = false) String staffId,
            @RequestParam(required = false) String serviceId,
            @RequestParam(required = false) ShiftStatus status
    ) {
        List<ShiftResponse> response = calendarService.searchShifts(
                workDate, startDate, endDate, staffId, serviceId, status);
        return ResponseEntity.ok(BaseResponse.ok("Lấy danh sách ca làm việc thành công", response));
    }

    @PutMapping(CalendarEndpointConstant.SHIFT_BY_ID)
    @Operation(
            summary = "Điều chỉnh ca làm việc",
            description = "Adjusts operational window of a scheduled work shift. Prevents shortening if booked appointments exist in the cut window."
    )
    public ResponseEntity<BaseResponse<ShiftResponse>> updateShift(
            @Parameter(description = "Shift unique UUID") @PathVariable String shiftId,
            @Valid @RequestBody UpdateShiftRequest request
    ) {
        ShiftResponse response = calendarService.updateShift(shiftId, request);
        return ResponseEntity.ok(BaseResponse.ok("Điều chỉnh ca làm việc thành công", response));
    }

    @DeleteMapping(CalendarEndpointConstant.SHIFT_BY_ID)
    @Operation(
            summary = "Hủy ca làm việc",
            description = "Cancels a scheduled shift and recalculates capacity. Blocked if confirmed appointments exist in the shift window."
    )
    public ResponseEntity<BaseResponse<ShiftResponse>> cancelShift(
            @Parameter(description = "Shift unique UUID") @PathVariable String shiftId
    ) {
        ShiftResponse response = calendarService.cancelShift(shiftId);
        return ResponseEntity.ok(BaseResponse.ok("Hủy ca làm việc thành công", response));
    }

    @PutMapping(CalendarEndpointConstant.SHIFT_CANCEL)
    @Operation(
            summary = "Hủy ca làm việc (PUT)",
            description = "Alternative PUT endpoint to cancel a scheduled shift and recalculate capacity."
    )
    public ResponseEntity<BaseResponse<ShiftResponse>> cancelShiftPut(
            @Parameter(description = "Shift unique UUID") @PathVariable String shiftId
    ) {
        ShiftResponse response = calendarService.cancelShift(shiftId);
        return ResponseEntity.ok(BaseResponse.ok("Hủy ca làm việc thành công", response));
    }

    @GetMapping(CalendarEndpointConstant.SLOTS)
    @Operation(
            summary = "Truy vấn danh sách khung giờ trống",
            description = "Retrieves available capacity booking time slots for a service and date, checking Valkey cache first."
    )
    public ResponseEntity<BaseResponse<List<TimeSlotResponse>>> getTimeSlots(
            @Parameter(description = "Service UUID", required = true) @RequestParam String serviceId,
            @Parameter(description = "Work date (YYYY-MM-DD)", required = true)
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate workDate,
            @Parameter(description = "Whether to return only available slots (default: true)")
            @RequestParam(defaultValue = "true") boolean availableOnly
    ) {
        List<TimeSlotResponse> slots = availableOnly
                ? timeSlotQueryService.getAvailableSlots(serviceId, workDate)
                : timeSlotQueryService.getAllSlotsForDate(serviceId, workDate);

        return ResponseEntity.ok(BaseResponse.ok("Lấy danh sách khung giờ trống thành công", slots));
    }

    @PostMapping(CalendarEndpointConstant.SLOT_HOLD)
    @Operation(
            summary = "Khóa slot tạm thời (Hold slot)",
            description = "Temporarily holds an available time slot for booking checkout using distributed and pessimistic locking."
    )
    public ResponseEntity<BaseResponse<SlotHoldingResponse>> holdSlot(
            @Parameter(description = "Time slot UUID") @PathVariable String slotId,
            @Valid @RequestBody(required = false) HoldSlotRequest request
    ) {
        int duration = (request != null && request.getHoldingMinutes() != null) ? request.getHoldingMinutes() : 10;
        SlotHoldingResponse response = timeSlotHoldingService.holdSlot(slotId, duration);
        return ResponseEntity.ok(BaseResponse.ok(response.getMessage(), response));
    }

    @PostMapping(CalendarEndpointConstant.SLOT_RELEASE)
    @Operation(
            summary = "Giải phóng slot đã giữ chỗ",
            description = "Releases a held time slot back to AVAILABLE status when the matching holding token is provided."
    )
    public ResponseEntity<BaseResponse<Void>> releaseSlot(
            @Parameter(description = "Time slot UUID") @PathVariable String slotId,
            @Valid @RequestBody ReleaseSlotRequest request
    ) {
        timeSlotHoldingService.releaseHeldSlot(slotId, request.getHoldingToken());
        return ResponseEntity.ok(BaseResponse.ok("Giải phóng khung giờ thành công", null));
    }

    @PostMapping(CalendarEndpointConstant.SLOT_CONFIRM)
    @Operation(
            summary = "Xác nhận đặt hẹn trên slot đã giữ chỗ",
            description = "Confirms booking on a held slot, advancing status to BOOKED and incrementing booked count."
    )
    public ResponseEntity<BaseResponse<Void>> confirmSlot(
            @Parameter(description = "Time slot UUID") @PathVariable String slotId,
            @Valid @RequestBody ConfirmSlotRequest request
    ) {
        timeSlotHoldingService.confirmSlot(slotId, request.getHoldingToken());
        return ResponseEntity.ok(BaseResponse.ok("Xác nhận khung giờ đặt lịch thành công", null));
    }

    @GetMapping(CalendarEndpointConstant.SETTINGS)
    @Operation(
            summary = "Lấy cấu hình chính sách đặt hẹn",
            description = "Retrieves facility-level booking policy parameters including min lead time, max advance booking days, and cancellation deadline."
    )
    public ResponseEntity<BaseResponse<CalendarSettingsResponse>> getSettings() {
        CalendarSettingsResponse response = calendarSettingsService.getSettings();
        return ResponseEntity.ok(BaseResponse.ok("Lấy cấu hình chính sách đặt hẹn thành công", response));
    }

    @PutMapping(CalendarEndpointConstant.SETTINGS)
    @Operation(
            summary = "Cập nhật cấu hình chính sách đặt hẹn",
            description = "Updates facility booking parameters and synchronizes cache with Valkey."
    )
    public ResponseEntity<BaseResponse<CalendarSettingsResponse>> updateSettings(
            @Valid @RequestBody CalendarSettingsRequest request
    ) {
        CalendarSettingsResponse response = calendarSettingsService.updateSettings(request);
        return ResponseEntity.ok(BaseResponse.ok("Cập nhật chính sách đặt hẹn thành công", response));
    }
}
