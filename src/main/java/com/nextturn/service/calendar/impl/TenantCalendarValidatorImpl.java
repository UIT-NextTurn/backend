package com.nextturn.service.calendar.impl;

import com.nextturn.entity.TenantService;
import com.nextturn.entity.TenantShift;
import com.nextturn.entity.TenantStaff;
import com.nextturn.entity.TenantStaffServiceAssignment;
import com.nextturn.enums.StaffRole;
import com.nextturn.enums.StaffStatus;
import com.nextturn.exception.calendar.CalendarErrorCode;
import com.nextturn.exception.calendar.CalendarException;
import com.nextturn.repository.calendar.TenantShiftRepository;
import com.nextturn.repository.service.TenantServiceRepository;
import com.nextturn.repository.staff.TenantStaffRepository;
import com.nextturn.repository.staff.TenantStaffServiceRepository;
import com.nextturn.service.calendar.TenantCalendarValidator;
import com.nextturn.infrastructure.logging.LogContract;
import com.nextturn.infrastructure.logging.LogDomain;
import com.nextturn.infrastructure.logging.LogOutcome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/**
 * Default implementation of {@link TenantCalendarValidator} enforcing business rules for calendar shifts.
 */
@Component
public class TenantCalendarValidatorImpl implements TenantCalendarValidator {

    private static final Logger log = LoggerFactory.getLogger(TenantCalendarValidatorImpl.class);

    private final TenantStaffRepository staffRepository;
    private final TenantServiceRepository serviceRepository;
    private final TenantStaffServiceRepository staffServiceRepository;
    private final TenantShiftRepository shiftRepository;

    public TenantCalendarValidatorImpl(
            TenantStaffRepository staffRepository,
            TenantServiceRepository serviceRepository,
            TenantStaffServiceRepository staffServiceRepository,
            TenantShiftRepository shiftRepository
    ) {
        this.staffRepository = staffRepository;
        this.serviceRepository = serviceRepository;
        this.staffServiceRepository = staffServiceRepository;
        this.shiftRepository = shiftRepository;
    }

    @Override
    public TenantStaff validateStaff(String staffId) {
        TenantStaff staff = staffRepository.findByIdAndIsDeletedFalse(staffId)
                .orElseThrow(() -> new CalendarException(CalendarErrorCode.STAFF_NOT_FOUND));

        if (staff.getStatus() != StaffStatus.ACTIVE) {
            LogContract.warn(log, LogDomain.CALENDAR, "shift_validation")
                    .outcome(LogOutcome.REJECTED)
                    .entity("staff", staff.getStaffCode())
                    .field("status", staff.getStatus())
                    .reason("Staff not active")
                    .emit();
            throw new CalendarException(CalendarErrorCode.STAFF_NOT_ACTIVE);
        }

        if (staff.getRole() != StaffRole.SPECIALIST) {
            LogContract.warn(log, LogDomain.CALENDAR, "shift_validation")
                    .outcome(LogOutcome.REJECTED)
                    .entity("staff", staff.getStaffCode())
                    .field("role", staff.getRole())
                    .reason("Only SPECIALIST can be assigned shifts")
                    .emit();
            throw new CalendarException(CalendarErrorCode.STAFF_NOT_SPECIALIST);
        }
        return staff;
    }

    @Override
    public TenantService validateAndResolveService(TenantStaff staff, String requestedServiceId) {
        if (requestedServiceId != null && !requestedServiceId.isBlank()) {
            TenantService service = serviceRepository.findByIdAndIsDeletedFalse(requestedServiceId.trim())
                    .orElseThrow(() -> new CalendarException(CalendarErrorCode.SERVICE_NOT_FOUND));

            boolean assigned = staffServiceRepository.existsByStaffIdAndServiceId(staff.getId(), service.getId());
            if (!assigned) {
                LogContract.warn(log, LogDomain.CALENDAR, "shift_validation")
                        .outcome(LogOutcome.REJECTED)
                        .entity("staff", staff.getStaffCode())
                        .field("serviceCode", service.getServiceCode())
                        .reason("Service not assigned to staff")
                        .emit();
                throw new CalendarException(CalendarErrorCode.SERVICE_NOT_ASSIGNED);
            }
            return service;
        }

        List<TenantStaffServiceAssignment> assignments = staffServiceRepository.findByStaffIdWithService(staff.getId());
        if (assignments.isEmpty()) {
            LogContract.warn(log, LogDomain.CALENDAR, "shift_validation")
                    .outcome(LogOutcome.REJECTED)
                    .entity("staff", staff.getStaffCode())
                    .reason("Staff has no assigned services")
                    .emit();
            throw new CalendarException(CalendarErrorCode.STAFF_NO_SERVICE);
        }

        if (assignments.size() > 1) {
            LogContract.warn(log, LogDomain.CALENDAR, "shift_validation")
                    .outcome(LogOutcome.REJECTED)
                    .entity("staff", staff.getStaffCode())
                    .field("assignedCount", assignments.size())
                    .reason("Specific serviceId required")
                    .emit();
            throw new CalendarException(CalendarErrorCode.SERVICE_REQUIRED_FOR_MULTI_SERVICE_STAFF);
        }

        return assignments.get(0).getService();
    }

    @Override
    public void validateWorkDate(LocalDate workDate) {
        if (workDate == null || workDate.isBefore(LocalDate.now())) {
            LogContract.warn(log, LogDomain.CALENDAR, "shift_validation")
                    .outcome(LogOutcome.REJECTED)
                    .field("workDate", workDate)
                    .reason("Work date in the past")
                    .emit();
            throw new CalendarException(CalendarErrorCode.DATE_IN_PAST);
        }
    }

    @Override
    public void validateTimeWindow(LocalTime shiftStart, LocalTime shiftEnd) {
        if (shiftStart == null || shiftEnd == null || !shiftStart.isBefore(shiftEnd)) {
            LogContract.warn(log, LogDomain.CALENDAR, "shift_validation")
                    .outcome(LogOutcome.REJECTED)
                    .field("shiftStart", shiftStart)
                    .field("shiftEnd", shiftEnd)
                    .reason("Invalid shift time window")
                    .emit();
            throw new CalendarException(CalendarErrorCode.TIME_WINDOW_INVALID);
        }
    }

    @Override
    public void validateShiftDuration(LocalTime shiftStart, LocalTime shiftEnd, TenantService service) {
        long shiftMinutes = Duration.between(shiftStart, shiftEnd).toMinutes();
        int standardDuration = service.getStandardDuration() != null ? service.getStandardDuration() : 15;

        if (shiftMinutes < standardDuration) {
            LogContract.warn(log, LogDomain.CALENDAR, "shift_validation")
                    .outcome(LogOutcome.REJECTED)
                    .field("shiftMinutes", shiftMinutes)
                    .field("serviceCode", service.getServiceCode())
                    .field("standardDuration", standardDuration)
                    .reason("Duration shorter than service standard")
                    .emit();
            String customMessage = String.format(
                    "Tổng thời lượng của ca (%d phút) tối thiểu phải lớn hơn hoặc bằng thời lượng phục vụ chuẩn (%d phút) của dịch vụ mà nhân viên đó đảm nhiệm.",
                    shiftMinutes,
                    standardDuration
            );
            throw new CalendarException(CalendarErrorCode.DURATION_TOO_SHORT, customMessage);
        }
    }

    @Override
    public void validateNoOverlap(String staffId, LocalDate workDate, LocalTime shiftStart, LocalTime shiftEnd) {
        List<TenantShift> overlapping = shiftRepository.findOverlappingShifts(staffId, workDate, shiftStart, shiftEnd);
        if (!overlapping.isEmpty()) {
            TenantShift existing = overlapping.get(0);
            LogContract.warn(log, LogDomain.CALENDAR, "shift_overlap")
                    .outcome(LogOutcome.REJECTED)
                    .entity("staff", staffId)
                    .field("workDate", workDate)
                    .field("requested", shiftStart + "-" + shiftEnd)
                    .field("existing", existing.getShiftStart() + "-" + existing.getShiftEnd())
                    .emit();

            String message = String.format(
                    "Nhân viên đã được phân công ca trực khác từ %s đến %s trong ngày này. Vui lòng kiểm tra lại.",
                    existing.getShiftStart(),
                    existing.getShiftEnd()
            );
            throw new CalendarException(CalendarErrorCode.SHIFT_OVERLAPPING, message);
        }
    }

    @Override
    public void validateNoOverlapExcludingShift(
            String staffId,
            LocalDate workDate,
            LocalTime shiftStart,
            LocalTime shiftEnd,
            String excludedShiftId
    ) {
        List<TenantShift> overlapping = shiftRepository.findOverlappingShiftsExcludingShift(
                staffId, workDate, shiftStart, shiftEnd, excludedShiftId);
        if (!overlapping.isEmpty()) {
            TenantShift existing = overlapping.get(0);
            LogContract.warn(log, LogDomain.CALENDAR, "shift_overlap")
                    .outcome(LogOutcome.REJECTED)
                    .entity("staff", staffId)
                    .field("excludedShiftId", excludedShiftId)
                    .field("workDate", workDate)
                    .field("requested", shiftStart + "-" + shiftEnd)
                    .field("existing", existing.getShiftStart() + "-" + existing.getShiftEnd())
                    .emit();

            String message = String.format(
                    "Nhân viên đã được phân công ca trực khác từ %s đến %s trong ngày này. Vui lòng kiểm tra lại.",
                    existing.getShiftStart(),
                    existing.getShiftEnd()
            );
            throw new CalendarException(CalendarErrorCode.SHIFT_OVERLAPPING, message);
        }
    }
}

