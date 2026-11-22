package com.nextturn.service.ticket;

import com.nextturn.dto.ticket.IssueTicketRequest;
import com.nextturn.dto.ticket.TicketResponse;
import com.nextturn.entity.TenantAppointment;
import com.nextturn.entity.TenantQueueTicket;
import com.nextturn.entity.TenantService;
import com.nextturn.entity.TenantTimeSlot;
import com.nextturn.enums.AppointmentStatus;
import com.nextturn.enums.ServiceStatus;
import com.nextturn.enums.TicketCustomerType;
import com.nextturn.enums.TicketStatus;
import com.nextturn.exception.ticket.TicketErrorCode;
import com.nextturn.exception.ticket.TicketException;
import com.nextturn.infrastructure.multitenancy.TenantContextHolder;
import com.nextturn.repository.appointment.TenantAppointmentRepository;
import com.nextturn.repository.service.TenantServiceRepository;
import com.nextturn.repository.ticket.TenantQueueTicketRepository;
import com.nextturn.repository.ticket.TenantTicketAuditLogRepository;
import com.nextturn.service.tenant.TenantCacheService;
import com.nextturn.service.ticket.TenantTicketSequenceGenerator.GeneratedTicketNumber;
import com.nextturn.service.ticket.impl.TenantTicketQueryServiceImpl;
import com.nextturn.service.ticket.impl.TenantTicketServiceImpl;
import com.nextturn.service.ticket.impl.TenantTicketValidatorImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TenantTicketServiceTest {

    private static final String TENANT_CODE = "clinic-alpha";

    @Mock
    private TenantQueueTicketRepository ticketRepository;

    @Mock
    private TenantTicketAuditLogRepository auditLogRepository;

    @Mock
    private TenantAppointmentRepository appointmentRepository;

    @Mock
    private TenantServiceRepository serviceRepository;

    @Mock
    private TenantTicketSequenceGenerator sequenceGenerator;

    @Mock
    private TenantTicketSseBroadcaster sseBroadcaster;

    @Mock
    private TenantCacheService cacheService;

    private TenantTicketValidatorImpl validator;
    private TenantTicketQueryServiceImpl queryService;
    private TenantTicketServiceImpl ticketService;

    private TenantService activeService;

    @BeforeEach
    void setUp() {
        TenantContextHolder.setTenantCode(TENANT_CODE);

        validator = new TenantTicketValidatorImpl(
                serviceRepository,
                appointmentRepository,
                ticketRepository,
                null,
                null,
                null
        );
        queryService = new TenantTicketQueryServiceImpl(ticketRepository, null);
        ticketService = new TenantTicketServiceImpl(
                ticketRepository,
                auditLogRepository,
                appointmentRepository,
                sequenceGenerator,
                validator,
                queryService,
                sseBroadcaster,
                cacheService,
                null
        );

        activeService = TenantService.builder()
                .id("srv-001")
                .serviceCode("SVC-0001")
                .serviceName("Khám Tổng Quát")
                .standardDuration(15)
                .status(ServiceStatus.ACTIVE)
                .isDeleted(false)
                .build();
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
    }

    @Test
    @DisplayName("REQ-TICKET-01: Walk-in issuance acquires lock, invokes Lua sequence, persists WAITING ticket + audit log, and broadcasts SSE")
    void issueTicket_WalkIn_Success() {
        LocalDate today = LocalDate.now();
        when(serviceRepository.findByIdAndIsDeletedFalse("srv-001")).thenReturn(Optional.of(activeService));
        when(ticketRepository.existsByServiceIdAndCustomerPhoneAndWorkDateAndStatusIn(eq("srv-001"), eq("0901234567"), eq(today), any()))
                .thenReturn(false);
        when(sequenceGenerator.acquireTicketLock(TENANT_CODE, "srv-001", today)).thenReturn("lock-owner-uuid");
        when(sequenceGenerator.nextTicketNumber(TENANT_CODE, activeService, today))
                .thenReturn(new GeneratedTicketNumber(1, "A-001"));
        when(ticketRepository.save(any(TenantQueueTicket.class))).thenAnswer(inv -> inv.getArgument(0));
        when(ticketRepository.countWaitingTicketsAhead(eq("srv-001"), eq(today), any(), eq(1))).thenReturn(2L);
        when(ticketRepository.findActiveServingOrCallingByServiceAndWorkDate("srv-001", today))
                .thenReturn(List.of(TenantQueueTicket.builder().ticketCode("A-000").build()));

        IssueTicketRequest request = IssueTicketRequest.builder()
                .serviceId("srv-001")
                .customerPhone("0901234567")
                .customerName("Trần Thị Vãng Lai")
                .build();

        TicketResponse response = ticketService.issueTicket(request);

        assertThat(response).isNotNull();
        assertThat(response.getTicketCode()).isEqualTo("A-001");
        assertThat(response.getSequenceNumber()).isEqualTo(1);
        assertThat(response.getCustomerType()).isEqualTo(TicketCustomerType.WALK_IN);
        assertThat(response.getStatus()).isEqualTo(TicketStatus.WAITING);
        assertThat(response.getPeopleAheadCount()).isEqualTo(2L);
        assertThat(response.getQueuePosition()).isEqualTo(3L);
        assertThat(response.getEstimatedWaitMinutes()).isEqualTo(30L);
        assertThat(response.getApproachingTurnAlert()).isTrue();
        assertThat(response.getCurrentServingNumber()).isEqualTo("A-000");

        verify(auditLogRepository).save(any());
        verify(sequenceGenerator).releaseTicketLock(TENANT_CODE, "srv-001", today, "lock-owner-uuid");
        verify(sseBroadcaster).broadcastQueueUpdate(TENANT_CODE, "srv-001", today, "TICKET_ISSUED");
    }

    @Test
    @DisplayName("REQ-CHECK-IN-01 & REQ-TICKET-01: Pre-booked QR check-in within [-15m, +10m] issues APPOINTMENT ticket and transitions appointment to CHECKED_IN")
    void issueTicket_AppointmentCheckIn_Success() {
        LocalDate today = LocalDate.now();
        TenantTimeSlot slot = TenantTimeSlot.builder()
                .id("slot-1")
                .service(activeService)
                .workDate(today)
                .slotStart(LocalTime.now().plusMinutes(5))
                .slotEnd(LocalTime.now().plusMinutes(20))
                .build();

        TenantAppointment appointment = TenantAppointment.builder()
                .id("apt-001")
                .appointmentCode("APT-20261122-0001")
                .qrToken("NTQR-VALID-001")
                .service(activeService)
                .slot(slot)
                .customerName("Nguyễn Văn Hẹn")
                .customerPhone("0912345678")
                .status(AppointmentStatus.CONFIRMED)
                .build();

        when(appointmentRepository.findByQrToken("NTQR-VALID-001")).thenReturn(Optional.of(appointment));
        when(serviceRepository.findByIdAndIsDeletedFalse("srv-001")).thenReturn(Optional.of(activeService));
        when(sequenceGenerator.acquireTicketLock(TENANT_CODE, "srv-001", today)).thenReturn("lock-owner-2");
        when(sequenceGenerator.nextTicketNumber(TENANT_CODE, activeService, today))
                .thenReturn(new GeneratedTicketNumber(1000, "A-1000"));
        when(ticketRepository.save(any(TenantQueueTicket.class))).thenAnswer(inv -> inv.getArgument(0));
        when(ticketRepository.findByAppointmentIdWithDetails("apt-001")).thenReturn(Optional.empty());

        IssueTicketRequest request = IssueTicketRequest.builder()
                .qrToken("NTQR-VALID-001")
                .build();

        TicketResponse response = ticketService.issueTicket(request);

        assertThat(response.getTicketCode()).isEqualTo("A-1000");
        assertThat(response.getCustomerType()).isEqualTo(TicketCustomerType.APPOINTMENT);
        assertThat(response.getAppointmentCode()).isEqualTo("APT-20261122-0001");
        assertThat(appointment.getStatus()).isEqualTo(AppointmentStatus.CHECKED_IN);
        verify(appointmentRepository).save(appointment);
    }

    @Test
    @DisplayName("REQ-CHECK-IN-01 Exception 1: Rejects QR check-in when customer arrives more than 15 minutes early")
    void issueTicket_AppointmentTooEarly_ThrowsBadRequest() {
        LocalDate today = LocalDate.now();
        TenantTimeSlot slot = TenantTimeSlot.builder()
                .id("slot-1")
                .service(activeService)
                .workDate(today)
                .slotStart(LocalTime.now().plusMinutes(45))
                .slotEnd(LocalTime.now().plusMinutes(60))
                .build();

        TenantAppointment appointment = TenantAppointment.builder()
                .id("apt-001")
                .appointmentCode("APT-20261122-0001")
                .qrToken("NTQR-EARLY")
                .service(activeService)
                .slot(slot)
                .status(AppointmentStatus.CONFIRMED)
                .build();

        when(appointmentRepository.findByQrToken("NTQR-EARLY")).thenReturn(Optional.of(appointment));

        IssueTicketRequest request = IssueTicketRequest.builder().qrToken("NTQR-EARLY").build();

        assertThatThrownBy(() -> ticketService.issueTicket(request))
                .isInstanceOf(TicketException.class)
                .hasFieldOrPropertyWithValue("code", TicketErrorCode.CHECKIN_TOO_EARLY.code());
    }

    @Test
    @DisplayName("REQ-CHECK-IN-01 Exception 2: Marks appointment NO_SHOW when customer arrives more than 10 minutes late")
    void issueTicket_AppointmentTooLate_MarksNoShow() {
        LocalDate today = LocalDate.now();
        TenantTimeSlot slot = TenantTimeSlot.builder()
                .id("slot-1")
                .service(activeService)
                .workDate(today)
                .slotStart(LocalTime.now().minusMinutes(20))
                .slotEnd(LocalTime.now().minusMinutes(5))
                .build();

        TenantAppointment appointment = TenantAppointment.builder()
                .id("apt-001")
                .appointmentCode("APT-20261122-0001")
                .qrToken("NTQR-LATE")
                .service(activeService)
                .slot(slot)
                .status(AppointmentStatus.CONFIRMED)
                .build();

        when(appointmentRepository.findByQrToken("NTQR-LATE")).thenReturn(Optional.of(appointment));

        IssueTicketRequest request = IssueTicketRequest.builder().qrToken("NTQR-LATE").build();

        assertThatThrownBy(() -> ticketService.issueTicket(request))
                .isInstanceOf(TicketException.class)
                .hasFieldOrPropertyWithValue("code", TicketErrorCode.CHECKIN_TOO_LATE.code());
        assertThat(appointment.getStatus()).isEqualTo(AppointmentStatus.NO_SHOW);
        verify(appointmentRepository).save(appointment);
    }

    @Test
    @DisplayName("REQ-TICKET-04 Exception: Throws exact Vietnamese message when no active ticket exists for phone today")
    void recoverTicketsByPhoneToday_NotFound_ThrowsExactMessage() {
        when(ticketRepository.findActiveByCustomerPhoneAndWorkDate(eq("0909999888"), eq(LocalDate.now()), any()))
                .thenReturn(List.of());

        assertThatThrownBy(() -> ticketService.recoverTicketsByPhoneToday("0909999888"))
                .isInstanceOf(TicketException.class)
                .hasFieldOrPropertyWithValue("code", TicketErrorCode.NO_ACTIVE_TICKET_TODAY.code())
                .hasMessage("Không tìm thấy vé hàng đợi nào đang chờ phục vụ với số điện thoại này trong hôm nay.");
    }

    @Test
    @DisplayName("REQ-TICKET-04: Successfully recovers active tickets by customer phone today")
    void recoverTicketsByPhoneToday_Success() {
        LocalDate today = LocalDate.now();
        TenantQueueTicket waiting = TenantQueueTicket.builder()
                .id("tkt-1")
                .ticketCode("A-024")
                .sequenceNumber(24)
                .workDate(today)
                .service(activeService)
                .customerPhone("0901234567")
                .status(TicketStatus.WAITING)
                .issuedAt(LocalDateTime.now().minusMinutes(10))
                .build();

        when(ticketRepository.findActiveByCustomerPhoneAndWorkDate(eq("0901234567"), eq(today), any()))
                .thenReturn(List.of(waiting));
        when(ticketRepository.countWaitingTicketsAhead(eq("srv-001"), eq(today), any(), eq(24))).thenReturn(4L);

        List<TicketResponse> result = ticketService.recoverTicketsByPhoneToday("0901234567");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getTicketCode()).isEqualTo("A-024");
        assertThat(result.get(0).getQueuePosition()).isEqualTo(5L);
        assertThat(result.get(0).getPeopleAheadCount()).isEqualTo(4L);
    }
}
