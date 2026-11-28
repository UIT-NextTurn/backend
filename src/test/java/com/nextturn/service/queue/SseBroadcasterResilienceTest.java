package com.nextturn.service.queue;

import com.nextturn.config.AsyncConfig;
import com.nextturn.dto.dashboard.TenantDashboardOverviewResponse;
import com.nextturn.dto.queue.CallNextTicketRequest;
import com.nextturn.dto.ticket.TicketResponse;
import com.nextturn.entity.TenantQueueTicket;
import com.nextturn.entity.TenantService;
import com.nextturn.entity.TenantStaff;
import com.nextturn.enums.ServiceStatus;
import com.nextturn.enums.StaffRole;
import com.nextturn.enums.StaffStatus;
import com.nextturn.enums.TicketCustomerType;
import com.nextturn.enums.TicketStatus;
import com.nextturn.enums.UserRole;
import com.nextturn.exception.AppException;
import com.nextturn.exception.system.SystemErrorCode;
import com.nextturn.infrastructure.multitenancy.TenantContextHolder;
import com.nextturn.infrastructure.security.principal.AuthenticatedUser;
import com.nextturn.infrastructure.sse.TenantSseConnectionLimiter;
import com.nextturn.repository.appointment.TenantAppointmentRepository;
import com.nextturn.repository.service.TenantServiceRepository;
import com.nextturn.repository.staff.TenantStaffRepository;
import com.nextturn.repository.ticket.TenantQueueTicketRepository;
import com.nextturn.service.dashboard.TenantDashboardService;
import com.nextturn.service.dashboard.impl.DashboardSseBroadcasterImpl;
import com.nextturn.scheduler.SseHeartbeatScheduler;
import com.nextturn.service.queue.impl.TenantLobbySseBroadcasterImpl;
import com.nextturn.service.queue.impl.TenantQueueCallingServiceImpl;
import com.nextturn.service.ticket.TenantTicketQueryService;
import com.nextturn.service.ticket.TenantTicketSseBroadcaster;
import com.nextturn.service.ticket.impl.TenantTicketQueryServiceImpl;
import com.nextturn.service.ticket.impl.TenantTicketSseBroadcasterImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Verifies P0 SSE backend defect fixes:
 * - #1: Per-emitter failure isolation (IOException / IllegalStateException) across all 3 broadcasters
 * - #2: Multiple concurrent viewers subscribed to the same ticket
 * - #3: Bounded O(1) query cost during ticket SSE fan-out and async post-commit latency isolation
 */
@ExtendWith(MockitoExtension.class)
class SseBroadcasterResilienceTest {

    private static final String TENANT_CODE = "clinic_a";
    private static final String SERVICE_ID = "srv-001";
    private static final LocalDate WORK_DATE = LocalDate.of(2026, 11, 28);
    private static final Clock FIXED_CLOCK = Clock.fixed(
            Instant.parse("2026-11-28T03:00:00Z"),
            ZoneId.of("Asia/Ho_Chi_Minh")
    );

    @Mock
    private TenantQueueTicketRepository ticketRepository;

    @Mock
    private TenantServiceRepository serviceRepository;

    @Mock
    private TenantStaffRepository staffRepository;

    @Mock
    private TenantAppointmentRepository appointmentRepository;

    @Mock
    private TenantTicketStateMachine stateMachine;

    @Mock
    private TenantSpecialistDispatchService specialistDispatchService;

    @Mock
    private TenantTicketQueryService mockTicketQueryService;

    @Mock
    private TenantDashboardService tenantDashboardService;

    @Mock
    private TenantTicketSseBroadcaster mockTicketSseBroadcaster;

    @Mock
    private TenantLobbySseBroadcaster mockLobbySseBroadcaster;

    private TenantService service;

    @BeforeEach
    void setUp() {
        TenantContextHolder.setTenantCode(TENANT_CODE);
        service = TenantService.builder()
                .id(SERVICE_ID)
                .serviceCode("A")
                .serviceName("General Consultation")
                .standardDuration(15)
                .status(ServiceStatus.ACTIVE)
                .isDeleted(false)
                .build();
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
    }

    // -------------------------------------------------------------------------
    // #1: Isolated subscriber failures across all three broadcasters
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("P0 #1: Stale lobby emitter throwing IllegalStateException does not fail broadcast or starve healthy viewers")
    void lobbyBroadcaster_IsolatesStaleEmitterAndDeliversToHealthyViewers() {
        when(ticketRepository.findActiveLobbyTicketsByWorkDate(any())).thenReturn(List.of());

        RecordingSseEmitter staleEmitter = new RecordingSseEmitter();
        RecordingSseEmitter healthyEmitter = new RecordingSseEmitter();
        Queue<SseEmitter> emitters = new ArrayDeque<>(List.of(staleEmitter, healthyEmitter));

        TenantLobbySseBroadcasterImpl broadcaster = new TenantLobbySseBroadcasterImpl(ticketRepository, null) {
            @Override
            protected SseEmitter createEmitter() {
                return emitters.remove();
            }
        };

        broadcaster.subscribe(TENANT_CODE);
        broadcaster.subscribe(TENANT_CODE);
        assertThat(staleEmitter.getSendCount()).isEqualTo(1);
        assertThat(healthyEmitter.getSendCount()).isEqualTo(1);

        // Simulate emitter already completed mid-flight (races broadcast before onCompletion removes it)
        staleEmitter.failNextSendsWith(new IllegalStateException("ResponseBodyEmitter has already completed"));

        TenantQueueTicket called = TenantQueueTicket.builder()
                .id("t-1")
                .ticketCode("A-001")
                .sequenceNumber(1)
                .workDate(WORK_DATE)
                .service(service)
                .status(TicketStatus.CALLING)
                .counterLabel("Counter 1")
                .issuedAt(LocalDateTime.now(FIXED_CLOCK))
                .build();

        assertThatCode(() -> broadcaster.broadcastLobbyEvent(TENANT_CODE, "TICKET_CALLED", called))
                .doesNotThrowAnyException();

        // Healthy subscriber received the update, stale emitter was evicted and not called again
        assertThat(healthyEmitter.getSendCount()).isEqualTo(2);
        assertThat(staleEmitter.isCompleted()).isTrue();

        broadcaster.broadcastLobbyEvent(TENANT_CODE, "TICKET_SERVING", called);
        assertThat(healthyEmitter.getSendCount()).isEqualTo(3);
        assertThat(staleEmitter.getFailedSendAttempts()).isEqualTo(1);
    }

    @Test
    @DisplayName("P0 #1: Dashboard broadcaster isolates failing emitter even when complete() also throws")
    void dashboardBroadcaster_IsolatesFailingEmitter() {
        when(tenantDashboardService.getOverview()).thenReturn(TenantDashboardOverviewResponse.builder().build());

        RecordingSseEmitter brokenEmitter = new RecordingSseEmitter();
        RecordingSseEmitter healthyEmitter = new RecordingSseEmitter();
        Queue<SseEmitter> emitters = new ArrayDeque<>(List.of(brokenEmitter, healthyEmitter));

        DashboardSseBroadcasterImpl broadcaster = new DashboardSseBroadcasterImpl(tenantDashboardService, null) {
            @Override
            protected SseEmitter createEmitter() {
                return emitters.remove();
            }
        };

        broadcaster.subscribeTenantOverview(TENANT_CODE);
        broadcaster.subscribeTenantOverview(TENANT_CODE);

        brokenEmitter.failNextSendsWith(new IllegalStateException("Emitter already closed"));
        brokenEmitter.throwOnComplete(true);

        assertThatCode(() -> broadcaster.broadcastTenantOverviewUpdate(TENANT_CODE, "TICKET_COMPLETED"))
                .doesNotThrowAnyException();

        assertThat(healthyEmitter.getSendCount()).isEqualTo(2);
    }

    // -------------------------------------------------------------------------
    // #2: Multiple concurrent viewers watching the same ticket
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("P0 #2: Two devices subscribing to the same ticket both receive every broadcast, and one failing leaves the other intact")
    void ticketBroadcaster_SupportsMultipleConcurrentViewersPerTicket() {
        TenantTicketQueryServiceImpl queryService = new TenantTicketQueryServiceImpl(ticketRepository, null);

        TenantQueueTicket ticket = TenantQueueTicket.builder()
                .id("ticket-shared")
                .ticketCode("A-010")
                .sequenceNumber(10)
                .workDate(WORK_DATE)
                .service(service)
                .status(TicketStatus.WAITING)
                .issuedAt(LocalDateTime.of(2026, 11, 28, 9, 10))
                .build();

        when(ticketRepository.findByIdWithDetails("ticket-shared")).thenReturn(Optional.of(ticket));
        when(ticketRepository.findActiveServingOrCallingByServiceAndWorkDate(SERVICE_ID, WORK_DATE))
                .thenReturn(List.of(TenantQueueTicket.builder().ticketCode("A-008").build()));
        when(ticketRepository.countWaitingTicketsAhead(eq(SERVICE_ID), eq(WORK_DATE), any(), eq(10)))
                .thenReturn(1L);
        when(ticketRepository.findByIdInWithDetails(anyCollection())).thenReturn(List.of(ticket));
        when(ticketRepository.findWaitingOrderProjectionsByServiceAndWorkDate(SERVICE_ID, WORK_DATE))
                .thenReturn(List.of(
                        new Object[]{"ticket-ahead", LocalDateTime.of(2026, 11, 28, 9, 9), 9},
                        new Object[]{"ticket-shared", LocalDateTime.of(2026, 11, 28, 9, 10), 10}
                ));

        RecordingSseEmitter phoneEmitter = new RecordingSseEmitter();
        RecordingSseEmitter laptopEmitter = new RecordingSseEmitter();
        Queue<SseEmitter> emitters = new ArrayDeque<>(List.of(phoneEmitter, laptopEmitter));

        TenantTicketSseBroadcasterImpl broadcaster = new TenantTicketSseBroadcasterImpl(queryService) {
            @Override
            protected SseEmitter createEmitter() {
                return emitters.remove();
            }
        };

        broadcaster.subscribe(TENANT_CODE, "ticket-shared");
        broadcaster.subscribe(TENANT_CODE, "ticket-shared");

        // Broadcast 1: both phone and laptop receive the update
        broadcaster.broadcastQueueUpdate(TENANT_CODE, SERVICE_ID, WORK_DATE, "TICKET_CALLED");
        assertThat(phoneEmitter.getSendCount()).isEqualTo(2);
        assertThat(laptopEmitter.getSendCount()).isEqualTo(2);

        // Phone disconnects (IOException); laptop must still receive broadcast 2 and phone is evicted
        phoneEmitter.failNextSendsWith(new IOException("Broken pipe"));
        broadcaster.broadcastQueueUpdate(TENANT_CODE, SERVICE_ID, WORK_DATE, "TICKET_SERVING");
        assertThat(laptopEmitter.getSendCount()).isEqualTo(3);
        assertThat(phoneEmitter.isCompleted()).isTrue();

        broadcaster.broadcastQueueUpdate(TENANT_CODE, SERVICE_ID, WORK_DATE, "TICKET_COMPLETED");
        assertThat(laptopEmitter.getSendCount()).isEqualTo(4);
        assertThat(phoneEmitter.getFailedSendAttempts()).isEqualTo(1);
    }

    // -------------------------------------------------------------------------
    // #3A: Bounded O(1) query cost with 50 tracked tickets in one service
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("P0 #3A: Broadcasting to 50 tracked tickets performs bounded O(1) queries instead of N+1 per-ticket reads")
    void ticketBroadcaster_BoundedQueryCountFor50Subscribers() {
        TenantTicketQueryServiceImpl queryService = new TenantTicketQueryServiceImpl(ticketRepository, null);

        List<TenantQueueTicket> tickets = new ArrayList<>();
        List<Object[]> waitingProjections = new ArrayList<>();
        LocalDateTime baseIssuedAt = LocalDateTime.of(2026, 11, 28, 8, 0);

        for (int i = 1; i <= 50; i++) {
            String id = "ticket-" + i;
            LocalDateTime issuedAt = baseIssuedAt.plusMinutes(i);
            TenantQueueTicket t = TenantQueueTicket.builder()
                    .id(id)
                    .ticketCode(String.format("A-%03d", i))
                    .sequenceNumber(i)
                    .workDate(WORK_DATE)
                    .service(service)
                    .status(TicketStatus.WAITING)
                    .issuedAt(issuedAt)
                    .build();
            tickets.add(t);
            waitingProjections.add(new Object[]{id, issuedAt, i});
            when(ticketRepository.findByIdWithDetails(id)).thenReturn(Optional.of(t));
        }

        when(ticketRepository.findActiveServingOrCallingByServiceAndWorkDate(SERVICE_ID, WORK_DATE))
                .thenReturn(List.of(TenantQueueTicket.builder().ticketCode("A-000").build()));
        when(ticketRepository.countWaitingTicketsAhead(eq(SERVICE_ID), eq(WORK_DATE), any(), any()))
                .thenReturn(5L);
        when(ticketRepository.findByIdInWithDetails(anyCollection())).thenReturn(tickets);
        when(ticketRepository.findWaitingOrderProjectionsByServiceAndWorkDate(SERVICE_ID, WORK_DATE))
                .thenReturn(waitingProjections);

        List<RecordingSseEmitter> createdEmitters = new ArrayList<>();
        TenantTicketSseBroadcasterImpl broadcaster = new TenantTicketSseBroadcasterImpl(queryService) {
            @Override
            protected SseEmitter createEmitter() {
                RecordingSseEmitter e = new RecordingSseEmitter();
                createdEmitters.add(e);
                return e;
            }
        };

        for (TenantQueueTicket t : tickets) {
            broadcaster.subscribe(TENANT_CODE, t.getId());
        }

        // Clear interaction counts from initial subscribe phase so we measure strictly ONE broadcast
        org.mockito.Mockito.clearInvocations(ticketRepository);

        broadcaster.broadcastQueueUpdate(TENANT_CODE, SERVICE_ID, WORK_DATE, "TICKET_CALLED");

        // All 50 subscribers received the broadcast event
        assertThat(createdEmitters).hasSize(50);
        assertThat(createdEmitters).allSatisfy(e -> assertThat(e.getSendCount()).isEqualTo(2));

        // Strictly bounded O(1) DB queries (3 total for 50 tickets, NOT 150!)
        verify(ticketRepository, times(1)).findByIdInWithDetails(anyCollection());
        verify(ticketRepository, times(1)).findActiveServingOrCallingByServiceAndWorkDate(SERVICE_ID, WORK_DATE);
        verify(ticketRepository, times(1)).findWaitingOrderProjectionsByServiceAndWorkDate(SERVICE_ID, WORK_DATE);
        verify(ticketRepository, never()).findByIdWithDetails(any());
        verify(ticketRepository, never()).countWaitingTicketsAhead(any(), any(), any(), any());

        // Verify batch position calculation matches exact queue order
        var batchResult = queryService.getTicketDetailsBatch(
                SERVICE_ID, WORK_DATE, List.of("ticket-1", "ticket-3", "ticket-50")
        );
        assertThat(batchResult.get("ticket-1").getPeopleAheadCount()).isEqualTo(0L);
        assertThat(batchResult.get("ticket-1").getQueuePosition()).isEqualTo(1L);
        assertThat(batchResult.get("ticket-1").getApproachingTurnAlert()).isTrue();
        assertThat(batchResult.get("ticket-3").getPeopleAheadCount()).isEqualTo(2L);
        assertThat(batchResult.get("ticket-3").getApproachingTurnAlert()).isTrue();
        assertThat(batchResult.get("ticket-50").getPeopleAheadCount()).isEqualTo(49L);
        assertThat(batchResult.get("ticket-50").getQueuePosition()).isEqualTo(50L);
        assertThat(batchResult.get("ticket-50").getEstimatedWaitMinutes()).isEqualTo(49L * 15L);
        assertThat(batchResult.get("ticket-50").getApproachingTurnAlert()).isFalse();
        assertThat(batchResult.get("ticket-50").getCurrentServingNumber()).isEqualTo("A-000");
    }

    // -------------------------------------------------------------------------
    // #3B: Latency isolation on bounded async executor & error containment
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("P0 #3B: Stalled subscriber socket runs on bounded async executor and does not block callNextTicket latency")
    void queueCallingService_StalledBroadcastDoesNotBlockCallNextResponse() throws Exception {
        AsyncConfig asyncConfig = new AsyncConfig();
        Executor executor = asyncConfig.queueEventExecutor();

        try {
            TenantQueueCallingServiceImpl queueCallingService = new TenantQueueCallingServiceImpl(
                    ticketRepository,
                    serviceRepository,
                    staffRepository,
                    appointmentRepository,
                    stateMachine,
                    specialistDispatchService,
                    mockTicketQueryService,
                    mockTicketSseBroadcaster,
                    mockLobbySseBroadcaster,
                    null,
                    FIXED_CLOCK
            );
            queueCallingService.setPostCommitExecutor(executor);

            TenantStaff staff = TenantStaff.builder()
                    .id("stf-1")
                    .staffCode("STAFF-001")
                    .fullName("Counter Staff")
                    .role(StaffRole.COUNTER)
                    .status(StaffStatus.ACTIVE)
                    .isDeleted(false)
                    .build();

            TenantQueueTicket waitingTicket = TenantQueueTicket.builder()
                    .id("t-next")
                    .ticketCode("A-001")
                    .sequenceNumber(1)
                    .workDate(WORK_DATE)
                    .service(service)
                    .customerType(TicketCustomerType.WALK_IN)
                    .status(TicketStatus.WAITING)
                    .issuedAt(LocalDateTime.now(FIXED_CLOCK).minusMinutes(5))
                    .build();

            when(serviceRepository.findByIdAndIsDeletedFalse(SERVICE_ID)).thenReturn(Optional.of(service));
            when(staffRepository.findByIdAndIsDeletedFalse("stf-1")).thenReturn(Optional.of(staff));
            when(ticketRepository.existsByCounterStaffIdAndWorkDateAndStatus("stf-1", WORK_DATE, TicketStatus.CALLING))
                    .thenReturn(false);
            when(ticketRepository.findNextPriorityWaitingTicketForUpdate(eq(SERVICE_ID), eq(WORK_DATE), any()))
                    .thenReturn(Optional.of(waitingTicket));
            when(ticketRepository.save(any(TenantQueueTicket.class))).thenAnswer(inv -> inv.getArgument(0));
            when(ticketRepository.findByIdWithDetails("t-next")).thenReturn(Optional.of(waitingTicket));
            when(mockTicketQueryService.enrichWithQueueMetrics(any())).thenReturn(
                    TicketResponse.builder().id("t-next").ticketCode("A-001").serviceCode("A").build()
            );

            CountDownLatch broadcastStarted = new CountDownLatch(1);
            CountDownLatch releaseStalledSocket = new CountDownLatch(1);
            CountDownLatch broadcastFinished = new CountDownLatch(1);
            AtomicReference<String> workerThreadName = new AtomicReference<>();
            AtomicReference<String> workerTenantCode = new AtomicReference<>();

            // Simulate a stalled TCP socket inside lobby broadcast
            doAnswer(inv -> {
                workerThreadName.set(Thread.currentThread().getName());
                workerTenantCode.set(TenantContextHolder.getTenantCode());
                broadcastStarted.countDown();
                releaseStalledSocket.await(5, TimeUnit.SECONDS);
                broadcastFinished.countDown();
                return null;
            }).when(mockLobbySseBroadcaster).broadcastLobbyEvent(eq(TENANT_CODE), eq("TICKET_CALLED"), any());

            // Also simulate ticket broadcaster throwing an unexpected runtime exception — lobby must still run!
            doThrow(new IllegalStateException("Simulated broadcaster failure"))
                    .when(mockTicketSseBroadcaster)
                    .broadcastQueueUpdate(TENANT_CODE, SERVICE_ID, WORK_DATE, "TICKET_CALLED");

            CallNextTicketRequest req = CallNextTicketRequest.builder()
                    .serviceId(SERVICE_ID)
                    .counterStaffId("stf-1")
                    .counterLabel("Desk 1")
                    .build();
            AuthenticatedUser caller = new AuthenticatedUser("kc-1", "staff@clinic.vn", TENANT_CODE, Set.of(UserRole.COUNTER_STAFF));

            long startNanos = System.nanoTime();
            TicketResponse response = queueCallingService.callNextTicket(req, caller);
            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);

            // HTTP request returned immediately while the stalled subscriber is still blocked
            assertThat(response.getTicketCode()).isEqualTo("A-001");
            assertThat(elapsedMillis).isLessThan(1000L);
            assertThat(broadcastStarted.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(broadcastFinished.getCount()).isEqualTo(1L); // Still stalled!
            assertThat(workerThreadName.get()).startsWith("queue-fanout-");
            assertThat(workerTenantCode.get()).isEqualTo(TENANT_CODE);

            // Release the stalled subscriber and verify clean completion
            releaseStalledSocket.countDown();
            assertThat(broadcastFinished.await(2, TimeUnit.SECONDS)).isTrue();
        } finally {
            if (executor instanceof ThreadPoolTaskExecutor tpe) {
                tpe.shutdown();
            }
        }
    }

    // -------------------------------------------------------------------------
    // #4 & #6: Heartbeat keepalives, dead-peer eviction, long-lived timeout (0L), retry: hint
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("P0 #4 & P1 #6: All broadcasters create long-lived emitters (timeout=0L), emit retry: hint, send heartbeats, and evict dead peers")
    void allBroadcasters_LongLivedTimeoutRetryHintAndHeartbeatEviction() {
        when(ticketRepository.findActiveLobbyTicketsByWorkDate(any())).thenReturn(List.of());
        when(tenantDashboardService.getOverview()).thenReturn(TenantDashboardOverviewResponse.builder().build());

        // Verify real SseEmitter instances created by all 3 broadcasters have no 30-min cutoff (timeout == 0L)
        TenantLobbySseBroadcasterImpl realLobby = new TenantLobbySseBroadcasterImpl(ticketRepository, null);
        assertThat(realLobby.subscribe(TENANT_CODE).getTimeout()).isEqualTo(0L);

        DashboardSseBroadcasterImpl realDashboard = new DashboardSseBroadcasterImpl(tenantDashboardService, null);
        assertThat(realDashboard.subscribeTenantOverview(TENANT_CODE).getTimeout()).isEqualTo(0L);
        assertThat(realDashboard.subscribePlatformOverview().getTimeout()).isEqualTo(0L);

        TenantTicketQueryServiceImpl realQuery = new TenantTicketQueryServiceImpl(ticketRepository, null);
        TenantQueueTicket sampleTicket = TenantQueueTicket.builder()
                .id("t-hb")
                .ticketCode("A-099")
                .sequenceNumber(99)
                .workDate(WORK_DATE)
                .service(service)
                .status(TicketStatus.WAITING)
                .issuedAt(LocalDateTime.of(2026, 11, 28, 9, 0))
                .build();
        when(ticketRepository.findByIdWithDetails("t-hb")).thenReturn(Optional.of(sampleTicket));
        when(ticketRepository.findActiveServingOrCallingByServiceAndWorkDate(SERVICE_ID, WORK_DATE)).thenReturn(List.of());
        when(ticketRepository.countWaitingTicketsAhead(eq(SERVICE_ID), eq(WORK_DATE), any(), eq(99))).thenReturn(0L);

        TenantTicketSseBroadcasterImpl realTicket = new TenantTicketSseBroadcasterImpl(realQuery);
        assertThat(realTicket.subscribe(TENANT_CODE, "t-hb").getTimeout()).isEqualTo(0L);

        // Verify initial retry: hint and heartbeat comment + dead-peer eviction across all 3 broadcasters
        RecordingSseEmitter healthyLobby = new RecordingSseEmitter();
        RecordingSseEmitter deadLobby = new RecordingSseEmitter();
        Queue<SseEmitter> lobbyQueue = new ArrayDeque<>(List.of(healthyLobby, deadLobby));
        TenantLobbySseBroadcasterImpl lobbyBroadcaster = new TenantLobbySseBroadcasterImpl(ticketRepository, null) {
            @Override
            protected SseEmitter createEmitter() {
                return lobbyQueue.remove();
            }
        };
        lobbyBroadcaster.subscribe(TENANT_CODE);
        lobbyBroadcaster.subscribe(TENANT_CODE);
        assertThat(healthyLobby.getRawFrames().get(0)).contains("retry:3000");

        RecordingSseEmitter healthyTicket = new RecordingSseEmitter();
        RecordingSseEmitter deadTicket = new RecordingSseEmitter();
        Queue<SseEmitter> ticketQueue = new ArrayDeque<>(List.of(healthyTicket, deadTicket));
        TenantTicketSseBroadcasterImpl ticketBroadcaster = new TenantTicketSseBroadcasterImpl(realQuery) {
            @Override
            protected SseEmitter createEmitter() {
                return ticketQueue.remove();
            }
        };
        ticketBroadcaster.subscribe(TENANT_CODE, "t-hb");
        ticketBroadcaster.subscribe(TENANT_CODE, "t-hb");
        assertThat(healthyTicket.getRawFrames().get(0)).contains("retry:3000");

        RecordingSseEmitter healthyDashboard = new RecordingSseEmitter();
        RecordingSseEmitter deadDashboard = new RecordingSseEmitter();
        Queue<SseEmitter> dashboardQueue = new ArrayDeque<>(List.of(healthyDashboard, deadDashboard));
        DashboardSseBroadcasterImpl dashboardBroadcaster = new DashboardSseBroadcasterImpl(tenantDashboardService, null) {
            @Override
            protected SseEmitter createEmitter() {
                return dashboardQueue.remove();
            }
        };
        dashboardBroadcaster.subscribeTenantOverview(TENANT_CODE);
        dashboardBroadcaster.subscribeTenantOverview(TENANT_CODE);
        assertThat(healthyDashboard.getRawFrames().get(0)).contains("retry:3000");

        // Kill one peer on each broadcaster
        deadLobby.failNextSendsWith(new IOException("Connection reset by peer"));
        deadTicket.failNextSendsWith(new IOException("Broken pipe"));
        deadDashboard.failNextSendsWith(new IllegalStateException("Already completed"));

        SseHeartbeatScheduler scheduler = new SseHeartbeatScheduler(lobbyBroadcaster, ticketBroadcaster, dashboardBroadcaster);
        scheduler.emitHeartbeats();

        // Healthy peers received SSE comment ":heartbeat", dead peers were evicted and completed
        assertThat(healthyLobby.getRawFrames().get(1)).contains(":heartbeat");
        assertThat(healthyTicket.getRawFrames().get(1)).contains(":heartbeat");
        assertThat(healthyDashboard.getRawFrames().get(1)).contains(":heartbeat");
        assertThat(deadLobby.isCompleted()).isTrue();
        assertThat(deadTicket.isCompleted()).isTrue();
        assertThat(deadDashboard.isCompleted()).isTrue();

        // Second heartbeat tick only touches healthy peers (dead peers were removed)
        assertThat(lobbyBroadcaster.sendHeartbeats()).isEqualTo(1);
        assertThat(ticketBroadcaster.sendHeartbeats()).isEqualTo(1);
        assertThat(dashboardBroadcaster.sendHeartbeats()).isEqualTo(1);
        assertThat(deadLobby.getFailedSendAttempts()).isEqualTo(1);
        assertThat(deadTicket.getFailedSendAttempts()).isEqualTo(1);
        assertThat(deadDashboard.getFailedSendAttempts()).isEqualTo(1);
    }

    // -------------------------------------------------------------------------
    // #7: Event IDs, Last-Event-ID replay buffer, and subscribe-vs-snapshot race
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("P1 #7: Lobby broadcaster emits monotonic id: fields and replays missed announcements when reconnecting with Last-Event-ID")
    void lobbyBroadcaster_EmitsEventIdsAndReplaysMissedEventsOnLastEventId() {
        when(ticketRepository.findActiveLobbyTicketsByWorkDate(any())).thenReturn(List.of());

        RecordingSseEmitter tvInitial = new RecordingSseEmitter();
        RecordingSseEmitter tvReconnected = new RecordingSseEmitter();
        RecordingSseEmitter tvOldBoot = new RecordingSseEmitter();
        Queue<SseEmitter> emitters = new ArrayDeque<>(List.of(tvInitial, tvReconnected, tvOldBoot));

        TenantLobbySseBroadcasterImpl broadcaster = new TenantLobbySseBroadcasterImpl(ticketRepository, null) {
            @Override
            protected SseEmitter createEmitter() {
                return emitters.remove();
            }
        };

        // 1. Initial TV connection receives INITIAL_DISPLAY with id:
        broadcaster.subscribe(TENANT_CODE);
        assertThat(tvInitial.getRawFrames().get(0)).contains("id:");

        // 2. First call event arrives on TV; capture its SSE id:
        TenantQueueTicket t1 = TenantQueueTicket.builder()
                .id("t-1").ticketCode("A-001").sequenceNumber(1).workDate(WORK_DATE)
                .service(service).status(TicketStatus.CALLING).counterLabel("Counter 1")
                .issuedAt(LocalDateTime.now(FIXED_CLOCK)).build();
        broadcaster.broadcastLobbyEvent(TENANT_CODE, "TICKET_CALLED", t1);
        String event1Id = extractSseId(tvInitial.getRawFrames().get(1));
        assertThat(event1Id).isNotBlank();

        // 3. TV drops connection (simulating brief network blip leaving 0 active subscribers)
        tvInitial.failNextSendsWith(new IOException("Connection reset"));
        broadcaster.sendHeartbeats();
        assertThat(tvInitial.isCompleted()).isTrue();

        // 4. Two announcements happen while TV is offline
        TenantQueueTicket t2 = TenantQueueTicket.builder()
                .id("t-2").ticketCode("A-002").sequenceNumber(2).workDate(WORK_DATE)
                .service(service).status(TicketStatus.CALLING).counterLabel("Counter 2")
                .issuedAt(LocalDateTime.now(FIXED_CLOCK)).build();
        broadcaster.broadcastLobbyEvent(TENANT_CODE, "TICKET_CALLED", t2);
        broadcaster.broadcastLobbyEvent(TENANT_CODE, "TICKET_RECALLED", t2);

        // 5. TV reconnects with Last-Event-ID from event 1 -> gets INITIAL_DISPLAY + both missed events in order
        broadcaster.subscribe(TENANT_CODE, event1Id);
        assertThat(tvReconnected.getSendCount()).isEqualTo(3);
        assertThat(tvReconnected.getRawFrames().get(0)).contains("INITIAL_DISPLAY");
        assertThat(tvReconnected.getRawFrames().get(1)).contains("TICKET_CALLED").contains("A-002");
        assertThat(tvReconnected.getRawFrames().get(2)).contains("TICKET_RECALLED").contains("A-002");
        String event2Id = extractSseId(tvReconnected.getRawFrames().get(1));
        String event3Id = extractSseId(tvReconnected.getRawFrames().get(2));
        assertThat(event2Id).isNotEqualTo(event1Id);
        assertThat(event3Id).isNotEqualTo(event2Id);

        // 6. Client sending a stale Last-Event-ID from a different server boot epoch safely gets only INITIAL_DISPLAY
        broadcaster.subscribe(TENANT_CODE, "oldboot0-1");
        assertThat(tvOldBoot.getSendCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("P1 #7: Ticket subscribe refreshes snapshot if a broadcast races between initial DB read and registration")
    void ticketBroadcaster_ClosesSnapshotVsRegistrationRaceAndEmitsIds() {
        TicketResponse staleWaiting = TicketResponse.builder()
                .id("t-race").ticketCode("A-050").serviceId(SERVICE_ID).workDate(WORK_DATE)
                .status(TicketStatus.WAITING).peopleAheadCount(1L).queuePosition(2L).build();
        TicketResponse freshCalling = TicketResponse.builder()
                .id("t-race").ticketCode("A-050").serviceId(SERVICE_ID).workDate(WORK_DATE)
                .status(TicketStatus.CALLING).counterLabel("Desk 3").build();

        RecordingSseEmitter viewer = new RecordingSseEmitter();
        TenantTicketSseBroadcasterImpl broadcaster = new TenantTicketSseBroadcasterImpl(mockTicketQueryService) {
            @Override
            protected SseEmitter createEmitter() {
                return viewer;
            }
        };

        AtomicInteger readCount = new AtomicInteger(0);
        when(mockTicketQueryService.getTicketDetails("t-race")).thenAnswer(inv -> {
            int call = readCount.incrementAndGet();
            if (call == 1) {
                // Simulate a concurrent broadcast firing while subscribe() is still reading the initial snapshot
                broadcaster.broadcastQueueUpdate(TENANT_CODE, SERVICE_ID, WORK_DATE, "TICKET_CALLED");
                return staleWaiting;
            }
            return freshCalling;
        });

        broadcaster.subscribe(TENANT_CODE, "t-race");

        // Detected the concurrent broadcast and re-fetched fresh CALLING snapshot with id:
        assertThat(readCount.get()).isEqualTo(2);
        assertThat(viewer.getSendCount()).isEqualTo(1);
        assertThat(viewer.getRawFrames().get(0))
                .contains("id:")
                .contains("retry:3000")
                .contains("CALLING");
    }

    // -------------------------------------------------------------------------
    // #9: Per-tenant subscriber cap and leak-free idempotent slot release
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("P1 #9: Per-tenant SSE connection limiter caps combined Lobby/Ticket/Dashboard emitters with HTTP 429 and releases idempotently")
    void connectionLimiter_EnforcesPerTenantCapAndReleasesCleanly() {
        when(ticketRepository.findActiveLobbyTicketsByWorkDate(any())).thenReturn(List.of());
        when(tenantDashboardService.getOverview()).thenReturn(TenantDashboardOverviewResponse.builder().build());

        TenantSseConnectionLimiter limiter = new TenantSseConnectionLimiter(2, 5, 10);

        RecordingSseEmitter lobbyEmitter = new RecordingSseEmitter();
        RecordingSseEmitter otherTenantLobby = new RecordingSseEmitter();
        Queue<SseEmitter> lobbyQueue = new ArrayDeque<>(List.of(lobbyEmitter, otherTenantLobby));
        TenantLobbySseBroadcasterImpl lobbyBroadcaster = new TenantLobbySseBroadcasterImpl(ticketRepository, null) {
            @Override
            protected SseEmitter createEmitter() {
                return lobbyQueue.remove();
            }
        };
        lobbyBroadcaster.setConnectionLimiter(limiter);

        RecordingSseEmitter dashEmitter1 = new RecordingSseEmitter();
        RecordingSseEmitter dashEmitter2 = new RecordingSseEmitter();
        Queue<SseEmitter> dashQueue = new ArrayDeque<>(List.of(dashEmitter1, dashEmitter2));
        DashboardSseBroadcasterImpl dashboardBroadcaster = new DashboardSseBroadcasterImpl(tenantDashboardService, null) {
            @Override
            protected SseEmitter createEmitter() {
                return dashQueue.remove();
            }
        };
        dashboardBroadcaster.setConnectionLimiter(limiter);

        // Tenant clinic_a opens 1 lobby + 1 dashboard stream -> reaches cap of 2
        lobbyBroadcaster.subscribe(TENANT_CODE);
        dashboardBroadcaster.subscribeTenantOverview(TENANT_CODE);
        assertThat(limiter.getActiveCountForTenant(TENANT_CODE)).isEqualTo(2);

        // 3rd subscriber for clinic_a is rejected with HTTP 429 RATE_LIMIT_EXCEEDED
        assertThatThrownBy(() -> dashboardBroadcaster.subscribeTenantOverview(TENANT_CODE))
                .isInstanceOf(AppException.class)
                .satisfies(ex -> {
                    AppException appEx = (AppException) ex;
                    assertThat(appEx.getCode()).isEqualTo(SystemErrorCode.RATE_LIMIT_EXCEEDED.code());
                    assertThat(appEx.getStatus()).isEqualTo(429);
                });
        assertThat(limiter.getActiveCountForTenant(TENANT_CODE)).isEqualTo(2);

        // Another tenant (clinic_b) is unaffected and can still subscribe
        lobbyBroadcaster.subscribe("clinic_b");
        assertThat(limiter.getActiveCountForTenant("clinic_b")).isEqualTo(1);
        assertThat(limiter.getTotalActiveCount()).isEqualTo(3);

        // Evict clinic_a lobby emitter via failed heartbeat -> frees exactly 1 slot (idempotent)
        lobbyEmitter.failNextSendsWith(new IOException("Broken pipe"));
        lobbyBroadcaster.sendHeartbeats();
        assertThat(limiter.getActiveCountForTenant(TENANT_CODE)).isEqualTo(1);

        // Now clinic_a can open a new dashboard stream again
        dashboardBroadcaster.subscribeTenantOverview(TENANT_CODE);
        assertThat(limiter.getActiveCountForTenant(TENANT_CODE)).isEqualTo(2);
    }

    private static String extractSseId(String frame) {
        for (String line : frame.split("\n")) {
            if (line.startsWith("id:")) {
                return line.substring(3).trim();
            }
        }
        return null;
    }

    /**
     * Test double extending {@link SseEmitter} to observe delivered events and inject transport/state errors
     * without requiring a live servlet container.
     */
    private static final class RecordingSseEmitter extends SseEmitter {
        private final AtomicInteger sendCount = new AtomicInteger();
        private final AtomicInteger failedSendAttempts = new AtomicInteger();
        private final AtomicBoolean completed = new AtomicBoolean(false);
        private final List<String> rawFrames = new ArrayList<>();
        private volatile Exception failureToThrow;
        private volatile boolean throwOnComplete;

        RecordingSseEmitter() {
            super(0L);
        }

        void failNextSendsWith(Exception ex) {
            this.failureToThrow = ex;
        }

        void throwOnComplete(boolean flag) {
            this.throwOnComplete = flag;
        }

        int getSendCount() {
            return sendCount.get();
        }

        int getFailedSendAttempts() {
            return failedSendAttempts.get();
        }

        boolean isCompleted() {
            return completed.get();
        }

        List<String> getRawFrames() {
            return rawFrames;
        }

        @Override
        public void send(SseEventBuilder builder) throws IOException {
            Exception ex = failureToThrow;
            if (ex != null) {
                failedSendAttempts.incrementAndGet();
                if (ex instanceof IOException io) {
                    throw io;
                }
                if (ex instanceof RuntimeException re) {
                    throw re;
                }
                throw new RuntimeException(ex);
            }
            StringBuilder frame = new StringBuilder();
            for (DataWithMediaType part : builder.build()) {
                frame.append(part.getData());
            }
            rawFrames.add(frame.toString());
            sendCount.incrementAndGet();
        }

        @Override
        public synchronized void complete() {
            completed.set(true);
            if (throwOnComplete) {
                throw new IllegalStateException("Already completed");
            }
            super.complete();
        }

        @Override
        public synchronized void completeWithError(Throwable ex) {
            completed.set(true);
            if (throwOnComplete) {
                throw new IllegalStateException("Already completed with error");
            }
            super.completeWithError(ex);
        }
    }
}
