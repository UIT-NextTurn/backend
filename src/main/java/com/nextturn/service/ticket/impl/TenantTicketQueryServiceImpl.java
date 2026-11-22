package com.nextturn.service.ticket.impl;

import com.nextturn.dto.ticket.TicketResponse;
import com.nextturn.entity.TenantQueueTicket;
import com.nextturn.entity.TenantService;
import com.nextturn.enums.TicketStatus;
import com.nextturn.exception.ticket.TicketErrorCode;
import com.nextturn.exception.ticket.TicketException;
import com.nextturn.repository.ticket.TenantQueueTicketRepository;
import com.nextturn.service.BaseService;
import com.nextturn.service.ticket.TenantTicketQueryService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.util.List;

/**
 * Default implementation of {@link TenantTicketQueryService} computing real-time queue metrics
 * and supporting customer e-ticket recovery (REQ-TICKET-02, REQ-TICKET-04).
 */
@Service
public class TenantTicketQueryServiceImpl extends BaseService implements TenantTicketQueryService {

    private static final List<TicketStatus> ACTIVE_RECOVERY_STATUSES = List.of(
            TicketStatus.WAITING,
            TicketStatus.CALLING,
            TicketStatus.SERVING
    );

    private final TenantQueueTicketRepository ticketRepository;

    @Autowired
    public TenantTicketQueryServiceImpl(
            TenantQueueTicketRepository ticketRepository,
            @Autowired(required = false) TransactionTemplate transactionTemplate
    ) {
        super(transactionTemplate);
        this.ticketRepository = ticketRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public TicketResponse getTicketDetails(String ticketIdOrCode) {
        if (ticketIdOrCode == null || ticketIdOrCode.isBlank()) {
            throw new TicketException(TicketErrorCode.TICKET_NOT_FOUND);
        }
        String cleanId = ticketIdOrCode.trim();
        LocalDate today = LocalDate.now();

        TenantQueueTicket ticket = ticketRepository.findByIdWithDetails(cleanId)
                .or(() -> ticketRepository.findByTicketCodeIgnoreCaseAndWorkDateWithDetails(cleanId, today))
                .orElseThrow(() -> new TicketException(TicketErrorCode.TICKET_NOT_FOUND));

        return enrichWithQueueMetrics(ticket);
    }

    @Override
    @Transactional(readOnly = true)
    public TicketResponse enrichWithQueueMetrics(TenantQueueTicket ticket) {
        if (ticket == null) {
            return null;
        }
        TenantService service = ticket.getService();
        String serviceId = service != null ? service.getId() : null;
        int standardDuration = (service != null && service.getStandardDuration() != null && service.getStandardDuration() > 0)
                ? service.getStandardDuration()
                : 15;

        String currentServingNumber = null;
        long peopleAhead = 0L;
        long queuePosition = 0L;

        if (serviceId != null && ticket.getWorkDate() != null) {
            List<TenantQueueTicket> activeServing = ticketRepository
                    .findActiveServingOrCallingByServiceAndWorkDate(serviceId, ticket.getWorkDate());
            if (activeServing != null && !activeServing.isEmpty()) {
                currentServingNumber = activeServing.get(0).getTicketCode();
            }

            if (ticket.getStatus() == TicketStatus.WAITING && ticket.getIssuedAt() != null) {
                int seqNum = ticket.getSequenceNumber() != null ? ticket.getSequenceNumber() : 0;
                peopleAhead = ticketRepository.countWaitingTicketsAhead(
                        serviceId, ticket.getWorkDate(), ticket.getIssuedAt(), seqNum);
                queuePosition = peopleAhead + 1;
            }
        }

        long estimatedWaitMinutes = peopleAhead * standardDuration;
        return TicketResponse.fromEntity(ticket, currentServingNumber, peopleAhead, queuePosition, estimatedWaitMinutes);
    }

    @Override
    @Transactional(readOnly = true)
    public List<TicketResponse> recoverTicketsByPhoneToday(String customerPhone) {
        if (customerPhone == null || customerPhone.isBlank()) {
            throw new TicketException(TicketErrorCode.NO_ACTIVE_TICKET_TODAY);
        }
        String cleanPhone = customerPhone.trim();
        LocalDate today = LocalDate.now();

        List<TenantQueueTicket> activeTickets = ticketRepository.findActiveByCustomerPhoneAndWorkDate(
                cleanPhone, today, ACTIVE_RECOVERY_STATUSES
        );

        if (activeTickets == null || activeTickets.isEmpty()) {
            throw new TicketException(TicketErrorCode.NO_ACTIVE_TICKET_TODAY);
        }

        return activeTickets.stream()
                .map(this::enrichWithQueueMetrics)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<TicketResponse> getTicketsForDate(LocalDate workDate, String serviceId) {
        LocalDate targetDate = workDate != null ? workDate : LocalDate.now();
        String cleanServiceId = (serviceId != null && !serviceId.isBlank()) ? serviceId.trim() : null;

        return ticketRepository.findByWorkDateAndOptionalServiceWithDetails(targetDate, cleanServiceId)
                .stream()
                .map(this::enrichWithQueueMetrics)
                .toList();
    }
}
