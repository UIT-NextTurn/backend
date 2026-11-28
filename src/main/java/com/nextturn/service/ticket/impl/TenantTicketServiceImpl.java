package com.nextturn.service.ticket.impl;

import com.nextturn.constant.ValkeyKeyConstant;
import com.nextturn.dto.ticket.IssueTicketRequest;
import com.nextturn.dto.ticket.TicketResponse;
import com.nextturn.entity.TenantAppointment;
import com.nextturn.entity.TenantQueueTicket;
import com.nextturn.entity.TenantReceptionPoint;
import com.nextturn.entity.TenantService;
import com.nextturn.entity.TenantTicketAuditLog;
import com.nextturn.enums.AppointmentStatus;
import com.nextturn.enums.TicketCustomerType;
import com.nextturn.enums.TicketStatus;
import com.nextturn.exception.checkin.CheckInErrorCode;
import com.nextturn.exception.checkin.CheckInException;
import com.nextturn.exception.ticket.TicketErrorCode;
import com.nextturn.exception.ticket.TicketException;
import com.nextturn.repository.appointment.TenantAppointmentRepository;
import com.nextturn.repository.ticket.TenantQueueTicketRepository;
import com.nextturn.repository.ticket.TenantTicketAuditLogRepository;
import com.nextturn.service.BaseService;
import com.nextturn.infrastructure.multitenancy.TenantContextHolder;
import com.nextturn.service.checkin.TenantReceptionQrTokenService;
import com.nextturn.service.tenant.TenantCacheService;
import com.nextturn.service.ticket.TenantTicketQueryService;
import com.nextturn.service.ticket.TenantTicketSequenceGenerator;
import com.nextturn.service.ticket.TenantTicketService;
import com.nextturn.service.ticket.TenantTicketSseBroadcaster;
import com.nextturn.service.ticket.TenantTicketValidator;
import com.nextturn.service.ticket.TenantTicketSequenceGenerator.GeneratedTicketNumber;
import com.nextturn.config.AsyncConfig;
import com.nextturn.infrastructure.logging.LogContract;
import com.nextturn.infrastructure.logging.LogDomain;
import com.nextturn.infrastructure.logging.LogOutcome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executor;

/**
 * Default orchestrator implementation of {@link TenantTicketService} coordinating:
 * <ul>
 *     <li>Valkey distributed mutex locking against race conditions</li>
 *     <li>Atomic monotonic sequence generation via Valkey Lua script (or MySQL fallback)</li>
 *     <li>Transactional ticket + audit log persistence in {@code tenant__queue_tickets}</li>
 *     <li>Real-time SSE broadcasting and Valkey telemetry cache refresh</li>
 * </ul>
 */
@Service
public class TenantTicketServiceImpl extends BaseService implements TenantTicketService {

    private static final Logger log = LoggerFactory.getLogger(TenantTicketServiceImpl.class);

    private final TenantQueueTicketRepository ticketRepository;
    private final TenantTicketAuditLogRepository auditLogRepository;
    private final TenantAppointmentRepository appointmentRepository;
    private final TenantTicketSequenceGenerator sequenceGenerator;
    private final TenantTicketValidator validator;
    private final TenantTicketQueryService queryService;
    private final TenantTicketSseBroadcaster sseBroadcaster;
    private final TenantCacheService cacheService;
    private final TenantReceptionQrTokenService receptionQrTokenService;
    private com.nextturn.service.dashboard.DashboardAnalyticsSyncService dashboardAnalyticsSyncService;
    private Executor postCommitExecutor;

    @Autowired(required = false)
    @Qualifier(AsyncConfig.QUEUE_EVENT_EXECUTOR)
    public void setPostCommitExecutor(Executor postCommitExecutor) {
        this.postCommitExecutor = postCommitExecutor;
    }

    @Autowired(required = false)
    public void setDashboardAnalyticsSyncService(
            com.nextturn.service.dashboard.DashboardAnalyticsSyncService dashboardAnalyticsSyncService) {
        this.dashboardAnalyticsSyncService = dashboardAnalyticsSyncService;
    }

    @Autowired
    public TenantTicketServiceImpl(
            TenantQueueTicketRepository ticketRepository,
            TenantTicketAuditLogRepository auditLogRepository,
            TenantAppointmentRepository appointmentRepository,
            TenantTicketSequenceGenerator sequenceGenerator,
            TenantTicketValidator validator,
            TenantTicketQueryService queryService,
            @Autowired(required = false) TenantTicketSseBroadcaster sseBroadcaster,
            @Autowired(required = false) TenantCacheService cacheService,
            @Autowired(required = false) TenantReceptionQrTokenService receptionQrTokenService,
            @Autowired(required = false) TransactionTemplate transactionTemplate
    ) {
        super(transactionTemplate);
        this.ticketRepository = ticketRepository;
        this.auditLogRepository = auditLogRepository;
        this.appointmentRepository = appointmentRepository;
        this.sequenceGenerator = sequenceGenerator;
        this.validator = validator;
        this.queryService = queryService;
        this.sseBroadcaster = sseBroadcaster;
        this.cacheService = cacheService;
        this.receptionQrTokenService = receptionQrTokenService;
    }

    /**
     * Backward-compatible constructor used by unit tests.
     */
    public TenantTicketServiceImpl(
            TenantQueueTicketRepository ticketRepository,
            TenantTicketAuditLogRepository auditLogRepository,
            TenantAppointmentRepository appointmentRepository,
            TenantTicketSequenceGenerator sequenceGenerator,
            TenantTicketValidator validator,
            TenantTicketQueryService queryService,
            TenantTicketSseBroadcaster sseBroadcaster,
            TenantCacheService cacheService,
            TransactionTemplate transactionTemplate
    ) {
        this(ticketRepository, auditLogRepository, appointmentRepository, sequenceGenerator,
                validator, queryService, sseBroadcaster, cacheService, null, transactionTemplate);
    }

    @Override
    public TicketResponse issueTicket(IssueTicketRequest request) {
        if (request == null) {
            throw new TicketException(TicketErrorCode.INVALID_TICKET_STATE, "Yêu cầu cấp vé không được để trống.");
        }

        if ((TenantContextHolder.getTenantCode() == null || TenantContextHolder.getTenantCode().isBlank())
                && request.getReceptionQrToken() != null && !request.getReceptionQrToken().isBlank()
                && receptionQrTokenService != null) {
            String embeddedTenant = receptionQrTokenService.extractTenantCodeFromToken(request.getReceptionQrToken());
            if (embeddedTenant != null && !embeddedTenant.isBlank()) {
                TenantContextHolder.setTenantCode(embeddedTenant);
            }
        }

        String tenantCode = resolveCurrentTenantCode();
        LocalDate today = LocalDate.now();

        boolean isAppointmentFlow = (request.getAppointmentId() != null && !request.getAppointmentId().isBlank())
                || (request.getQrToken() != null && !request.getQrToken().isBlank());

        TenantReceptionPoint receptionPoint = null;
        if (request.getReceptionQrToken() != null && !request.getReceptionQrToken().isBlank()
                && receptionQrTokenService != null) {
            receptionPoint = receptionQrTokenService.validateAndResolveToken(request.getReceptionQrToken());
        }

        TenantAppointment preValidatedAppointment = isAppointmentFlow
                ? validator.validateAppointmentForCheckIn(request, today)
                : null;

        // Idempotent check: if appointment already checked in and has a ticket, return existing ticket
        if (preValidatedAppointment != null && preValidatedAppointment.getStatus() == AppointmentStatus.CHECKED_IN) {
            Optional<TenantQueueTicket> existingTicket = ticketRepository.findByAppointmentIdWithDetails(preValidatedAppointment.getId());
            if (existingTicket.isPresent()) {
                LogContract.info(log, LogDomain.TICKET, "checkin_idempotent")
                        .outcome(LogOutcome.SUCCESS)
                        .entity("ticket", existingTicket.get().getTicketCode())
                        .field("appointmentCode", preValidatedAppointment.getAppointmentCode())
                        .emit();
                return queryService.enrichWithQueueMetrics(existingTicket.get());
            }
        }

        String targetServiceId = resolveTargetServiceId(preValidatedAppointment, request, receptionPoint);

        TenantService service = validator.validateActiveService(targetServiceId);
        validator.validateTenantDailyTicketQuota(tenantCode, today);

        if (!isAppointmentFlow) {
            validator.validateWalkInEligibility(service, request.getCustomerPhone(), today);
        }

        // Acquire Valkey Distributed Mutex Lock for (tenantCode, serviceId, today) to prevent Race Condition
        String lockOwnerToken = sequenceGenerator.acquireTicketLock(tenantCode, service.getId(), today);

        final TenantReceptionPoint resolvedReceptionPoint = receptionPoint;
        final java.util.concurrent.atomic.AtomicReference<TenantQueueTicket> issuedEntityRef =
                new java.util.concurrent.atomic.AtomicReference<>();
        TicketResponse response;
        try {
            response = executeInTransaction(status -> {
                // Re-check idempotency / duplicate inside lock & transaction
                if (preValidatedAppointment != null) {
                    Optional<TenantQueueTicket> existingInsideLock =
                            ticketRepository.findByAppointmentIdWithDetails(preValidatedAppointment.getId());
                    if (existingInsideLock.isPresent()) {
                        return queryService.enrichWithQueueMetrics(existingInsideLock.get());
                    }
                } else {
                    validator.validateWalkInEligibility(service, request.getCustomerPhone(), today);
                }

                // Atomic Lua INCR sequence generation (A-001 .. A-999 -> A-1000)
                GeneratedTicketNumber generated = sequenceGenerator.nextTicketNumber(tenantCode, service, today);

                String customerName = preValidatedAppointment != null
                        ? preValidatedAppointment.getCustomerName()
                        : (request.getCustomerName() != null && !request.getCustomerName().isBlank()
                                ? request.getCustomerName().trim()
                                : null);
                String customerPhone = preValidatedAppointment != null
                        ? preValidatedAppointment.getCustomerPhone()
                        : (request.getCustomerPhone() != null ? request.getCustomerPhone().trim() : null);

                TicketCustomerType customerType = preValidatedAppointment != null
                        ? TicketCustomerType.APPOINTMENT
                        : TicketCustomerType.WALK_IN;

                LocalDateTime issuedAt = LocalDateTime.now();

                TenantQueueTicket ticket = TenantQueueTicket.builder()
                        .id(UUID.randomUUID().toString())
                        .ticketCode(generated.ticketCode())
                        .sequenceNumber(generated.sequenceNumber())
                        .workDate(today)
                        .service(service)
                        .appointment(preValidatedAppointment)
                        .receptionPoint(resolvedReceptionPoint)
                        .customerName(customerName)
                        .customerPhone(customerPhone)
                        .customerType(customerType)
                        .status(TicketStatus.WAITING)
                        .recallCount(0)
                        .autoCompleted(false)
                        .approachingAlertSent(false)
                        .issuedAt(issuedAt)
                        .build();

                TenantQueueTicket persisted = ticketRepository.save(ticket);

                // Transition appointment status to CHECKED_IN when ticket is issued
                if (preValidatedAppointment != null && appointmentRepository != null) {
                    preValidatedAppointment.setStatus(AppointmentStatus.CHECKED_IN);
                    appointmentRepository.save(preValidatedAppointment);
                }

                // Record audit log entry (Section 3.16)
                if (auditLogRepository != null) {
                    String actorId = customerPhone != null ? customerPhone : "SYSTEM";
                    TenantTicketAuditLog auditLog = TenantTicketAuditLog.builder()
                            .id(UUID.randomUUID().toString())
                            .ticket(persisted)
                            .actorId(actorId)
                            .previousState(TicketStatus.WAITING)
                            .newState(TicketStatus.WAITING)
                            .build();
                    auditLogRepository.save(auditLog);
                }

                // Flush & build DTO inside active JPA session so lazy associations (service, receptionPoint) stay initialized
                ticketRepository.flush();
                TenantQueueTicket initialized = ticketRepository.findByIdWithDetails(persisted.getId())
                        .orElse(persisted);
                issuedEntityRef.set(initialized);
                return queryService.enrichWithQueueMetrics(initialized);
            });
        } finally {
            sequenceGenerator.releaseTicketLock(tenantCode, service.getId(), today, lockOwnerToken);
        }

        // Dispatch post-commit analytics, telemetry refresh, and SSE fan-out asynchronously on bounded executor
        dispatchPostIssueSideEffects(tenantCode, service.getId(), today, issuedEntityRef.get(), response.getTicketCode());

        LogContract.info(log, LogDomain.TICKET, "ticket_issued")
                .outcome(LogOutcome.SUCCESS)
                .tenant(tenantCode)
                .entity("ticket", response.getTicketCode())
                .field("sequence", response.getSequenceNumber())
                .field("customerType", response.getCustomerType())
                .field("serviceCode", service.getServiceCode())
                .emit();

        return response;
    }

    private void dispatchPostIssueSideEffects(
            String tenantCode,
            String serviceId,
            LocalDate workDate,
            TenantQueueTicket issuedTicket,
            String ticketCode
    ) {
        String traceId = MDC.get(LogContract.MDC_TRACE_ID);
        Runnable task = () -> executePostIssueSideEffectsWithContext(tenantCode, traceId, serviceId, workDate, issuedTicket);
        if (TransactionSynchronizationManager.isActualTransactionActive()
                && TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    submitPostCommit(task, tenantCode, ticketCode);
                }
            });
            return;
        }
        submitPostCommit(task, tenantCode, ticketCode);
    }

    private void submitPostCommit(Runnable task, String tenantCode, String ticketCode) {
        if (postCommitExecutor != null) {
            try {
                postCommitExecutor.execute(task);
            } catch (Exception ex) {
                LogContract.warn(log, LogDomain.TICKET, "post_commit_fanout_submit")
                        .outcome(LogOutcome.DEGRADED)
                        .tenant(tenantCode)
                        .entity("ticket", ticketCode)
                        .cause(ex)
                        .emit();
            }
            return;
        }
        task.run();
    }

    private void executePostIssueSideEffectsWithContext(
            String tenantCode,
            String traceId,
            String serviceId,
            LocalDate workDate,
            TenantQueueTicket issuedTicket
    ) {
        String previousTenant = TenantContextHolder.getTenantCode();
        String previousTrace = MDC.get(LogContract.MDC_TRACE_ID);
        try {
            if (tenantCode != null && !tenantCode.isBlank()) {
                TenantContextHolder.setTenantCode(tenantCode);
            }
            if (traceId != null && !traceId.isBlank()) {
                LogContract.bindTraceId(traceId);
            }
            if (dashboardAnalyticsSyncService != null && issuedTicket != null) {
                try {
                    dashboardAnalyticsSyncService.onTicketIssued(tenantCode, issuedTicket);
                } catch (Exception ex) {
                    LogContract.warn(log, LogDomain.TICKET, "dashboard_analytics_hook")
                            .outcome(LogOutcome.DEGRADED)
                            .tenant(tenantCode)
                            .entity("ticket", issuedTicket.getTicketCode())
                            .cause(ex)
                            .emit();
                }
            }
            if (cacheService != null) {
                try {
                    long todayTotal = ticketRepository.countByWorkDate(workDate);
                    cacheService.setCachedCount(ValkeyKeyConstant.tenantTodayTicketsKey(tenantCode), todayTotal, Duration.ofSeconds(60));
                } catch (Exception ex) {
                    LogContract.warn(log, LogDomain.TICKET, "telemetry_cache_refresh")
                            .outcome(LogOutcome.DEGRADED)
                            .tenant(tenantCode)
                            .cause(ex)
                            .emit();
                }
            }
            if (sseBroadcaster != null) {
                try {
                    sseBroadcaster.broadcastQueueUpdate(tenantCode, serviceId, workDate, "TICKET_ISSUED");
                } catch (Exception ex) {
                    LogContract.warn(log, LogDomain.TICKET, "ticket_sse_hook")
                            .outcome(LogOutcome.DEGRADED)
                            .tenant(tenantCode)
                            .field("serviceId", serviceId)
                            .cause(ex)
                            .emit();
                }
            }
        } finally {
            if (previousTenant != null) {
                TenantContextHolder.setTenantCode(previousTenant);
            } else {
                TenantContextHolder.clear();
            }
            if (previousTrace != null) {
                LogContract.bindTraceId(previousTrace);
            } else {
                LogContract.clearTraceId();
            }
        }
    }

    @Override
    public TicketResponse getTicketDetails(String ticketIdOrCode) {
        return queryService.getTicketDetails(ticketIdOrCode);
    }

    @Override
    public SseEmitter subscribeToTicketStream(String ticketIdOrCode) {
        return subscribeToTicketStream(ticketIdOrCode, null);
    }

    @Override
    public SseEmitter subscribeToTicketStream(String ticketIdOrCode, String lastEventId) {
        String tenantCode = resolveCurrentTenantCode();
        if (sseBroadcaster == null) {
            SseEmitter emitter = new SseEmitter(0L);
            emitter.complete();
            return emitter;
        }
        return (lastEventId != null && !lastEventId.isBlank())
                ? sseBroadcaster.subscribe(tenantCode, ticketIdOrCode, lastEventId)
                : sseBroadcaster.subscribe(tenantCode, ticketIdOrCode);
    }

    @Override
    public List<TicketResponse> recoverTicketsByPhoneToday(String customerPhone) {
        return queryService.recoverTicketsByPhoneToday(customerPhone);
    }

    @Override
    public List<TicketResponse> getTicketsForDate(LocalDate workDate, String serviceId) {
        return queryService.getTicketsForDate(workDate, serviceId);
    }

    private String resolveTargetServiceId(
            TenantAppointment appointment,
            IssueTicketRequest request,
            TenantReceptionPoint receptionPoint
    ) {
        String appointmentServiceId = appointment != null && appointment.getService() != null
                ? appointment.getService().getId()
                : null;
        String requestedServiceId = request.getServiceId() != null && !request.getServiceId().isBlank()
                ? request.getServiceId().trim()
                : null;
        String scopedServiceId = receptionPoint != null && receptionPoint.getService() != null
                ? receptionPoint.getService().getId()
                : null;

        if (appointmentServiceId != null) {
            if (scopedServiceId != null && !scopedServiceId.equals(appointmentServiceId)) {
                throw new CheckInException(CheckInErrorCode.SERVICE_MISMATCH_WITH_RECEPTION);
            }
            return appointmentServiceId;
        }

        if (scopedServiceId != null) {
            if (requestedServiceId != null && !scopedServiceId.equals(requestedServiceId)) {
                throw new CheckInException(CheckInErrorCode.SERVICE_MISMATCH_WITH_RECEPTION);
            }
            return scopedServiceId;
        }
        return requestedServiceId;
    }
}
