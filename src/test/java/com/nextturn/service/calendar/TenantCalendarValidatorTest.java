package com.nextturn.service.calendar;

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
import com.nextturn.service.calendar.impl.TenantCalendarValidatorImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TenantCalendarValidatorTest {

    @Mock
    private TenantStaffRepository staffRepository;

    @Mock
    private TenantServiceRepository serviceRepository;

    @Mock
    private TenantStaffServiceRepository staffServiceRepository;

    @Mock
    private TenantShiftRepository shiftRepository;

    @InjectMocks
    private TenantCalendarValidatorImpl validator;

    private TenantStaff staff;
    private TenantService service1;
    private TenantService service2;

    @BeforeEach
    void setUp() {
        staff = TenantStaff.builder()
                .id("staff-uuid-1")
                .staffCode("STAFF-0001")
                .fullName("Nguyễn Văn A")
                .email("staff.a@nextturn.com")
                .phoneNumber("0912345678")
                .role(StaffRole.COUNTER)
                .status(StaffStatus.ACTIVE)
                .build();

        service1 = TenantService.builder()
                .id("service-uuid-1")
                .serviceCode("SRV-001")
                .serviceName("Khám đa khoa tổng quát")
                .standardDuration(30)
                .build();

        service2 = TenantService.builder()
                .id("service-uuid-2")
                .serviceCode("SRV-002")
                .serviceName("Tư vấn trực tuyến")
                .standardDuration(15)
                .build();
    }

    @Test
    @DisplayName("validateStaff: should succeed when staff is found and ACTIVE")
    void validateStaff_Success() {
        when(staffRepository.findByIdAndIsDeletedFalse("staff-uuid-1")).thenReturn(Optional.of(staff));

        TenantStaff result = validator.validateStaff("staff-uuid-1");
        assertThat(result).isNotNull();
        assertThat(result.getId()).isEqualTo("staff-uuid-1");
    }

    @Test
    @DisplayName("validateStaff: should throw STAFF_NOT_FOUND when staff does not exist")
    void validateStaff_NotFound() {
        when(staffRepository.findByIdAndIsDeletedFalse("unknown")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> validator.validateStaff("unknown"))
                .isInstanceOf(CalendarException.class)
                .hasFieldOrPropertyWithValue("code", CalendarErrorCode.STAFF_NOT_FOUND.code());
    }

    @Test
    @DisplayName("validateStaff: should throw STAFF_NOT_ACTIVE when staff status is INACTIVE")
    void validateStaff_NotActive() {
        staff.setStatus(StaffStatus.INACTIVE);
        when(staffRepository.findByIdAndIsDeletedFalse("staff-uuid-1")).thenReturn(Optional.of(staff));

        assertThatThrownBy(() -> validator.validateStaff("staff-uuid-1"))
                .isInstanceOf(CalendarException.class)
                .hasFieldOrPropertyWithValue("code", CalendarErrorCode.STAFF_NOT_ACTIVE.code());
    }

    @Test
    @DisplayName("validateAndResolveService: should succeed when specific service is assigned to staff")
    void validateAndResolveService_WithExplicitAssignedService() {
        when(serviceRepository.findByIdAndIsDeletedFalse("service-uuid-1")).thenReturn(Optional.of(service1));
        when(staffServiceRepository.existsByStaffIdAndServiceId("staff-uuid-1", "service-uuid-1")).thenReturn(true);

        TenantService result = validator.validateAndResolveService(staff, "service-uuid-1");
        assertThat(result).isEqualTo(service1);
    }

    @Test
    @DisplayName("validateAndResolveService: should throw SERVICE_NOT_FOUND if serviceId does not exist")
    void validateAndResolveService_ServiceNotFound() {
        when(serviceRepository.findByIdAndIsDeletedFalse("unknown-srv")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> validator.validateAndResolveService(staff, "unknown-srv"))
                .isInstanceOf(CalendarException.class)
                .hasFieldOrPropertyWithValue("code", CalendarErrorCode.SERVICE_NOT_FOUND.code());
    }

    @Test
    @DisplayName("validateAndResolveService: should throw SERVICE_NOT_ASSIGNED if staff not assigned to service")
    void validateAndResolveService_NotAssigned() {
        when(serviceRepository.findByIdAndIsDeletedFalse("service-uuid-1")).thenReturn(Optional.of(service1));
        when(staffServiceRepository.existsByStaffIdAndServiceId("staff-uuid-1", "service-uuid-1")).thenReturn(false);

        assertThatThrownBy(() -> validator.validateAndResolveService(staff, "service-uuid-1"))
                .isInstanceOf(CalendarException.class)
                .hasFieldOrPropertyWithValue("code", CalendarErrorCode.SERVICE_NOT_ASSIGNED.code());
    }

    @Test
    @DisplayName("validateAndResolveService: should default to single assigned service if serviceId not passed")
    void validateAndResolveService_SingleAssignedDefault() {
        TenantStaffServiceAssignment assignment = TenantStaffServiceAssignment.builder()
                .id("assign-1")
                .staff(staff)
                .service(service1)
                .build();

        when(staffServiceRepository.findByStaffIdWithService("staff-uuid-1")).thenReturn(List.of(assignment));

        TenantService result = validator.validateAndResolveService(staff, null);
        assertThat(result).isEqualTo(service1);
    }

    @Test
    @DisplayName("validateAndResolveService: should throw STAFF_NO_SERVICE if staff has no assigned services")
    void validateAndResolveService_NoServices() {
        when(staffServiceRepository.findByStaffIdWithService("staff-uuid-1")).thenReturn(Collections.emptyList());

        assertThatThrownBy(() -> validator.validateAndResolveService(staff, null))
                .isInstanceOf(CalendarException.class)
                .hasFieldOrPropertyWithValue("code", CalendarErrorCode.STAFF_NO_SERVICE.code());
    }

    @Test
    @DisplayName("validateAndResolveService: should throw SERVICE_REQUIRED_FOR_MULTI_SERVICE_STAFF if multiple services and none specified")
    void validateAndResolveService_MultipleServicesAmbiguous() {
        TenantStaffServiceAssignment a1 = TenantStaffServiceAssignment.builder().id("a1").staff(staff).service(service1).build();
        TenantStaffServiceAssignment a2 = TenantStaffServiceAssignment.builder().id("a2").staff(staff).service(service2).build();

        when(staffServiceRepository.findByStaffIdWithService("staff-uuid-1")).thenReturn(List.of(a1, a2));

        assertThatThrownBy(() -> validator.validateAndResolveService(staff, null))
                .isInstanceOf(CalendarException.class)
                .hasFieldOrPropertyWithValue("code", CalendarErrorCode.SERVICE_REQUIRED_FOR_MULTI_SERVICE_STAFF.code());
    }

    @Test
    @DisplayName("validateWorkDate: should succeed for today and future dates")
    void validateWorkDate_Success() {
        validator.validateWorkDate(LocalDate.now());
        validator.validateWorkDate(LocalDate.now().plusDays(5));
    }

    @Test
    @DisplayName("validateWorkDate: should throw DATE_IN_PAST for past dates")
    void validateWorkDate_PastDate() {
        assertThatThrownBy(() -> validator.validateWorkDate(LocalDate.now().minusDays(1)))
                .isInstanceOf(CalendarException.class)
                .hasFieldOrPropertyWithValue("code", CalendarErrorCode.DATE_IN_PAST.code());
    }

    @Test
    @DisplayName("validateTimeWindow: should succeed when start is before end")
    void validateTimeWindow_Success() {
        validator.validateTimeWindow(LocalTime.of(8, 0), LocalTime.of(12, 0));
    }

    @Test
    @DisplayName("validateTimeWindow: should throw TIME_WINDOW_INVALID when start >= end")
    void validateTimeWindow_Invalid() {
        assertThatThrownBy(() -> validator.validateTimeWindow(LocalTime.of(12, 0), LocalTime.of(8, 0)))
                .isInstanceOf(CalendarException.class)
                .hasFieldOrPropertyWithValue("code", CalendarErrorCode.TIME_WINDOW_INVALID.code());

        assertThatThrownBy(() -> validator.validateTimeWindow(LocalTime.of(8, 0), LocalTime.of(8, 0)))
                .isInstanceOf(CalendarException.class)
                .hasFieldOrPropertyWithValue("code", CalendarErrorCode.TIME_WINDOW_INVALID.code());
    }

    @Test
    @DisplayName("validateShiftDuration: should succeed when duration >= standard duration")
    void validateShiftDuration_Success() {
        validator.validateShiftDuration(LocalTime.of(8, 0), LocalTime.of(8, 30), service1);
        validator.validateShiftDuration(LocalTime.of(8, 0), LocalTime.of(9, 0), service1);
    }

    @Test
    @DisplayName("validateShiftDuration: should throw DURATION_TOO_SHORT when duration < standard duration")
    void validateShiftDuration_TooShort() {
        assertThatThrownBy(() -> validator.validateShiftDuration(LocalTime.of(8, 0), LocalTime.of(8, 20), service1))
                .isInstanceOf(CalendarException.class)
                .hasFieldOrPropertyWithValue("code", CalendarErrorCode.DURATION_TOO_SHORT.code())
                .hasMessageContaining("30 phút");
    }

    @Test
    @DisplayName("validateNoOverlap: should succeed when no overlapping shifts exist")
    void validateNoOverlap_Success() {
        LocalDate date = LocalDate.now().plusDays(1);
        when(shiftRepository.findOverlappingShifts("staff-uuid-1", date, LocalTime.of(8, 0), LocalTime.of(12, 0)))
                .thenReturn(Collections.emptyList());

        validator.validateNoOverlap("staff-uuid-1", date, LocalTime.of(8, 0), LocalTime.of(12, 0));
    }

    @Test
    @DisplayName("validateNoOverlap: should throw SHIFT_OVERLAPPING with exact required Vietnamese error message")
    void validateNoOverlap_OverlappingDetected() {
        LocalDate date = LocalDate.now().plusDays(1);
        TenantShift existing = TenantShift.builder()
                .id("shift-exist")
                .staff(staff)
                .workDate(date)
                .shiftStart(LocalTime.of(8, 0, 0))
                .shiftEnd(LocalTime.of(12, 0, 0))
                .build();

        when(shiftRepository.findOverlappingShifts("staff-uuid-1", date, LocalTime.of(10, 0), LocalTime.of(14, 0)))
                .thenReturn(List.of(existing));

        assertThatThrownBy(() -> validator.validateNoOverlap("staff-uuid-1", date, LocalTime.of(10, 0), LocalTime.of(14, 0)))
                .isInstanceOf(CalendarException.class)
                .hasFieldOrPropertyWithValue("code", CalendarErrorCode.SHIFT_OVERLAPPING.code())
                .hasMessage("Nhân viên đã được phân công ca trực khác từ 08:00 đến 12:00 trong ngày này. Vui lòng kiểm tra lại.");
    }

    @Test
    @DisplayName("validateNoOverlapExcludingShift: should succeed when no other overlapping shifts exist")
    void validateNoOverlapExcludingShift_Success() {
        LocalDate date = LocalDate.now().plusDays(1);
        when(shiftRepository.findOverlappingShiftsExcludingShift("staff-uuid-1", date, LocalTime.of(8, 0), LocalTime.of(12, 0), "shift-current"))
                .thenReturn(Collections.emptyList());

        validator.validateNoOverlapExcludingShift("staff-uuid-1", date, LocalTime.of(8, 0), LocalTime.of(12, 0), "shift-current");
    }

    @Test
    @DisplayName("validateNoOverlapExcludingShift: should throw SHIFT_OVERLAPPING when another overlapping shift is detected")
    void validateNoOverlapExcludingShift_OverlappingDetected() {
        LocalDate date = LocalDate.now().plusDays(1);
        TenantShift existing = TenantShift.builder()
                .id("shift-exist")
                .staff(staff)
                .workDate(date)
                .shiftStart(LocalTime.of(8, 0, 0))
                .shiftEnd(LocalTime.of(12, 0, 0))
                .build();

        when(shiftRepository.findOverlappingShiftsExcludingShift("staff-uuid-1", date, LocalTime.of(10, 0), LocalTime.of(14, 0), "shift-current"))
                .thenReturn(List.of(existing));

        assertThatThrownBy(() -> validator.validateNoOverlapExcludingShift("staff-uuid-1", date, LocalTime.of(10, 0), LocalTime.of(14, 0), "shift-current"))
                .isInstanceOf(CalendarException.class)
                .hasFieldOrPropertyWithValue("code", CalendarErrorCode.SHIFT_OVERLAPPING.code())
                .hasMessage("Nhân viên đã được phân công ca trực khác từ 08:00 đến 12:00 trong ngày này. Vui lòng kiểm tra lại.");
    }
}

