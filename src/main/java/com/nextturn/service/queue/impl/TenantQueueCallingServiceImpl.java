package com.nextturn.service.queue.impl;

import com.nextturn.dto.queue.CallNextTicketRequest;
import com.nextturn.dto.queue.LobbyDisplayResponse;
import com.nextturn.dto.queue.QueueBoardResponse;
import com.nextturn.dto.queue.QueueTicketSummaryResponse;
import com.nextturn.dto.queue.ServeTicketRequest;
import com.nextturn.dto.queue.TicketTransitionRequest;
import com.nextturn.dto.ticket.TicketResponse;
import com.nextturn.entity.TenantAppointment;
import com.nextturn.entity.TenantQueueTicket;
import com.nextturn.entity.TenantService;
import com.nextturn.entity.TenantStaff;
import com.nextturn.enums.AppointmentStatus;
import com.nextturn.enums.ServiceStatus;
import com.nextturn.enums.TicketStatus;
import com.nextturn.exception.counter.CounterErrorCode;
import com.nextturn.exception.counter.CounterException;
import com.nextturn.exception.queue.QueueErrorCode;
import com.nextturn.exception.queue.QueueException;
import com.nextturn.exception.ticket.TicketErrorCode;
import com.nextturn.exception.ticket.TicketException;
import com.nextturn.infrastructure.security.principal.AuthenticatedUser;
import com.nextturn.repository.appointment.TenantAppointmentRepository;
import com.nextturn.repository.service.TenantServiceRepository;
import com.nextturn.repository.staff.TenantStaffRepository;
import com.nextturn.repository.ticket.TenantQueueTicketRepository;
import com.nextturn.service.BaseService;
import com.nextturn.service.queue.TenantLobbySseBroadcaster;
import com.nextturn.service.queue.TenantQueueCallingService;
import com.nextturn.service.queue.TenantSpecialistDispatchService;
import com.nextturn.service.queue.TenantTicketStateMachine;
import com.nextturn.service.ticket.TenantTicketQueryService;
import com.nextturn.service.ticket.TenantTicketSseBroadcaster;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Comparator;
import java.util.List;

/**
 * Default implementation of {@link TenantQueueCallingService} orchestrating the strict
 * Queue Calling FSM (WAITING -> CALLING -> SERVING -> COMPLETED / NO_SHOW) with weighted priority
 * scoring, specialist capacity checks, load balancing, optimistic locking, and SSE broadcasts (REQ-QC-01..04).
 */
@Service
public class TenantQueueCallingServiceImpl extends BaseService implements TenantQueueCallingService {

    private static final Logger log = LoggerFactory.getLogger(TenantQueueCallingServiceImpl.class);
    private static final long NO_SHOW_MIN_RECALLS = 3L;
    private static final long NO_SHOW_MIN_WAIT_MINUTES = 3L;
    private static final double AUTO_COMPLETE_MULTIPLIER = 1.5d;

    private final TenantQueueTicketRepository ticketRepository;
    private final TenantServiceRepository serviceRepository;
    private final TenantStaffRepository staffRepository;
    private final TenantAppointmentRepository appointmentRepository;
    private final TenantTicketStateMachine stateMachine;
    private final TenantSpecialistDispatchService specialistDispatchService;
    private final TenantTicketQueryService ticketQueryService;
    private final TenantTicketSseBroadcaster ticketSseBroadcaster;
    private final TenantLobbySseBroadcaster lobbySseBroadcaster;
    private final Clock clock;

    @Autowired
    public TenantQueueCallingServiceImpl(
            TenantQueueTicketRepository ticketRepository,
            TenantServiceRepository serviceRepository,
            TenantStaffRepository staffRepository,
            TenantAppointmentRepository appointmentRepository,
            TenantTicketStateMachine stateMachine,
            TenantSpecialistDispatchService specialistDispatchService,
            TenantTicketQueryService ticketQueryService,
            @Autowired(required = false) TenantTicketSseBroadcaster ticketSseBroadcaster,
            @Autowired(required = false) TenantLobbySseBroadcaster lobbySseBroadcaster,
            @Autowired(required = false) TransactionTemplate transactionTemplate
    ) {
        this(ticketRepository, serviceRepository, staffRepository, appointmentRepository, stateMachine,
                specialistDispatchService, ticketQueryService, ticketSseBroadcaster, lobbySseBroadcaster,
                transactionTemplate, Clock.systemDefaultZone());
    }

    public TenantQueueCallingServiceImpl(
            TenantQueueTicketRepository ticketRepository,
            TenantServiceRepository serviceRepository,
            TenantStaffRepository staffRepository,
            TenantAppointmentRepository appointmentRepository,
            TenantTicketStateMachine stateMachine,
            TenantSpecialistDispatchService specialistDispatchService,
            TenantTicketQueryService ticketQueryService,
            TenantTicketSseBroadcaster ticketSseBroadcaster,
            TenantLobbySseBroadcaster lobbySseBroadcaster,
            TransactionTemplate transactionTemplate,
            Clock clock
    ) {
        super(transactionTemplate);
        this.ticketRepository = ticketRepository;
        this.serviceRepository = serviceRepository;
        this.staffRepository = staffRepository;
        this.appointmentRepository = appointmentRepository;
        this.stateMachine = stateMachine;
        this.specialistDispatchService = specialistDispatchService;
        this.ticketQueryService = ticketQueryService;
        this.ticketSseBroadcaster = ticketSseBroadcaster;
        this.lobbySseBroadcaster = lobbySseBroadcaster;
        this.clock = clock != null ? clock : Clock.systemDefaultZone();
    }

    @Override
    public TicketResponse callNextTicket(CallNextTicketRequest request, AuthenticatedUser caller) {
        String tenantCode = resolveCurrentTenantCode();
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDate today = now.toLocalDate();
        LocalTime nowTime = now.toLocalTime();

        CallOutcome outcome = executeInTransaction(status -> {
            TenantService service = serviceRepository.findByIdAndIsDeletedFalse(request.getServiceId().trim())
                    .filter(s -> s.getStatus() == ServiceStatus.ACTIVE)
                    .orElseThrow(() -> new TicketException(TicketErrorCode.SERVICE_UNAVAILABLE_FOR_TICKET));

            TenantStaff counterStaff = resolveCounterStaff(request.getCounterStaffId(), caller);
            if (counterStaff != null && ticketRepository.existsByCounterStaffIdAndWorkDateAndStatus(
                    counterStaff.getId(), today, TicketStatus.CALLING)) {
                throw new CounterException(CounterErrorCode.COUNTER_HAS_ACTIVE_CALL);
            }

            // REQ-QC-01: Must have >= 1 active certified specialist available in shift
            specialistDispatchService.assertSpecialistCapacityAvailable(service, today, nowTime);

            TenantQueueTicket nextTicket = ticketRepository
                    .findNextPriorityWaitingTicketForUpdate(service.getId(), today, now)
                    .orElseThrow(() -> new QueueException(QueueErrorCode.QUEUE_EMPTY));

            stateMachine.assertValidTransition(nextTicket, TicketStatus.CALLING, null);
            TicketStatus prevStatus = nextTicket.getStatus();

            nextTicket.setStatus(TicketStatus.CALLING);
            nextTicket.setCounterStaff(counterStaff);
            nextTicket.setCalledAt(now);
            if (request.getCounterLabel() != null && !request.getCounterLabel().isBlank()) {
                nextTicket.setCounterLabel(request.getCounterLabel().trim());
            }

            TenantQueueTicket saved = saveAndFlushSafe(nextTicket);
            stateMachine.recordAuditLog(saved, prevStatus, TicketStatus.CALLING, resolveActorId(counterStaff, caller));

            TenantQueueTicket initialized = ticketRepository.findByIdWithDetails(saved.getId()).orElse(saved);
            return new CallOutcome(initialized, ticketQueryService.enrichWithQueueMetrics(initialized));
        });

        broadcastAll(tenantCode, outcome.ticket(), "TICKET_CALLED");
        log.info("Called next priority ticket [{}] ({}) for service [{}] in tenant [{}]",
                outcome.response().getTicketCode(), outcome.response().getCustomerType(),
                outcome.response().getServiceCode(), tenantCode);
        return outcome.response();
    }

    @Override
    public TicketResponse recallTicket(String ticketId, TicketTransitionRequest request, AuthenticatedUser caller) {
        String tenantCode = resolveCurrentTenantCode();
        CallOutcome outcome = executeInTransaction(status -> {
            TenantQueueTicket ticket = findTicketOrThrow(ticketId);
            Integer expectedVersion = request != null ? request.getExpectedVersion() : null;
            stateMachine.assertValidTransition(ticket, TicketStatus.CALLING, expectedVersion);

            int nextRecall = (ticket.getRecallCount() != null ? ticket.getRecallCount() : 0) + 1;
            ticket.setRecallCount(nextRecall);
            if (request != null && request.getCounterLabel() != null && !request.getCounterLabel().isBlank()) {
                ticket.setCounterLabel(request.getCounterLabel().trim());
            }
            // Note: Keep original calledAt intact so the 3-minute waiting timer for NO_SHOW remains accurate

            TenantQueueTicket saved = saveAndFlushSafe(ticket);
            TenantStaff actorStaff = resolveCounterStaff(null, caller);
            stateMachine.recordAuditLog(saved, TicketStatus.CALLING, TicketStatus.CALLING, resolveActorId(actorStaff, caller));

            TenantQueueTicket initialized = ticketRepository.findByIdWithDetails(saved.getId()).orElse(saved);
            return new CallOutcome(initialized, ticketQueryService.enrichWithQueueMetrics(initialized));
        });

        broadcastAll(tenantCode, outcome.ticket(), "TICKET_RECALLED");
        return outcome.response();
    }

    @Override
    public TicketResponse serveTicket(String ticketId, ServeTicketRequest request, AuthenticatedUser caller) {
        String tenantCode = resolveCurrentTenantCode();
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDate today = now.toLocalDate();
        LocalTime nowTime = now.toLocalTime();

        CallOutcome outcome = executeInTransaction(status -> {
            TenantQueueTicket ticket = findTicketOrThrow(ticketId);
            Integer expectedVersion = request != null ? request.getExpectedVersion() : null;
            stateMachine.assertValidTransition(ticket, TicketStatus.SERVING, expectedVersion);

            String requestedProviderId = request != null ? request.getProviderId() : null;
            TenantStaff specialist = specialistDispatchService.selectOrValidateSpecialist(
                    ticket.getService().getId(), requestedProviderId, today, nowTime
            );

            TicketStatus prevStatus = ticket.getStatus();
            ticket.setStatus(TicketStatus.SERVING);
            ticket.setProvider(specialist);
            ticket.setServedAt(now);

            TenantQueueTicket saved = saveAndFlushSafe(ticket);
            TenantStaff actorStaff = resolveCounterStaff(null, caller);
            stateMachine.recordAuditLog(saved, prevStatus, TicketStatus.SERVING, resolveActorId(actorStaff, caller));

            TenantQueueTicket initialized = ticketRepository.findByIdWithDetails(saved.getId()).orElse(saved);
            return new CallOutcome(initialized, ticketQueryService.enrichWithQueueMetrics(initialized));
        });

        broadcastAll(tenantCode, outcome.ticket(), "TICKET_SERVING");
        log.info("Transferred ticket [{}] to specialist [{}] (status: SERVING) in tenant [{}]",
                outcome.response().getTicketCode(), outcome.response().getProviderName(), tenantCode);
        return outcome.response();
    }

    @Override
    public TicketResponse completeTicket(String ticketId, TicketTransitionRequest request, AuthenticatedUser caller) {
        String tenantCode = resolveCurrentTenantCode();
        LocalDateTime now = LocalDateTime.now(clock);

        CallOutcome outcome = executeInTransaction(status -> {
            TenantQueueTicket ticket = findTicketOrThrow(ticketId);
            Integer expectedVersion = request != null ? request.getExpectedVersion() : null;
            stateMachine.assertValidTransition(ticket, TicketStatus.COMPLETED, expectedVersion);

            TicketStatus prevStatus = ticket.getStatus();
            ticket.setStatus(TicketStatus.COMPLETED);
            ticket.setCompletedAt(now);
            ticket.setAutoCompleted(false);
            if (ticket.getServedAt() != null && !now.isBefore(ticket.getServedAt())) {
                ticket.setActualDuration((int) Duration.between(ticket.getServedAt(), now).getSeconds());
            }

            TenantQueueTicket saved = saveAndFlushSafe(ticket);
            TenantStaff actorStaff = resolveCounterStaff(null, caller);
            stateMachine.recordAuditLog(saved, prevStatus, TicketStatus.COMPLETED, resolveActorId(actorStaff, caller));

            TenantQueueTicket initialized = ticketRepository.findByIdWithDetails(saved.getId()).orElse(saved);
            return new CallOutcome(initialized, ticketQueryService.enrichWithQueueMetrics(initialized));
        });

        broadcastAll(tenantCode, outcome.ticket(), "TICKET_COMPLETED");
        return outcome.response();
    }

    @Override
    public TicketResponse markNoShow(String ticketId, TicketTransitionRequest request, AuthenticatedUser caller) {
        String tenantCode = resolveCurrentTenantCode();
        LocalDateTime now = LocalDateTime.now(clock);

        CallOutcome outcome = executeInTransaction(status -> {
            TenantQueueTicket ticket = findTicketOrThrow(ticketId);
            Integer expectedVersion = request != null ? request.getExpectedVersion() : null;
            stateMachine.assertValidTransition(ticket, TicketStatus.NO_SHOW, expectedVersion);

            int recalls = ticket.getRecallCount() != null ? ticket.getRecallCount() : 0;
            long minutesSinceCalled = ticket.getCalledAt() != null && !now.isBefore(ticket.getCalledAt())
                    ? Duration.between(ticket.getCalledAt(), now).toMinutes()
                    : 0L;

            if (recalls < NO_SHOW_MIN_RECALLS && minutesSinceCalled < NO_SHOW_MIN_WAIT_MINUTES) {
                throw new QueueException(QueueErrorCode.NO_SHOW_PRECONDITION_NOT_MET);
            }

            TicketStatus prevStatus = ticket.getStatus();
            ticket.setStatus(TicketStatus.NO_SHOW);
            ticket.setCompletedAt(now);

            TenantStaff actorStaff = resolveCounterStaff(null, caller);
            if (ticket.getCounterStaff() == null && actorStaff != null) {
                ticket.setCounterStaff(actorStaff);
            }

            // Sync linked pre-booked appointment to NO_SHOW per REQ-QC-04
            TenantAppointment linkedAppointment = ticket.getAppointment();
            if (linkedAppointment != null && appointmentRepository != null) {
                linkedAppointment.setStatus(AppointmentStatus.NO_SHOW);
                appointmentRepository.save(linkedAppointment);
            }

            TenantQueueTicket saved = saveAndFlushSafe(ticket);
            stateMachine.recordAuditLog(saved, prevStatus, TicketStatus.NO_SHOW, resolveActorId(actorStaff, caller));

            TenantQueueTicket initialized = ticketRepository.findByIdWithDetails(saved.getId()).orElse(saved);
            return new CallOutcome(initialized, ticketQueryService.enrichWithQueueMetrics(initialized));
        });

        broadcastAll(tenantCode, outcome.ticket(), "TICKET_NO_SHOW");
        return outcome.response();
    }

    @Override
    public int autoCompleteOverdueTickets() {
        String tenantCode = resolveCurrentTenantCode();
        LocalDateTime now = LocalDateTime.now(clock);

        List<TenantQueueTicket> completed = executeInTransaction(status -> {
            List<TenantQueueTicket> servingTickets = ticketRepository.findAllServingWithDetails();
            if (servingTickets == null || servingTickets.isEmpty()) {
                return List.of();
            }

            List<TenantQueueTicket> overdue = servingTickets.stream()
                    .filter(t -> isOverdueForAutoComplete(t, now))
                    .toList();
            if (overdue.isEmpty()) {
                return List.of();
            }

            for (TenantQueueTicket ticket : overdue) {
                stateMachine.assertValidTransition(ticket, TicketStatus.COMPLETED, ticket.getVersion());
                ticket.setStatus(TicketStatus.COMPLETED);
                ticket.setCompletedAt(now);
                ticket.setAutoCompleted(true);
                ticket.setActualDuration((int) Duration.between(ticket.getServedAt(), now).getSeconds());
            }

            List<TenantQueueTicket> savedList = ticketRepository.saveAll(overdue);
            ticketRepository.flush();

            for (TenantQueueTicket saved : savedList) {
                stateMachine.recordAuditLog(saved, TicketStatus.SERVING, TicketStatus.COMPLETED, "SYSTEM_AUTO_COMPLETE");
            }
            return savedList;
        });

        for (TenantQueueTicket ticket : completed) {
            broadcastAll(tenantCode, ticket, "TICKET_AUTO_COMPLETED");
        }
        return completed.size();
    }

    @Override
    public QueueBoardResponse getQueueBoard(String serviceId) {
        resolveCurrentTenantCode();
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDate today = now.toLocalDate();
        LocalTime nowTime = now.toLocalTime();
        String cleanServiceId = (serviceId != null && !serviceId.isBlank()) ? serviceId.trim() : null;

        return executeInTransaction(status -> {
            List<TenantQueueTicket> todayTickets =
                    ticketRepository.findByWorkDateAndOptionalServiceWithDetails(today, cleanServiceId);

            List<QueueTicketSummaryResponse> waiting = todayTickets.stream()
                    .filter(t -> t.getStatus() == TicketStatus.WAITING)
                    .map(t -> toBoardCard(t, now))
                    .sorted(Comparator
                            .comparingLong(QueueTicketSummaryResponse::getPriorityScore).reversed()
                            .thenComparing(QueueTicketSummaryResponse::getIssuedAt)
                            .thenComparingInt(QueueTicketSummaryResponse::getSequenceNumber))
                    .toList();

            List<QueueTicketSummaryResponse> calling = todayTickets.stream()
                    .filter(t -> t.getStatus() == TicketStatus.CALLING)
                    .map(t -> toBoardCard(t, now))
                    .toList();

            List<QueueTicketSummaryResponse> serving = todayTickets.stream()
                    .filter(t -> t.getStatus() == TicketStatus.SERVING)
                    .map(t -> toBoardCard(t, now))
                    .toList();

            List<QueueTicketSummaryResponse> completed = todayTickets.stream()
                    .filter(t -> t.getStatus() == TicketStatus.COMPLETED)
                    .map(t -> toBoardCard(t, now))
                    .toList();

            return QueueBoardResponse.builder()
                    .workDate(today)
                    .serviceId(cleanServiceId)
                    .waitingTickets(waiting)
                    .callingTickets(calling)
                    .servingTickets(serving)
                    .completedTickets(completed)
                    .specialists(specialistDispatchService.getSpecialistWorkloads(cleanServiceId, today, nowTime))
                    .build();
        });
    }

    @Override
    public LobbyDisplayResponse getLobbyDisplay() {
        String tenantCode = resolveCurrentTenantCode();
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDate today = now.toLocalDate();

        return executeInTransaction(status -> {
            List<TenantQueueTicket> active = ticketRepository.findActiveLobbyTicketsByWorkDate(today);
            List<QueueTicketSummaryResponse> calling = active.stream()
                    .filter(t -> t.getStatus() == TicketStatus.CALLING)
                    .map(t -> toBoardCard(t, now))
                    .toList();
            List<QueueTicketSummaryResponse> serving = active.stream()
                    .filter(t -> t.getStatus() == TicketStatus.SERVING)
                    .map(t -> toBoardCard(t, now))
                    .toList();

            return LobbyDisplayResponse.builder()
                    .tenantCode(tenantCode)
                    .workDate(today)
                    .callingTickets(calling)
                    .servingTickets(serving)
                    .generatedAt(now)
                    .build();
        });
    }

    @Override
    public SseEmitter subscribeToLobbyDisplay() {
        String tenantCode = resolveCurrentTenantCode();
        if (lobbySseBroadcaster == null) {
            SseEmitter emitter = new SseEmitter(0L);
            emitter.complete();
            return emitter;
        }
        return lobbySseBroadcaster.subscribe(tenantCode);
    }

    private boolean isOverdueForAutoComplete(TenantQueueTicket ticket, LocalDateTime now) {
        if (ticket.getServedAt() == null || now.isBefore(ticket.getServedAt())) {
            return false;
        }
        int standardDuration = (ticket.getService() != null
                && ticket.getService().getStandardDuration() != null
                && ticket.getService().getStandardDuration() > 0)
                ? ticket.getService().getStandardDuration()
                : 15;
        long elapsedSeconds = Duration.between(ticket.getServedAt(), now).getSeconds();
        double thresholdSeconds = standardDuration * 60.0d * AUTO_COMPLETE_MULTIPLIER;
        return elapsedSeconds >= thresholdSeconds;
    }

    private TenantQueueTicket findTicketOrThrow(String ticketId) {
        if (ticketId == null || ticketId.isBlank()) {
            throw new TicketException(TicketErrorCode.TICKET_NOT_FOUND);
        }
        return ticketRepository.findByIdWithDetails(ticketId.trim())
                .orElseThrow(() -> new TicketException(TicketErrorCode.TICKET_NOT_FOUND));
    }

    private TenantQueueTicket saveAndFlushSafe(TenantQueueTicket ticket) {
        try {
            TenantQueueTicket saved = ticketRepository.save(ticket);
            ticketRepository.flush();
            return saved;
        } catch (OptimisticLockingFailureException ex) {
            throw new QueueException(QueueErrorCode.VERSION_CONFLICT);
        }
    }

    private TenantStaff resolveCounterStaff(String explicitStaffId, AuthenticatedUser caller) {
        if (staffRepository == null) {
            return null;
        }
        if (explicitStaffId != null && !explicitStaffId.isBlank()) {
            return staffRepository.findByIdAndIsDeletedFalse(explicitStaffId.trim()).orElse(null);
        }
        if (caller == null) {
            return null;
        }
        if (caller.userId() != null && !caller.userId().isBlank()) {
            var bySub = staffRepository.findByKeycloakUserIdAndIsDeletedFalse(caller.userId().trim());
            if (bySub.isPresent()) {
                return bySub.get();
            }
        }
        if (caller.email() != null && !caller.email().isBlank()) {
            return staffRepository.findByEmailAndIsDeletedFalse(caller.email().trim()).orElse(null);
        }
        return null;
    }

    private String resolveActorId(TenantStaff staff, AuthenticatedUser caller) {
        if (staff != null) {
            return staff.getStaffCode() != null ? staff.getStaffCode() : staff.getId();
        }
        if (caller != null) {
            return caller.email() != null ? caller.email() : caller.userId();
        }
        return "COUNTER_STAFF";
    }

    private QueueTicketSummaryResponse toBoardCard(TenantQueueTicket t, LocalDateTime now) {
        return QueueTicketSummaryResponse.builder()
                .id(t.getId())
                .ticketCode(t.getTicketCode())
                .sequenceNumber(t.getSequenceNumber())
                .serviceId(t.getService() != null ? t.getService().getId() : null)
                .serviceCode(t.getService() != null ? t.getService().getServiceCode() : null)
                .serviceName(t.getService() != null ? t.getService().getServiceName() : null)
                .customerType(t.getCustomerType())
                .customerName(t.getCustomerName())
                .customerPhone(t.getCustomerPhone())
                .status(t.getStatus())
                .priorityScore(stateMachine.computePriorityScore(t, now))
                .waitingMinutes(stateMachine.computeWaitingMinutes(t, now))
                .recallCount(t.getRecallCount())
                .version(t.getVersion())
                .autoCompleted(t.getAutoCompleted())
                .actualDuration(t.getActualDuration())
                .counterStaffId(t.getCounterStaff() != null ? t.getCounterStaff().getId() : null)
                .counterStaffName(t.getCounterStaff() != null ? t.getCounterStaff().getFullName() : null)
                .counterLabel(t.getCounterLabel())
                .providerId(t.getProvider() != null ? t.getProvider().getId() : null)
                .providerName(t.getProvider() != null ? t.getProvider().getFullName() : null)
                .issuedAt(t.getIssuedAt())
                .calledAt(t.getCalledAt())
                .servedAt(t.getServedAt())
                .completedAt(t.getCompletedAt())
                .build();
    }

    private void broadcastAll(String tenantCode, TenantQueueTicket ticket, String eventType) {
        if (ticket == null) {
            return;
        }
        if (ticketSseBroadcaster != null && ticket.getService() != null) {
            ticketSseBroadcaster.broadcastQueueUpdate(
                    tenantCode, ticket.getService().getId(), ticket.getWorkDate(), eventType
            );
        }
        if (lobbySseBroadcaster != null) {
            lobbySseBroadcaster.broadcastLobbyEvent(tenantCode, eventType, ticket);
        }
    }

    private record CallOutcome(TenantQueueTicket ticket, TicketResponse response) {
    }
}
