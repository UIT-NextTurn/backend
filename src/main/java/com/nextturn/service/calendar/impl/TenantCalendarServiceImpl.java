package com.nextturn.service.calendar.impl;

import com.nextturn.dto.calendar.CreateShiftRequest;
import com.nextturn.dto.calendar.ShiftResponse;
import com.nextturn.dto.calendar.UpdateShiftRequest;
import com.nextturn.entity.TenantService;
import com.nextturn.entity.TenantShift;
import com.nextturn.entity.TenantStaff;
import com.nextturn.enums.ShiftStatus;
import com.nextturn.exception.calendar.CalendarErrorCode;
import com.nextturn.exception.calendar.CalendarException;
import com.nextturn.repository.calendar.TenantShiftRepository;
import com.nextturn.repository.calendar.TenantTimeSlotRepository;
import com.nextturn.service.BaseService;
import com.nextturn.service.calendar.TenantCalendarService;
import com.nextturn.service.calendar.TenantCalendarValidator;
import com.nextturn.service.calendar.TenantTimeSlotGeneratorService;
import com.nextturn.infrastructure.logging.LogContract;
import com.nextturn.infrastructure.logging.LogDomain;
import com.nextturn.infrastructure.logging.LogOutcome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Default implementation of {@link TenantCalendarService} orchestrating staff shift scheduling,
 * validation, persistence, and automated capacity slot generation.
 */
@Service
public class TenantCalendarServiceImpl extends BaseService implements TenantCalendarService {

    private static final Logger log = LoggerFactory.getLogger(TenantCalendarServiceImpl.class);

    private final TenantShiftRepository shiftRepository;
    private final TenantTimeSlotRepository timeSlotRepository;
    private final TenantCalendarValidator calendarValidator;
    private final TenantTimeSlotGeneratorService slotGeneratorService;

    public TenantCalendarServiceImpl(
            TenantShiftRepository shiftRepository,
            TenantTimeSlotRepository timeSlotRepository,
            TenantCalendarValidator calendarValidator,
            TenantTimeSlotGeneratorService slotGeneratorService,
            TransactionTemplate transactionTemplate
    ) {
        super(transactionTemplate);
        this.shiftRepository = shiftRepository;
        this.timeSlotRepository = timeSlotRepository;
        this.calendarValidator = calendarValidator;
        this.slotGeneratorService = slotGeneratorService;
    }

    @Override
    public List<ShiftResponse> createShifts(CreateShiftRequest request) {
        // Step 1: Validate staff eligibility
        TenantStaff staff = calendarValidator.validateStaff(request.getStaffId());

        // Step 2: Validate and resolve service
        TenantService service = calendarValidator.validateAndResolveService(staff, request.getServiceId());

        // Step 3: Validate time window and minimum duration
        calendarValidator.validateTimeWindow(request.getShiftStart(), request.getShiftEnd());
        calendarValidator.validateShiftDuration(request.getShiftStart(), request.getShiftEnd(), service);

        // Step 4: Resolve scheduled dates (single date vs recurring range)
        List<LocalDate> scheduledDates = resolveScheduledDates(request);

        // Step 5: Validate work date constraints and overlap checks across all resolved dates
        for (LocalDate date : scheduledDates) {
            calendarValidator.validateWorkDate(date);
            calendarValidator.validateNoOverlap(staff.getId(), date, request.getShiftStart(), request.getShiftEnd());
        }

        // Step 6: Persist shifts and generate capacity time slots within a transaction
        return executeInTransaction(status -> {
            List<TenantShift> createdShifts = new ArrayList<>();
            for (LocalDate date : scheduledDates) {
                TenantShift shift = TenantShift.builder()
                        .id(UUID.randomUUID().toString())
                        .staff(staff)
                        .service(service)
                        .workDate(date)
                        .shiftStart(request.getShiftStart())
                        .shiftEnd(request.getShiftEnd())
                        .status(ShiftStatus.SCHEDULED)
                        .build();

                TenantShift savedShift = shiftRepository.save(shift);
                createdShifts.add(savedShift);
            }
            shiftRepository.flush();

            // Automatic generation of time slots for each created shift
            for (TenantShift shift : createdShifts) {
                slotGeneratorService.generateTimeSlotsForShift(shift);
            }

            LogContract.info(log, LogDomain.CALENDAR, "shifts_created")
                    .outcome(LogOutcome.SUCCESS)
                    .tenant(resolveCurrentTenantCode())
                    .entity("staff", staff.getStaffCode())
                    .field("count", createdShifts.size())
                    .emit();

            return createdShifts.stream()
                    .map(ShiftResponse::fromEntity)
                    .collect(Collectors.toList());
        });
    }

    @Override
    @Transactional(readOnly = true)
    public ShiftResponse getShiftById(String shiftId) {
        TenantShift shift = shiftRepository.findByIdWithDetails(shiftId)
                .orElseThrow(() -> new CalendarException(CalendarErrorCode.SHIFT_NOT_FOUND));
        return ShiftResponse.fromEntity(shift);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ShiftResponse> searchShifts(
            LocalDate workDate,
            LocalDate startDate,
            LocalDate endDate,
            String staffId,
            String serviceId,
            ShiftStatus status
    ) {
        List<TenantShift> shifts = shiftRepository.searchShifts(
                workDate, startDate, endDate, staffId, serviceId, status);

        return shifts.stream()
                .map(ShiftResponse::fromEntity)
                .collect(Collectors.toList());
    }

    @Override
    public ShiftResponse cancelShift(String shiftId) {
        return executeInTransaction(status -> {
            TenantShift shift = shiftRepository.findByIdWithDetails(shiftId)
                    .orElseThrow(() -> new CalendarException(CalendarErrorCode.SHIFT_NOT_FOUND));

            if (shift.getStatus() == ShiftStatus.CANCELLED) {
                throw new CalendarException(CalendarErrorCode.SHIFT_ALREADY_CANCELLED);
            }

            // Check if any slot in the shift operational window has confirmed appointments or is BOOKED
            long bookedCount = timeSlotRepository.sumBookedCountInWindow(
                    shift.getService().getId(),
                    shift.getWorkDate(),
                    shift.getShiftStart(),
                    shift.getShiftEnd()
            );
            long bookedSlots = timeSlotRepository.countBookedSlotsInWindow(
                    shift.getService().getId(),
                    shift.getWorkDate(),
                    shift.getShiftStart(),
                    shift.getShiftEnd()
            );

            if (bookedCount > 0 || bookedSlots > 0) {
                long countToReport = bookedCount > 0 ? bookedCount : bookedSlots;
                LogContract.warn(log, LogDomain.CALENDAR, "shift_cancel_blocked")
                        .outcome(LogOutcome.REJECTED)
                        .entity("shift", shiftId)
                        .field("bookedAppointments", countToReport)
                        .emit();
                throw new CalendarException(
                        CalendarErrorCode.SHIFT_CANNOT_CANCEL_WITH_BOOKED_SLOTS,
                        String.format("Ca trực hiện có %d lịch hẹn của khách hàng đã được xác nhận. Vui lòng hủy lịch hẹn hoặc điều chuyển nhân viên thay thế trước khi hủy ca trực này.", countToReport)
                );
            }

            shift.setStatus(ShiftStatus.CANCELLED);
            TenantShift updated = shiftRepository.save(shift);
            shiftRepository.flush();

            // Recalculate capacity of slots covered by this shift
            slotGeneratorService.recalculateSlotsForCancelledShift(updated);

            LogContract.info(log, LogDomain.CALENDAR, "shift_cancelled")
                    .outcome(LogOutcome.SUCCESS)
                    .entity("shift", shift.getId())
                    .field("staffCode", shift.getStaff().getStaffCode())
                    .field("workDate", shift.getWorkDate())
                    .emit();

            return ShiftResponse.fromEntity(updated);
        });
    }

    @Override
    public ShiftResponse updateShift(String shiftId, UpdateShiftRequest request) {
        TenantShift shift = shiftRepository.findByIdWithDetails(shiftId)
                .orElseThrow(() -> new CalendarException(CalendarErrorCode.SHIFT_NOT_FOUND));

        if (shift.getStatus() == ShiftStatus.CANCELLED) {
            throw new CalendarException(CalendarErrorCode.SHIFT_ALREADY_CANCELLED);
        }

        LocalTime newStart = request.getShiftStart();
        LocalTime newEnd = request.getShiftEnd();

        calendarValidator.validateTimeWindow(newStart, newEnd);
        calendarValidator.validateShiftDuration(newStart, newEnd, shift.getService());
        calendarValidator.validateNoOverlapExcludingShift(
                shift.getStaff().getId(), shift.getWorkDate(), newStart, newEnd, shift.getId());

        LocalTime oldStart = shift.getShiftStart();
        LocalTime oldEnd = shift.getShiftEnd();

        // Check if shift is shortened and if cut windows contain booked appointments
        long cutBookedCount = 0;
        if (newStart.isAfter(oldStart)) {
            cutBookedCount += timeSlotRepository.sumBookedCountInWindow(
                    shift.getService().getId(), shift.getWorkDate(), oldStart, newStart);
            cutBookedCount += timeSlotRepository.countBookedSlotsInWindow(
                    shift.getService().getId(), shift.getWorkDate(), oldStart, newStart);
        }
        if (newEnd.isBefore(oldEnd)) {
            cutBookedCount += timeSlotRepository.sumBookedCountInWindow(
                    shift.getService().getId(), shift.getWorkDate(), newEnd, oldEnd);
            cutBookedCount += timeSlotRepository.countBookedSlotsInWindow(
                    shift.getService().getId(), shift.getWorkDate(), newEnd, oldEnd);
        }

        if (cutBookedCount > 0) {
            LogContract.warn(log, LogDomain.CALENDAR, "shift_update_blocked")
                    .outcome(LogOutcome.REJECTED)
                    .entity("shift", shiftId)
                    .field("bookedAppointments", cutBookedCount)
                    .emit();
            throw new CalendarException(
                    CalendarErrorCode.SHIFT_CANNOT_TRUNCATE_WITH_BOOKED_SLOTS,
                    String.format("Ca trực hiện có %d lịch hẹn của khách hàng đã được xác nhận trong khoảng thời gian bị cắt giảm. Vui lòng hủy lịch hẹn hoặc điều chuyển nhân viên thay thế trước khi điều chỉnh ca trực này.", cutBookedCount)
            );
        }

        return executeInTransaction(status -> {
            shift.setShiftStart(newStart);
            shift.setShiftEnd(newEnd);
            TenantShift updated = shiftRepository.save(shift);
            shiftRepository.flush();

            // Recalculate/adjust slots for affected windows
            slotGeneratorService.recalculateSlotsForShiftAdjustment(updated, oldStart, oldEnd);

            LogContract.info(log, LogDomain.CALENDAR, "shift_updated")
                    .outcome(LogOutcome.SUCCESS)
                    .entity("shift", shift.getId())
                    .field("staffCode", shift.getStaff().getStaffCode())
                    .field("workDate", shift.getWorkDate())
                    .field("window", newStart + "-" + newEnd)
                    .emit();

            return ShiftResponse.fromEntity(updated);
        });
    }

    private List<LocalDate> resolveScheduledDates(CreateShiftRequest request) {
        LocalDate startDate = request.getWorkDate();
        LocalDate repeatUntil = request.getRepeatUntil();

        if (repeatUntil == null) {
            return List.of(startDate);
        }

        if (repeatUntil.isBefore(startDate)) {
            LogContract.warn(log, LogDomain.CALENDAR, "shift_validation")
                    .outcome(LogOutcome.REJECTED)
                    .field("repeatUntil", repeatUntil)
                    .field("startDate", startDate)
                    .reason("repeatUntil is before startDate")
                    .emit();
            throw new CalendarException(CalendarErrorCode.TIME_WINDOW_INVALID, "Ngày kết thúc lặp lại phải lớn hơn hoặc bằng ngày bắt đầu.");
        }

        List<DayOfWeek> repeatDays = request.getRepeatDays();
        List<LocalDate> dates = new ArrayList<>();
        LocalDate current = startDate;

        while (!current.isAfter(repeatUntil)) {
            if (repeatDays == null || repeatDays.isEmpty() || repeatDays.contains(current.getDayOfWeek())) {
                dates.add(current);
            }
            current = current.plusDays(1);
        }

        if (dates.isEmpty()) {
            throw new CalendarException(CalendarErrorCode.TIME_WINDOW_INVALID, "Không có ngày nào phù hợp với cấu hình lặp lại ca trực.");
        }
        return dates;
    }
}

