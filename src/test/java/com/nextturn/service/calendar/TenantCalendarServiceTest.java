package com.nextturn.service.calendar;

import com.nextturn.dto.calendar.CreateShiftRequest;
import com.nextturn.dto.calendar.ShiftResponse;
import com.nextturn.dto.calendar.UpdateShiftRequest;
import com.nextturn.entity.TenantService;
import com.nextturn.entity.TenantShift;
import com.nextturn.entity.TenantStaff;
import com.nextturn.enums.ShiftStatus;
import com.nextturn.enums.StaffRole;
import com.nextturn.enums.StaffStatus;
import com.nextturn.exception.calendar.CalendarErrorCode;
import com.nextturn.exception.calendar.CalendarException;
import com.nextturn.infrastructure.multitenancy.TenantContextHolder;
import com.nextturn.repository.calendar.TenantShiftRepository;
import com.nextturn.repository.calendar.TenantTimeSlotRepository;
import com.nextturn.service.calendar.impl.TenantCalendarServiceImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TenantCalendarServiceTest {

    private static final String TENANT_CODE = "tenant-alpha";

    @Mock
    private TenantShiftRepository shiftRepository;

    @Mock
    private TenantTimeSlotRepository timeSlotRepository;

    @Mock
    private TenantCalendarValidator calendarValidator;

    @Mock
    private TenantTimeSlotGeneratorService slotGeneratorService;

    @InjectMocks
    private TenantCalendarServiceImpl calendarService;

    private TenantStaff staff;
    private TenantService service;

    @BeforeEach
    void setUp() {
        TenantContextHolder.setTenantCode(TENANT_CODE);

        staff = TenantStaff.builder()
                .id("staff-uuid-1")
                .staffCode("STAFF-0001")
                .fullName("Nguyễn Văn A")
                .role(StaffRole.COUNTER)
                .status(StaffStatus.ACTIVE)
                .build();

        service = TenantService.builder()
                .id("srv-uuid-1")
                .serviceCode("SRV-001")
                .serviceName("Đăng ký hồ sơ")
                .standardDuration(30)
                .build();
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
    }

    @Test
    @DisplayName("createShifts: single date shift successfully created and time slots generated")
    void createShifts_SingleDate_Success() {
        LocalDate tomorrow = LocalDate.now().plusDays(1);
        CreateShiftRequest request = CreateShiftRequest.builder()
                .staffId("staff-uuid-1")
                .serviceId("srv-uuid-1")
                .workDate(tomorrow)
                .shiftStart(LocalTime.of(8, 0))
                .shiftEnd(LocalTime.of(12, 0))
                .build();

        when(calendarValidator.validateStaff("staff-uuid-1")).thenReturn(staff);
        when(calendarValidator.validateAndResolveService(staff, "srv-uuid-1")).thenReturn(service);
        when(shiftRepository.save(any(TenantShift.class))).thenAnswer(invocation -> invocation.getArgument(0));

        List<ShiftResponse> responses = calendarService.createShifts(request);

        assertThat(responses).hasSize(1);
        ShiftResponse shiftResp = responses.get(0);
        assertThat(shiftResp.getStaffId()).isEqualTo("staff-uuid-1");
        assertThat(shiftResp.getServiceId()).isEqualTo("srv-uuid-1");
        assertThat(shiftResp.getWorkDate()).isEqualTo(tomorrow);
        assertThat(shiftResp.getStatus()).isEqualTo(ShiftStatus.SCHEDULED);

        verify(calendarValidator).validateWorkDate(tomorrow);
        verify(calendarValidator).validateNoOverlap("staff-uuid-1", tomorrow, LocalTime.of(8, 0), LocalTime.of(12, 0));
        verify(shiftRepository).save(any(TenantShift.class));
        verify(slotGeneratorService).generateTimeSlotsForShift(any(TenantShift.class));
    }

    @Test
    @DisplayName("createShifts: recurring shifts across date range created with day-of-week filtering")
    void createShifts_Recurring_Success() {
        LocalDate monday = LocalDate.now().plusDays(7);
        while (monday.getDayOfWeek() != DayOfWeek.MONDAY) {
            monday = monday.plusDays(1);
        }
        LocalDate friday = monday.plusDays(4);

        CreateShiftRequest request = CreateShiftRequest.builder()
                .staffId("staff-uuid-1")
                .serviceId("srv-uuid-1")
                .workDate(monday)
                .repeatUntil(friday)
                .repeatDays(List.of(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY))
                .shiftStart(LocalTime.of(8, 0))
                .shiftEnd(LocalTime.of(12, 0))
                .build();

        when(calendarValidator.validateStaff("staff-uuid-1")).thenReturn(staff);
        when(calendarValidator.validateAndResolveService(staff, "srv-uuid-1")).thenReturn(service);
        when(shiftRepository.save(any(TenantShift.class))).thenAnswer(invocation -> invocation.getArgument(0));

        List<ShiftResponse> responses = calendarService.createShifts(request);

        assertThat(responses).hasSize(3);
        verify(shiftRepository, org.mockito.Mockito.times(3)).save(any(TenantShift.class));
        verify(slotGeneratorService, org.mockito.Mockito.times(3)).generateTimeSlotsForShift(any(TenantShift.class));
    }

    @Test
    @DisplayName("getShiftById: returns shift details when found")
    void getShiftById_Success() {
        TenantShift shift = TenantShift.builder()
                .id("shift-123")
                .staff(staff)
                .service(service)
                .workDate(LocalDate.now().plusDays(1))
                .shiftStart(LocalTime.of(8, 0))
                .shiftEnd(LocalTime.of(12, 0))
                .status(ShiftStatus.SCHEDULED)
                .build();

        when(shiftRepository.findByIdWithDetails("shift-123")).thenReturn(Optional.of(shift));

        ShiftResponse response = calendarService.getShiftById("shift-123");
        assertThat(response).isNotNull();
        assertThat(response.getId()).isEqualTo("shift-123");
        assertThat(response.getStaffFullName()).isEqualTo("Nguyễn Văn A");
    }

    @Test
    @DisplayName("getShiftById: throws SHIFT_NOT_FOUND when shift does not exist")
    void getShiftById_NotFound() {
        when(shiftRepository.findByIdWithDetails("unknown")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> calendarService.getShiftById("unknown"))
                .isInstanceOf(CalendarException.class)
                .hasFieldOrPropertyWithValue("code", CalendarErrorCode.SHIFT_NOT_FOUND.code());
    }

    @Test
    @DisplayName("cancelShift: updates status to CANCELLED and recalculates slots when no booked appointments exist")
    void cancelShift_Success() {
        TenantShift shift = TenantShift.builder()
                .id("shift-123")
                .staff(staff)
                .service(service)
                .workDate(LocalDate.now().plusDays(1))
                .shiftStart(LocalTime.of(8, 0))
                .shiftEnd(LocalTime.of(12, 0))
                .status(ShiftStatus.SCHEDULED)
                .build();

        when(shiftRepository.findByIdWithDetails("shift-123")).thenReturn(Optional.of(shift));
        when(timeSlotRepository.sumBookedCountInWindow(any(), any(), any(), any())).thenReturn(0L);
        when(timeSlotRepository.countBookedSlotsInWindow(any(), any(), any(), any())).thenReturn(0L);
        when(shiftRepository.save(any(TenantShift.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ShiftResponse response = calendarService.cancelShift("shift-123");

        assertThat(response.getStatus()).isEqualTo(ShiftStatus.CANCELLED);
        verify(slotGeneratorService).recalculateSlotsForCancelledShift(shift);
    }

    @Test
    @DisplayName("cancelShift: blocked with exact Vietnamese message when shift window contains booked appointments")
    void cancelShift_BlockedWhenBookedAppointmentsExist() {
        TenantShift shift = TenantShift.builder()
                .id("shift-123")
                .staff(staff)
                .service(service)
                .workDate(LocalDate.now().plusDays(1))
                .shiftStart(LocalTime.of(8, 0))
                .shiftEnd(LocalTime.of(12, 0))
                .status(ShiftStatus.SCHEDULED)
                .build();

        when(shiftRepository.findByIdWithDetails("shift-123")).thenReturn(Optional.of(shift));
        when(timeSlotRepository.sumBookedCountInWindow(any(), any(), any(), any())).thenReturn(3L);

        assertThatThrownBy(() -> calendarService.cancelShift("shift-123"))
                .isInstanceOf(CalendarException.class)
                .hasFieldOrPropertyWithValue("code", CalendarErrorCode.SHIFT_CANNOT_CANCEL_WITH_BOOKED_SLOTS.code())
                .hasMessage("Ca trực hiện có 3 lịch hẹn của khách hàng đã được xác nhận. Vui lòng hủy lịch hẹn hoặc điều chuyển nhân viên thay thế trước khi hủy ca trực này.");
    }

    @Test
    @DisplayName("cancelShift: throws SHIFT_ALREADY_CANCELLED if shift is already CANCELLED")
    void cancelShift_AlreadyCancelled() {
        TenantShift shift = TenantShift.builder()
                .id("shift-123")
                .staff(staff)
                .service(service)
                .workDate(LocalDate.now().plusDays(1))
                .shiftStart(LocalTime.of(8, 0))
                .shiftEnd(LocalTime.of(12, 0))
                .status(ShiftStatus.CANCELLED)
                .build();

        when(shiftRepository.findByIdWithDetails("shift-123")).thenReturn(Optional.of(shift));

        assertThatThrownBy(() -> calendarService.cancelShift("shift-123"))
                .isInstanceOf(CalendarException.class)
                .hasFieldOrPropertyWithValue("code", CalendarErrorCode.SHIFT_ALREADY_CANCELLED.code());
    }

    @Test
    @DisplayName("updateShift: successfully adjusts shift time window and recalculates slots")
    void updateShift_Success() {
        TenantShift shift = TenantShift.builder()
                .id("shift-123")
                .staff(staff)
                .service(service)
                .workDate(LocalDate.now().plusDays(1))
                .shiftStart(LocalTime.of(8, 0))
                .shiftEnd(LocalTime.of(12, 0))
                .status(ShiftStatus.SCHEDULED)
                .build();

        UpdateShiftRequest request = UpdateShiftRequest.builder()
                .shiftStart(LocalTime.of(8, 30))
                .shiftEnd(LocalTime.of(12, 0))
                .build();

        when(shiftRepository.findByIdWithDetails("shift-123")).thenReturn(Optional.of(shift));
        when(timeSlotRepository.sumBookedCountInWindow(any(), any(), any(), any())).thenReturn(0L);
        when(timeSlotRepository.countBookedSlotsInWindow(any(), any(), any(), any())).thenReturn(0L);
        when(shiftRepository.save(any(TenantShift.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ShiftResponse response = calendarService.updateShift("shift-123", request);

        assertThat(response).isNotNull();
        assertThat(response.getShiftStart()).isEqualTo(LocalTime.of(8, 30));
        assertThat(response.getShiftEnd()).isEqualTo(LocalTime.of(12, 0));

        verify(calendarValidator).validateTimeWindow(LocalTime.of(8, 30), LocalTime.of(12, 0));
        verify(calendarValidator).validateShiftDuration(LocalTime.of(8, 30), LocalTime.of(12, 0), service);
        verify(calendarValidator).validateNoOverlapExcludingShift("staff-uuid-1", shift.getWorkDate(), LocalTime.of(8, 30), LocalTime.of(12, 0), "shift-123");
        verify(slotGeneratorService).recalculateSlotsForShiftAdjustment(shift, LocalTime.of(8, 0), LocalTime.of(12, 0));
    }

    @Test
    @DisplayName("updateShift: blocked when cut window contains booked appointments")
    void updateShift_BlockedWhenCutWindowHasBookedAppointments() {
        TenantShift shift = TenantShift.builder()
                .id("shift-123")
                .staff(staff)
                .service(service)
                .workDate(LocalDate.now().plusDays(1))
                .shiftStart(LocalTime.of(8, 0))
                .shiftEnd(LocalTime.of(12, 0))
                .status(ShiftStatus.SCHEDULED)
                .build();

        UpdateShiftRequest request = UpdateShiftRequest.builder()
                .shiftStart(LocalTime.of(9, 0))
                .shiftEnd(LocalTime.of(12, 0))
                .build();

        when(shiftRepository.findByIdWithDetails("shift-123")).thenReturn(Optional.of(shift));
        when(timeSlotRepository.sumBookedCountInWindow(any(), any(), eq(LocalTime.of(8, 0)), eq(LocalTime.of(9, 0))))
                .thenReturn(2L);

        assertThatThrownBy(() -> calendarService.updateShift("shift-123", request))
                .isInstanceOf(CalendarException.class)
                .hasFieldOrPropertyWithValue("code", CalendarErrorCode.SHIFT_CANNOT_TRUNCATE_WITH_BOOKED_SLOTS.code())
                .hasMessage("Ca trực hiện có 2 lịch hẹn của khách hàng đã được xác nhận trong khoảng thời gian bị cắt giảm. Vui lòng hủy lịch hẹn hoặc điều chuyển nhân viên thay thế trước khi điều chỉnh ca trực này.");
    }
}
