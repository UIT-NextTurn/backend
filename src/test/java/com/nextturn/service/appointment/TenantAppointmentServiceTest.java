package com.nextturn.service.appointment;

import com.nextturn.dto.appointment.AppointmentResponse;
import com.nextturn.dto.appointment.CancelAppointmentRequest;
import com.nextturn.dto.appointment.CreateAppointmentRequest;
import com.nextturn.dto.appointment.RescheduleAppointmentRequest;
import com.nextturn.dto.calendar.CalendarSettingsResponse;
import com.nextturn.dto.calendar.SlotHoldingResponse;
import com.nextturn.dto.calendar.TimeSlotResponse;
import com.nextturn.entity.CentralGlobalUserAppointment;
import com.nextturn.entity.CentralTenant;
import com.nextturn.entity.TenantAppointment;
import com.nextturn.entity.TenantService;
import com.nextturn.entity.TenantTimeSlot;
import com.nextturn.enums.AppointmentStatus;
import com.nextturn.enums.TenantStatus;
import com.nextturn.enums.TimeSlotStatus;
import com.nextturn.exception.appointment.AppointmentErrorCode;
import com.nextturn.exception.appointment.AppointmentException;
import com.nextturn.exception.calendar.CalendarErrorCode;
import com.nextturn.exception.calendar.CalendarException;
import com.nextturn.infrastructure.multitenancy.TenantContextHolder;
import com.nextturn.repository.appointment.CentralGlobalUserAppointmentRepository;
import com.nextturn.repository.appointment.TenantAppointmentRepository;
import com.nextturn.repository.auth.CentralTenantRepository;
import com.nextturn.repository.calendar.TenantTimeSlotRepository;
import com.nextturn.service.appointment.impl.TenantAppointmentServiceImpl;
import com.nextturn.service.calendar.TenantCalendarSettingsService;
import com.nextturn.service.calendar.TenantTimeSlotGeneratorService;
import com.nextturn.service.calendar.TenantTimeSlotHoldingService;
import com.nextturn.service.calendar.TenantTimeSlotQueryService;
import com.nextturn.service.notification.EmailService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TenantAppointmentServiceTest {

    private static final String TENANT_CODE = "clinic-alpha";

    @Mock
    private TenantAppointmentRepository appointmentRepository;

    @Mock
    private TenantTimeSlotRepository timeSlotRepository;

    @Mock
    private TenantTimeSlotQueryService timeSlotQueryService;

    @Mock
    private TenantTimeSlotHoldingService timeSlotHoldingService;

    @Mock
    private TenantTimeSlotGeneratorService timeSlotGeneratorService;

    @Mock
    private TenantCalendarSettingsService calendarSettingsService;

    @Mock
    private CentralGlobalUserAppointmentRepository globalUserAppointmentRepository;

    @Mock
    private CentralTenantRepository centralTenantRepository;

    @Mock
    private EmailService emailService;

    @InjectMocks
    private TenantAppointmentServiceImpl appointmentService;

    private TenantService service;
    private TenantTimeSlot heldSlot;
    private CentralTenant tenant;

    @BeforeEach
    void setUp() {
        TenantContextHolder.setTenantCode(TENANT_CODE);

        tenant = CentralTenant.builder()
                .id("tenant-uuid-1")
                .tenantCode(TENANT_CODE)
                .tenantName("Phòng khám Đa khoa Alpha")
                .status(TenantStatus.ACTIVE)
                .build();

        service = TenantService.builder()
                .id("srv-uuid-1")
                .serviceCode("SVC-0001")
                .serviceName("Khám Nội Tổng Quát")
                .standardDuration(30)
                .build();

        heldSlot = TenantTimeSlot.builder()
                .id("slot-uuid-1")
                .service(service)
                .workDate(LocalDate.now().plusDays(2))
                .slotStart(LocalTime.of(9, 0))
                .slotEnd(LocalTime.of(9, 30))
                .capacityPerSlot(2)
                .bookedCount(0)
                .status(TimeSlotStatus.HOLDING)
                .holdingToken("hold-tok-123")
                .holdingExpiresAt(LocalDateTime.now().plusMinutes(9))
                .build();

        CalendarSettingsResponse defaultSettings = CalendarSettingsResponse.builder()
                .minLeadTimeHours(2)
                .maxAdvanceBookingDays(30)
                .cancellationDeadlineHours(2)
                .build();
        lenient().when(calendarSettingsService.getSettings()).thenReturn(defaultSettings);
        lenient().when(centralTenantRepository.findByTenantCodeIgnoreCaseAndIsDeletedFalse(TENANT_CODE))
                .thenReturn(Optional.of(tenant));
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
    }

    @Test
    @DisplayName("REQ-APT-01: getAvailableSlotsForBooking returns slots when date is within advance window and slots exist")
    void getAvailableSlotsForBooking_Success() {
        LocalDate targetDate = LocalDate.now().plusDays(3);
        TimeSlotResponse slotResp = TimeSlotResponse.fromEntity(heldSlot);
        when(timeSlotQueryService.getAvailableSlots("srv-uuid-1", targetDate)).thenReturn(List.of(slotResp));

        List<TimeSlotResponse> result = appointmentService.getAvailableSlotsForBooking("srv-uuid-1", targetDate);

        assertThat(result).hasSize(1);
    }

    @Test
    @DisplayName("REQ-APT-01: getAvailableSlotsForBooking throws NO_AVAILABLE_SLOTS when no slots remain on selected date")
    void getAvailableSlotsForBooking_NoSlots_ThrowsNotFound() {
        LocalDate targetDate = LocalDate.now().plusDays(3);
        when(timeSlotQueryService.getAvailableSlots("srv-uuid-1", targetDate)).thenReturn(List.of());

        assertThatThrownBy(() -> appointmentService.getAvailableSlotsForBooking("srv-uuid-1", targetDate))
                .isInstanceOf(AppointmentException.class)
                .hasFieldOrPropertyWithValue("code", AppointmentErrorCode.NO_AVAILABLE_SLOTS.code())
                .hasMessage("Dịch vụ trong ngày đã chọn hiện không còn khung giờ trống khả dụng. Quý khách vui lòng chọn ngày khác trong lịch mở hẹn.");
    }

    @Test
    @DisplayName("REQ-APT-01: getAvailableSlotsForBooking throws BOOKING_WINDOW_INVALID when date exceeds max_advance_booking_days")
    void getAvailableSlotsForBooking_ExceedsMaxAdvanceDays_ThrowsBadRequest() {
        LocalDate tooFar = LocalDate.now().plusDays(45);

        assertThatThrownBy(() -> appointmentService.getAvailableSlotsForBooking("srv-uuid-1", tooFar))
                .isInstanceOf(AppointmentException.class)
                .hasFieldOrPropertyWithValue("code", AppointmentErrorCode.BOOKING_WINDOW_INVALID.code());
    }

    @Test
    @DisplayName("REQ-APT-02: holdSlotForBooking maps calendar race-condition conflict to Exact SRS message")
    void holdSlotForBooking_Conflict_ThrowsSlotUnavailable() {
        when(timeSlotHoldingService.holdSlot("slot-uuid-1", 10))
                .thenThrow(new CalendarException(CalendarErrorCode.SLOT_ALREADY_HOLDING_OR_BOOKED));

        assertThatThrownBy(() -> appointmentService.holdSlotForBooking("slot-uuid-1", 10))
                .isInstanceOf(AppointmentException.class)
                .hasFieldOrPropertyWithValue("code", AppointmentErrorCode.SLOT_UNAVAILABLE.code())
                .hasMessage("Khung giờ này vừa được người khác chọn giữ chỗ. Vui lòng chọn khung giờ khác.");
    }

    @Test
    @DisplayName("REQ-APT-03: createAppointment confirms held slot, generates APT-YYYYMMDD-XXXX code and QR token, and indexes global history")
    void createAppointment_Success() {
        when(timeSlotRepository.findByIdForUpdate("slot-uuid-1")).thenReturn(Optional.of(heldSlot));
        when(appointmentRepository.existsBySlotIdAndCustomerPhoneAndStatusIn(eq("slot-uuid-1"), eq("0901234567"), any()))
                .thenReturn(false);
        when(appointmentRepository.countByAppointmentCodeStartingWith(anyString())).thenReturn(0L);
        when(appointmentRepository.existsByAppointmentCodeIgnoreCase(anyString())).thenReturn(false);
        when(appointmentRepository.save(any(TenantAppointment.class))).thenAnswer(inv -> inv.getArgument(0));

        CreateAppointmentRequest request = CreateAppointmentRequest.builder()
                .slotId("slot-uuid-1")
                .holdingToken("hold-tok-123")
                .customerName("Nguyễn Văn Khách")
                .customerPhone("0901234567")
                .customerEmail("khach@example.com")
                .notes("Khám buổi sáng")
                .build();

        AppointmentResponse response = appointmentService.createAppointment(request, "user-uuid-999");

        assertThat(response).isNotNull();
        assertThat(response.getAppointmentCode()).startsWith("APT-").endsWith("-0001");
        assertThat(response.getQrToken()).startsWith("NTQR-CLINIC-ALPHA-APT-");
        assertThat(response.getStatus()).isEqualTo(AppointmentStatus.CONFIRMED);
        assertThat(response.getCanModify()).isTrue();
        assertThat(heldSlot.getBookedCount()).isEqualTo(1);
        // Since capacityPerSlot is 2 and bookedCount is 1, slot returns to AVAILABLE for second specialist
        assertThat(heldSlot.getStatus()).isEqualTo(TimeSlotStatus.AVAILABLE);

        verify(globalUserAppointmentRepository).save(any(CentralGlobalUserAppointment.class));
        verify(timeSlotGeneratorService).syncAvailableSlotsToValkey(service.getId(), heldSlot.getWorkDate());
        verify(emailService).sendHtmlEmailAsync(eq("khach@example.com"), anyString(), anyString());
    }

    @Test
    @DisplayName("REQ-APT-03 Exception 1: createAppointment rejects when 10-minute holding session has expired")
    void createAppointment_HoldingExpired_ThrowsBadRequest() {
        heldSlot.setHoldingExpiresAt(LocalDateTime.now().minusMinutes(1));
        when(timeSlotRepository.findByIdForUpdate("slot-uuid-1")).thenReturn(Optional.of(heldSlot));

        CreateAppointmentRequest request = CreateAppointmentRequest.builder()
                .slotId("slot-uuid-1")
                .holdingToken("hold-tok-123")
                .customerName("Nguyễn Văn Khách")
                .customerPhone("0901234567")
                .build();

        assertThatThrownBy(() -> appointmentService.createAppointment(request, null))
                .isInstanceOf(AppointmentException.class)
                .hasFieldOrPropertyWithValue("code", AppointmentErrorCode.HOLDING_SESSION_EXPIRED.code())
                .hasMessage("Phiên giữ chỗ của bạn đã hết hạn (quá 10 phút). Vui lòng chọn lại khung giờ để tiếp tục.");
    }

    @Test
    @DisplayName("REQ-APT-03 Exception 2: createAppointment rejects when slot start violates min_lead_time_hours")
    void createAppointment_ViolatesMinLeadTime_ThrowsBadRequest() {
        heldSlot.setWorkDate(LocalDate.now());
        heldSlot.setSlotStart(LocalTime.now().plusMinutes(30));
        heldSlot.setSlotEnd(LocalTime.now().plusMinutes(60));
        when(timeSlotRepository.findByIdForUpdate("slot-uuid-1")).thenReturn(Optional.of(heldSlot));

        CreateAppointmentRequest request = CreateAppointmentRequest.builder()
                .slotId("slot-uuid-1")
                .holdingToken("hold-tok-123")
                .customerName("Nguyễn Văn Khách")
                .customerPhone("0901234567")
                .build();

        assertThatThrownBy(() -> appointmentService.createAppointment(request, null))
                .isInstanceOf(AppointmentException.class)
                .hasFieldOrPropertyWithValue("code", AppointmentErrorCode.LEAD_TIME_VIOLATED.code())
                .hasMessage("Khung giờ này đã quá thời hạn đặt trước tối thiểu (2 giờ) theo quy định của cơ sở. Vui lòng chọn khung giờ khác.");
    }

    @Test
    @DisplayName("REQ-APT-04: cancelAppointment transitions appointment to CANCELLED and restores slot from BOOKED to AVAILABLE")
    void cancelAppointment_Success() {
        heldSlot.setCapacityPerSlot(1);
        heldSlot.setBookedCount(1);
        heldSlot.setStatus(TimeSlotStatus.BOOKED);

        TenantAppointment appointment = TenantAppointment.builder()
                .id("apt-uuid-1")
                .appointmentCode("APT-20261125-0001")
                .service(service)
                .slot(heldSlot)
                .customerName("Nguyễn Văn Khách")
                .customerPhone("0901234567")
                .customerEmail("khach@example.com")
                .status(AppointmentStatus.CONFIRMED)
                .build();

        when(appointmentRepository.findByIdWithDetails("apt-uuid-1")).thenReturn(Optional.of(appointment));
        when(timeSlotRepository.findByIdForUpdate("slot-uuid-1")).thenReturn(Optional.of(heldSlot));
        when(appointmentRepository.save(any(TenantAppointment.class))).thenAnswer(inv -> inv.getArgument(0));

        CancelAppointmentRequest cancelReq = CancelAppointmentRequest.builder()
                .customerPhone("0901234567")
                .cancellationReason("Bận việc gia đình")
                .build();

        AppointmentResponse response = appointmentService.cancelAppointment("apt-uuid-1", cancelReq, null, false);

        assertThat(response.getStatus()).isEqualTo(AppointmentStatus.CANCELLED);
        assertThat(response.getCancellationReason()).isEqualTo("Bận việc gia đình");
        assertThat(heldSlot.getBookedCount()).isEqualTo(0);
        assertThat(heldSlot.getStatus()).isEqualTo(TimeSlotStatus.AVAILABLE);
    }

    @Test
    @DisplayName("REQ-APT-04 Exception 1: cancelAppointment blocks customer self-cancellation when remaining time < cancellation_deadline_hours")
    void cancelAppointment_PastDeadline_ThrowsBadRequest() {
        heldSlot.setWorkDate(LocalDate.now());
        heldSlot.setSlotStart(LocalTime.now().plusMinutes(45));

        TenantAppointment appointment = TenantAppointment.builder()
                .id("apt-uuid-1")
                .appointmentCode("APT-20261121-0001")
                .service(service)
                .slot(heldSlot)
                .customerName("Nguyễn Văn Khách")
                .customerPhone("0901234567")
                .status(AppointmentStatus.CONFIRMED)
                .build();

        when(appointmentRepository.findByIdWithDetails("apt-uuid-1")).thenReturn(Optional.of(appointment));

        CancelAppointmentRequest cancelReq = CancelAppointmentRequest.builder()
                .customerPhone("0901234567")
                .cancellationReason("Đổi ý")
                .build();

        assertThatThrownBy(() -> appointmentService.cancelAppointment("apt-uuid-1", cancelReq, null, false))
                .isInstanceOf(AppointmentException.class)
                .hasFieldOrPropertyWithValue("code", AppointmentErrorCode.CANCELLATION_DEADLINE_PASSED.code())
                .hasMessage("Cuộc hẹn đã vượt quá thời hạn cho phép tự hủy/đổi lịch trực tuyến (tối thiểu trước 2 giờ). Quý khách vui lòng liên hệ trực tiếp tổng đài của cơ sở để được hỗ trợ điều phối.");
    }

    @Test
    @DisplayName("REQ-APT-04: rescheduleAppointment atomically moves booking to new slot and releases old slot")
    void rescheduleAppointment_Success() {
        heldSlot.setCapacityPerSlot(1);
        heldSlot.setBookedCount(1);
        heldSlot.setStatus(TimeSlotStatus.BOOKED);

        TenantTimeSlot newSlot = TenantTimeSlot.builder()
                .id("slot-uuid-2")
                .service(service)
                .workDate(LocalDate.now().plusDays(3))
                .slotStart(LocalTime.of(14, 0))
                .slotEnd(LocalTime.of(14, 30))
                .capacityPerSlot(1)
                .bookedCount(0)
                .status(TimeSlotStatus.AVAILABLE)
                .build();

        TenantAppointment appointment = TenantAppointment.builder()
                .id("apt-uuid-1")
                .appointmentCode("APT-20261125-0001")
                .service(service)
                .slot(heldSlot)
                .customerName("Nguyễn Văn Khách")
                .customerPhone("0901234567")
                .status(AppointmentStatus.CONFIRMED)
                .build();

        when(appointmentRepository.findByIdWithDetails("apt-uuid-1")).thenReturn(Optional.of(appointment));
        when(timeSlotRepository.findByIdForUpdate("slot-uuid-1")).thenReturn(Optional.of(heldSlot));
        when(timeSlotRepository.findByIdForUpdate("slot-uuid-2")).thenReturn(Optional.of(newSlot));
        when(appointmentRepository.save(any(TenantAppointment.class))).thenAnswer(inv -> inv.getArgument(0));

        RescheduleAppointmentRequest rescheduleReq = RescheduleAppointmentRequest.builder()
                .customerPhone("0901234567")
                .newSlotId("slot-uuid-2")
                .build();

        AppointmentResponse response = appointmentService.rescheduleAppointment("apt-uuid-1", rescheduleReq, null, false);

        assertThat(response.getSlotId()).isEqualTo("slot-uuid-2");
        assertThat(heldSlot.getBookedCount()).isEqualTo(0);
        assertThat(heldSlot.getStatus()).isEqualTo(TimeSlotStatus.AVAILABLE);
        assertThat(newSlot.getBookedCount()).isEqualTo(1);
        assertThat(newSlot.getStatus()).isEqualTo(TimeSlotStatus.BOOKED);
    }

    @Test
    @DisplayName("REQ-APT-05: getCustomerAppointmentsForCurrentTenant queries central__global_user_appointments and returns history")
    void getCustomerAppointmentsForCurrentTenant_Success() {
        CentralGlobalUserAppointment index = CentralGlobalUserAppointment.builder()
                .id("gua-1")
                .userId("user-uuid-999")
                .tenantId("tenant-uuid-1")
                .tenantCode(TENANT_CODE)
                .appointmentId("apt-uuid-1")
                .appointmentCode("APT-20261125-0001")
                .build();

        TenantAppointment appointment = TenantAppointment.builder()
                .id("apt-uuid-1")
                .appointmentCode("APT-20261125-0001")
                .userId("user-uuid-999")
                .service(service)
                .slot(heldSlot)
                .customerName("Nguyễn Văn Khách")
                .customerPhone("0901234567")
                .status(AppointmentStatus.CONFIRMED)
                .build();

        when(globalUserAppointmentRepository.findByUserIdAndTenantCodeIgnoreCaseOrderByCreatedAtDesc("user-uuid-999", TENANT_CODE))
                .thenReturn(List.of(index));
        when(appointmentRepository.findByIdWithDetails("apt-uuid-1")).thenReturn(Optional.of(appointment));
        when(appointmentRepository.findByUserIdWithDetails("user-uuid-999")).thenReturn(List.of(appointment));

        List<AppointmentResponse> history = appointmentService.getCustomerAppointmentsForCurrentTenant("user-uuid-999");

        assertThat(history).hasSize(1);
        assertThat(history.get(0).getAppointmentCode()).isEqualTo("APT-20261125-0001");
        assertThat(history.get(0).getTenantName()).isEqualTo("Phòng khám Đa khoa Alpha");
    }
}
