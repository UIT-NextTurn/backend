package com.nextturn.service.queue;

import com.nextturn.dto.queue.CallNextTicketRequest;
import com.nextturn.dto.queue.QueueBoardResponse;
import com.nextturn.dto.queue.ServeTicketRequest;
import com.nextturn.dto.queue.TicketTransitionRequest;
import com.nextturn.dto.ticket.TicketResponse;
import com.nextturn.entity.TenantAppointment;
import com.nextturn.entity.TenantQueueTicket;
import com.nextturn.entity.TenantService;
import com.nextturn.entity.TenantStaff;
import com.nextturn.enums.AppointmentStatus;
import com.nextturn.enums.ServiceStatus;
import com.nextturn.enums.StaffRole;
import com.nextturn.enums.StaffStatus;
import com.nextturn.enums.TicketCustomerType;
import com.nextturn.enums.TicketStatus;
import com.nextturn.enums.UserRole;
import com.nextturn.exception.counter.CounterErrorCode;
import com.nextturn.exception.counter.CounterException;
import com.nextturn.exception.queue.QueueErrorCode;
import com.nextturn.exception.queue.QueueException;
import com.nextturn.infrastructure.multitenancy.TenantContextHolder;
import com.nextturn.infrastructure.security.principal.AuthenticatedUser;
import com.nextturn.repository.appointment.TenantAppointmentRepository;
import com.nextturn.repository.calendar.TenantShiftRepository;
import com.nextturn.repository.service.TenantServiceRepository;
import com.nextturn.repository.staff.TenantStaffRepository;
import com.nextturn.repository.ticket.TenantQueueTicketRepository;
import com.nextturn.repository.ticket.TenantTicketAuditLogRepository;
import com.nextturn.service.queue.impl.TenantQueueCallingServiceImpl;
import com.nextturn.service.queue.impl.TenantSpecialistDispatchServiceImpl;
import com.nextturn.service.queue.impl.TenantTicketStateMachineImpl;
import com.nextturn.service.ticket.TenantTicketQueryService;
import com.nextturn.service.ticket.TenantTicketSseBroadcaster;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TenantQueueCallingServiceTest {

    private static final String TENANT_CODE = "clinic-alpha";
    private static final Instant FIXED_INSTANT = LocalDateTime.of(2026, 11, 22, 10, 0, 0)
            .atZone(ZoneId.systemDefault()).toInstant();
    private static final Clock FIXED_CLOCK = Clock.fixed(FIXED_INSTANT, ZoneId.systemDefault());

    @Mock
    private TenantQueueTicketRepository ticketRepository;

    @Mock
    private TenantServiceRepository serviceRepository;

    @Mock
    private TenantStaffRepository staffRepository;

    @Mock
    private TenantAppointmentRepository appointmentRepository;

    @Mock
    private TenantTicketAuditLogRepository auditLogRepository;

    @Mock
    private TenantShiftRepository shiftRepository;

    @Mock
    private TenantTicketQueryService ticketQueryService;

    @Mock
    private TenantTicketSseBroadcaster ticketSseBroadcaster;

    @Mock
    private TenantLobbySseBroadcaster lobbySseBroadcaster;

    private TenantTicketStateMachineImpl stateMachine;
    private TenantSpecialistDispatchServiceImpl dispatchService;
    private TenantQueueCallingServiceImpl queueCallingService;

    private TenantService activeService;
    private TenantStaff counterStaff;
    private TenantStaff specialistBusy;
    private TenantStaff specialistFreeLeastLoaded;
    private AuthenticatedUser caller;

    @BeforeEach
    void setUp() {
        TenantContextHolder.setTenantCode(TENANT_CODE);
        stateMachine = new TenantTicketStateMachineImpl(auditLogRepository);
        dispatchService = new TenantSpecialistDispatchServiceImpl(shiftRepository, ticketRepository);
        queueCallingService = new TenantQueueCallingServiceImpl(
                ticketRepository,
                serviceRepository,
                staffRepository,
                appointmentRepository,
                stateMachine,
                dispatchService,
                ticketQueryService,
                ticketSseBroadcaster,
                lobbySseBroadcaster,
                null,
                FIXED_CLOCK
        );

        activeService = TenantService.builder()
                .id("srv-001")
                .serviceCode("SVC-0001")
                .serviceName("Khám Nội")
                .standardDuration(15)
                .status(ServiceStatus.ACTIVE)
                .isDeleted(false)
                .build();

        counterStaff = TenantStaff.builder()
                .id("stf-counter-1")
                .staffCode("STAFF-0002")
                .fullName("Lê Thu Ngân")
                .email("counter@clinic.vn")
                .role(StaffRole.COUNTER)
                .status(StaffStatus.ACTIVE)
                .isDeleted(false)
                .build();

        specialistBusy = TenantStaff.builder()
                .id("stf-spec-1")
                .staffCode("STAFF-0003")
                .fullName("BS. A")
                .role(StaffRole.SPECIALIST)
                .status(StaffStatus.ACTIVE)
                .isDeleted(false)
                .build();

        specialistFreeLeastLoaded = TenantStaff.builder()
                .id("stf-spec-2")
                .staffCode("STAFF-0004")
                .fullName("BS. B")
                .role(StaffRole.SPECIALIST)
                .status(StaffStatus.ACTIVE)
                .isDeleted(false)
                .build();

        caller = new AuthenticatedUser("kc-counter-1", "counter@clinic.vn", TENANT_CODE, Set.of(UserRole.COUNTER_STAFF));

        lenient().when(ticketQueryService.enrichWithQueueMetrics(any(TenantQueueTicket.class)))
                .thenAnswer(inv -> TicketResponse.fromEntity(inv.getArgument(0), null, 0L, 0L, 0L));
        lenient().when(ticketRepository.save(any(TenantQueueTicket.class)))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
    }

    @Test
    @DisplayName("REQ-QC-01: callNextTicket checks specialist availability, picks priority ticket, sets CALLING, and broadcasts SSE")
    void callNextTicket_Success() {
        LocalDate today = LocalDate.now(FIXED_CLOCK);
        LocalTime nowTime = LocalTime.now(FIXED_CLOCK);
        TenantQueueTicket waiting = TenantQueueTicket.builder()
                .id("tkt-1")
                .ticketCode("A-001")
                .sequenceNumber(1)
                .workDate(today)
                .service(activeService)
                .customerType(TicketCustomerType.APPOINTMENT)
                .status(TicketStatus.WAITING)
                .version(0)
                .issuedAt(LocalDateTime.now(FIXED_CLOCK).minusMinutes(5))
                .build();

        when(serviceRepository.findByIdAndIsDeletedFalse("srv-001")).thenReturn(Optional.of(activeService));
        when(staffRepository.findByKeycloakUserIdAndIsDeletedFalse("kc-counter-1")).thenReturn(Optional.of(counterStaff));
        when(ticketRepository.existsByCounterStaffIdAndWorkDateAndStatus("stf-counter-1", today, TicketStatus.CALLING))
                .thenReturn(false);
        when(shiftRepository.findActiveSpecialistsInShiftForService("srv-001", today, nowTime))
                .thenReturn(List.of(specialistBusy, specialistFreeLeastLoaded));
        when(ticketRepository.countServingTicketsGroupedByProvider(today))
                .thenReturn(List.<Object[]>of(new Object[]{"stf-spec-1", 1L}));
        when(ticketRepository.findNextPriorityWaitingTicketForUpdate("srv-001", today, LocalDateTime.now(FIXED_CLOCK)))
                .thenReturn(Optional.of(waiting));
        when(ticketRepository.findByIdWithDetails("tkt-1")).thenReturn(Optional.of(waiting));

        TicketResponse result = queueCallingService.callNextTicket(
                CallNextTicketRequest.builder().serviceId("srv-001").counterLabel("Quầy 01").build(),
                caller
        );

        assertThat(result.getStatus()).isEqualTo(TicketStatus.CALLING);
        assertThat(result.getCounterLabel()).isEqualTo("Quầy 01");
        assertThat(result.getCounterStaffId()).isEqualTo("stf-counter-1");
        verify(auditLogRepository).save(any());
        verify(ticketSseBroadcaster).broadcastQueueUpdate(TENANT_CODE, "srv-001", today, "TICKET_CALLED");
        verify(lobbySseBroadcaster).broadcastLobbyEvent(eq(TENANT_CODE), eq("TICKET_CALLED"), any());
    }

    @Test
    @DisplayName("REQ-QC-01 Exceptions: blocks when counter already calling or all specialists busy")
    void callNextTicket_CounterBusyAndSpecialistsFull_ThrowsConflict() {
        LocalDate today = LocalDate.now(FIXED_CLOCK);
        LocalTime nowTime = LocalTime.now(FIXED_CLOCK);
        when(serviceRepository.findByIdAndIsDeletedFalse("srv-001")).thenReturn(Optional.of(activeService));
        when(staffRepository.findByKeycloakUserIdAndIsDeletedFalse("kc-counter-1")).thenReturn(Optional.of(counterStaff));
        when(ticketRepository.existsByCounterStaffIdAndWorkDateAndStatus("stf-counter-1", today, TicketStatus.CALLING))
                .thenReturn(true);

        assertThatThrownBy(() -> queueCallingService.callNextTicket(
                CallNextTicketRequest.builder().serviceId("srv-001").build(), caller))
                .isInstanceOf(CounterException.class)
                .hasFieldOrPropertyWithValue("code", CounterErrorCode.COUNTER_HAS_ACTIVE_CALL.code());

        when(ticketRepository.existsByCounterStaffIdAndWorkDateAndStatus("stf-counter-1", today, TicketStatus.CALLING))
                .thenReturn(false);
        when(shiftRepository.findActiveSpecialistsInShiftForService("srv-001", today, nowTime))
                .thenReturn(List.of(specialistBusy));
        when(ticketRepository.countServingTicketsGroupedByProvider(today))
                .thenReturn(List.<Object[]>of(new Object[]{"stf-spec-1", 1L}));

        assertThatThrownBy(() -> queueCallingService.callNextTicket(
                CallNextTicketRequest.builder().serviceId("srv-001").build(), caller))
                .isInstanceOf(QueueException.class)
                .hasFieldOrPropertyWithValue("code", QueueErrorCode.NO_AVAILABLE_SPECIALIST.code());
    }

    @Test
    @DisplayName("REQ-QC-02: serveTicket transitions CALLING -> SERVING and auto-selects least-loaded specialist")
    void serveTicket_LoadBalancesToLeastLoadedSpecialist() {
        LocalDate today = LocalDate.now(FIXED_CLOCK);
        LocalTime nowTime = LocalTime.now(FIXED_CLOCK);
        TenantQueueTicket calling = TenantQueueTicket.builder()
                .id("tkt-1")
                .ticketCode("A-002")
                .workDate(today)
                .service(activeService)
                .status(TicketStatus.CALLING)
                .version(1)
                .build();

        when(ticketRepository.findByIdWithDetails("tkt-1")).thenReturn(Optional.of(calling));
        when(shiftRepository.findActiveSpecialistsInShiftForService("srv-001", today, nowTime))
                .thenReturn(List.of(specialistBusy, specialistFreeLeastLoaded));
        when(ticketRepository.countServingTicketsGroupedByProvider(today)).thenReturn(List.of());
        when(ticketRepository.countHandledTicketsGroupedByProvider(today))
                .thenReturn(List.of(new Object[]{"stf-spec-1", 5L}, new Object[]{"stf-spec-2", 1L}));

        TicketResponse result = queueCallingService.serveTicket(
                "tkt-1",
                ServeTicketRequest.builder().expectedVersion(1).build(),
                caller
        );

        assertThat(result.getStatus()).isEqualTo(TicketStatus.SERVING);
        assertThat(result.getProviderId()).isEqualTo("stf-spec-2");
        verify(lobbySseBroadcaster).broadcastLobbyEvent(eq(TENANT_CODE), eq("TICKET_SERVING"), any());
    }

    @Test
    @DisplayName("REQ-QC-03: completeTicket computes actualDuration and autoCompleteOverdueTickets closes >= 1.5x elapsed")
    void completeAndAutoCompleteSafetyNet() {
        LocalDateTime now = LocalDateTime.now(FIXED_CLOCK);
        TenantQueueTicket manualServing = TenantQueueTicket.builder()
                .id("tkt-man")
                .ticketCode("A-003")
                .workDate(now.toLocalDate())
                .service(activeService)
                .status(TicketStatus.SERVING)
                .version(2)
                .servedAt(now.minusSeconds(320))
                .build();

        when(ticketRepository.findByIdWithDetails("tkt-man")).thenReturn(Optional.of(manualServing));
        TicketResponse manualResp = queueCallingService.completeTicket(
                "tkt-man", TicketTransitionRequest.builder().expectedVersion(2).build(), caller);
        assertThat(manualResp.getStatus()).isEqualTo(TicketStatus.COMPLETED);
        assertThat(manualResp.getActualDuration()).isEqualTo(320);
        assertThat(manualResp.getAutoCompleted()).isFalse();

        // Standard duration = 15 min -> 1.5x = 22.5 min (1350s). 23m overdue vs 20m not overdue
        TenantQueueTicket overdue = TenantQueueTicket.builder()
                .id("tkt-over")
                .ticketCode("A-004")
                .workDate(now.toLocalDate())
                .service(activeService)
                .status(TicketStatus.SERVING)
                .version(1)
                .servedAt(now.minusMinutes(23))
                .build();
        TenantQueueTicket stillWithin = TenantQueueTicket.builder()
                .id("tkt-ok")
                .ticketCode("A-005")
                .workDate(now.toLocalDate())
                .service(activeService)
                .status(TicketStatus.SERVING)
                .version(1)
                .servedAt(now.minusMinutes(20))
                .build();

        when(ticketRepository.findAllServingWithDetails()).thenReturn(List.of(overdue, stillWithin));
        when(ticketRepository.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));

        int autoCount = queueCallingService.autoCompleteOverdueTickets();
        assertThat(autoCount).isEqualTo(1);
        assertThat(overdue.getStatus()).isEqualTo(TicketStatus.COMPLETED);
        assertThat(overdue.getAutoCompleted()).isTrue();
        assertThat(stillWithin.getStatus()).isEqualTo(TicketStatus.SERVING);
    }

    @Test
    @DisplayName("REQ-QC-04: recall preserves calledAt and markNoShow requires >= 3 recalls or >= 3 min & syncs appointment")
    void recallAndMarkNoShow_EnforcesGuardAndSyncsAppointment() {
        LocalDateTime now = LocalDateTime.now(FIXED_CLOCK);
        LocalDateTime initialCallTime = now.minusMinutes(1);
        TenantAppointment linkedApt = TenantAppointment.builder()
                .id("apt-1")
                .status(AppointmentStatus.CHECKED_IN)
                .build();

        TenantQueueTicket calling = TenantQueueTicket.builder()
                .id("tkt-ns")
                .ticketCode("A-009")
                .workDate(now.toLocalDate())
                .service(activeService)
                .appointment(linkedApt)
                .status(TicketStatus.CALLING)
                .recallCount(2)
                .calledAt(initialCallTime)
                .version(1)
                .build();

        when(ticketRepository.findByIdWithDetails("tkt-ns")).thenReturn(Optional.of(calling));

        // 2 recalls & 1 minute -> rejected
        assertThatThrownBy(() -> queueCallingService.markNoShow("tkt-ns", null, caller))
                .isInstanceOf(QueueException.class)
                .hasFieldOrPropertyWithValue("code", QueueErrorCode.NO_SHOW_PRECONDITION_NOT_MET.code());

        // 3rd recall preserves initial calledAt and permits NO_SHOW
        queueCallingService.recallTicket("tkt-ns", null, caller);
        assertThat(calling.getRecallCount()).isEqualTo(3);
        assertThat(calling.getCalledAt()).isEqualTo(initialCallTime);

        TicketResponse noShow = queueCallingService.markNoShow("tkt-ns", null, caller);
        assertThat(noShow.getStatus()).isEqualTo(TicketStatus.NO_SHOW);
        assertThat(linkedApt.getStatus()).isEqualTo(AppointmentStatus.NO_SHOW);
        verify(appointmentRepository).save(linkedApt);
    }

    @Test
    @DisplayName("getQueueBoard sorts WAITING tickets by live priority score (aged walk-in > fresh appointment)")
    void getQueueBoard_SortsWaitingByPriorityScore() {
        LocalDateTime now = LocalDateTime.now(FIXED_CLOCK);
        TenantQueueTicket freshApt = TenantQueueTicket.builder()
                .id("t1").ticketCode("A-010").sequenceNumber(10)
                .customerType(TicketCustomerType.APPOINTMENT).status(TicketStatus.WAITING)
                .service(activeService).issuedAt(now)
                .build();
        TenantQueueTicket agedWalkIn = TenantQueueTicket.builder()
                .id("t2").ticketCode("A-002").sequenceNumber(2)
                .customerType(TicketCustomerType.WALK_IN).status(TicketStatus.WAITING)
                .service(activeService).issuedAt(now.minusMinutes(25))
                .build();

        when(ticketRepository.findByWorkDateAndOptionalServiceWithDetails(now.toLocalDate(), "srv-001"))
                .thenReturn(List.of(freshApt, agedWalkIn));
        when(shiftRepository.findActiveSpecialistsInShiftForService(eq("srv-001"), eq(now.toLocalDate()), any()))
                .thenReturn(List.of(specialistFreeLeastLoaded));

        QueueBoardResponse board = queueCallingService.getQueueBoard("srv-001");

        assertThat(board.getWaitingTickets()).extracting("ticketCode")
                .containsExactly("A-002", "A-010");
        assertThat(board.getWaitingTickets().get(0).getPriorityScore()).isEqualTo(125L);
        assertThat(board.getWaitingTickets().get(1).getPriorityScore()).isEqualTo(100L);
    }
}
