package com.nextturn.service.ticket.impl;

import com.nextturn.constant.ValkeyKeyConstant;
import com.nextturn.dto.ticket.IssueTicketRequest;
import com.nextturn.dto.ticket.TicketResponse;
import com.nextturn.entity.TenantAppointment;
import com.nextturn.entity.TenantQueueTicket;
import com.nextturn.entity.TenantService;
import com.nextturn.entity.TenantTicketAuditLog;
import com.nextturn.enums.AppointmentStatus;
import com.nextturn.enums.TicketCustomerType;
import com.nextturn.enums.TicketStatus;
import com.nextturn.exception.ticket.TicketErrorCode;
import com.nextturn.exception.ticket.TicketException;
import com.nextturn.repository.appointment.TenantAppointmentRepository;
import com.nextturn.repository.ticket.TenantQueueTicketRepository;
import com.nextturn.repository.ticket.TenantTicketAuditLogRepository;
import com.nextturn.service.BaseService;
import com.nextturn.service.tenant.TenantCacheService;
import com.nextturn.service.ticket.TenantTicketQueryService;
import com.nextturn.service.ticket.TenantTicketSequenceGenerator;
import com.nextturn.service.ticket.TenantTicketService;
import com.nextturn.service.ticket.TenantTicketSseBroadcaster;
import com.nextturn.service.ticket.TenantTicketValidator;
import com.nextturn.service.ticket.TenantTicketSequenceGenerator.GeneratedTicketNumber;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

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
    }

    @Override
    public TicketResponse issueTicket(IssueTicketRequest request) {
        if (request == null) {
            throw new TicketException(TicketErrorCode.INVALID_TICKET_STATE, "Yêu cầu cấp vé không được để trống.");
        }

        String tenantCode = resolveCurrentTenantCode();
        LocalDate today = LocalDate.now();

        boolean isAppointmentFlow = (request.getAppointmentId() != null && !request.getAppointmentId().isBlank())
                || (request.getQrToken() != null && !request.getQrToken().isBlank());

        TenantAppointment preValidatedAppointment = isAppointmentFlow
                ? validator.validateAppointmentForCheckIn(request, today)
                : null;

        // Idempotent check: if appointment already checked in and has a ticket, return existing ticket
        if (preValidatedAppointment != null && preValidatedAppointment.getStatus() == AppointmentStatus.CHECKED_IN) {
            Optional<TenantQueueTicket> existingTicket = ticketRepository.findByAppointmentIdWithDetails(preValidatedAppointment.getId());
            if (existingTicket.isPresent()) {
                log.info("Idempotent check-in: returning existing ticket [{}] for appointment [{}]",
                        existingTicket.get().getTicketCode(), preValidatedAppointment.getAppointmentCode());
                return queryService.enrichWithQueueMetrics(existingTicket.get());
            }
        }

        String targetServiceId = preValidatedAppointment != null && preValidatedAppointment.getService() != null
                ? preValidatedAppointment.getService().getId()
                : request.getServiceId();

        TenantService service = validator.validateActiveService(targetServiceId);
        validator.validateTenantDailyTicketQuota(tenantCode, today);

        if (!isAppointmentFlow) {
            validator.validateWalkInEligibility(service, request.getCustomerPhone(), today);
        }

        // Acquire Valkey Distributed Mutex Lock for (tenantCode, serviceId, today) to prevent Race Condition
        String lockOwnerToken = sequenceGenerator.acquireTicketLock(tenantCode, service.getId(), today);

        TenantQueueTicket savedTicket;
        try {
            savedTicket = executeInTransaction(status -> {
                // Re-check idempotency / duplicate inside lock & transaction
                if (preValidatedAppointment != null) {
                    Optional<TenantQueueTicket> existingInsideLock =
                            ticketRepository.findByAppointmentIdWithDetails(preValidatedAppointment.getId());
                    if (existingInsideLock.isPresent()) {
                        return existingInsideLock.get();
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

                return persisted;
            });
        } finally {
            sequenceGenerator.releaseTicketLock(tenantCode, service.getId(), today, lockOwnerToken);
        }

        // Update daily ticket telemetry cache for tenant monitoring
        if (cacheService != null) {
            try {
                long todayTotal = ticketRepository.countByWorkDate(today);
                cacheService.setCachedCount(ValkeyKeyConstant.tenantTodayTicketsKey(tenantCode), todayTotal, Duration.ofSeconds(60));
            } catch (Exception ex) {
                log.trace("Non-fatal: Failed to refresh daily ticket telemetry cache: {}", ex.getMessage());
            }
        }

        // Broadcast live queue update to waiting SSE subscribers
        if (sseBroadcaster != null) {
            sseBroadcaster.broadcastQueueUpdate(tenantCode, service.getId(), today, "TICKET_ISSUED");
        }

        log.info("Issued queue ticket [{}] (seq #{}, type: {}) for service [{}] in tenant [{}]",
                savedTicket.getTicketCode(), savedTicket.getSequenceNumber(), savedTicket.getCustomerType(),
                service.getServiceCode(), tenantCode);

        return queryService.enrichWithQueueMetrics(savedTicket);
    }

    @Override
    public TicketResponse getTicketDetails(String ticketIdOrCode) {
        return queryService.getTicketDetails(ticketIdOrCode);
    }

    @Override
    public SseEmitter subscribeToTicketStream(String ticketIdOrCode) {
        String tenantCode = resolveCurrentTenantCode();
        if (sseBroadcaster == null) {
            SseEmitter emitter = new SseEmitter(0L);
            emitter.complete();
            return emitter;
        }
        return sseBroadcaster.subscribe(tenantCode, ticketIdOrCode);
    }

    @Override
    public List<TicketResponse> recoverTicketsByPhoneToday(String customerPhone) {
        return queryService.recoverTicketsByPhoneToday(customerPhone);
    }

    @Override
    public List<TicketResponse> getTicketsForDate(LocalDate workDate, String serviceId) {
        return queryService.getTicketsForDate(workDate, serviceId);
    }
}
