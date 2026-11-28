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
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

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
    public Map<String, TicketResponse> getTicketDetailsBatch(
            String serviceId,
            LocalDate workDate,
            Collection<String> ticketIds
    ) {
        if (ticketIds == null || ticketIds.isEmpty()) {
            return Collections.emptyMap();
        }

        Set<String> cleanIds = new LinkedHashSet<>();
        for (String id : ticketIds) {
            if (id != null && !id.isBlank()) {
                cleanIds.add(id.trim());
            }
        }
        if (cleanIds.isEmpty()) {
            return Collections.emptyMap();
        }

        // Query 1: Fetch all subscribed tickets in bounded IN-clause chunks (1 query for <= 500 tickets)
        List<TenantQueueTicket> fetchedTickets = new ArrayList<>(cleanIds.size());
        List<String> idList = new ArrayList<>(cleanIds);
        final int batchSize = 500;
        for (int start = 0; start < idList.size(); start += batchSize) {
            List<String> chunk = idList.subList(start, Math.min(start + batchSize, idList.size()));
            List<TenantQueueTicket> batch = ticketRepository.findByIdInWithDetails(chunk);
            if (batch != null) {
                fetchedTickets.addAll(batch);
            }
        }
        if (fetchedTickets.isEmpty()) {
            return Collections.emptyMap();
        }

        // Query 2: Fetch active CALLING/SERVING ticket once for the entire service queue
        String currentServingNumber = null;
        if (serviceId != null && workDate != null) {
            List<TenantQueueTicket> activeServing = ticketRepository
                    .findActiveServingOrCallingByServiceAndWorkDate(serviceId, workDate);
            if (activeServing != null && !activeServing.isEmpty()) {
                currentServingNumber = activeServing.get(0).getTicketCode();
            }
        }

        // Query 3 (only if any subscribed ticket is WAITING): Fetch ordered waiting keys once and compute positions in memory
        boolean hasWaitingTicket = fetchedTickets.stream()
                .anyMatch(t -> t.getStatus() == TicketStatus.WAITING && t.getIssuedAt() != null);
        Map<String, Long> peopleAheadByTicketId = Collections.emptyMap();
        if (hasWaitingTicket && serviceId != null && workDate != null) {
            List<Object[]> waitingOrderRows = ticketRepository
                    .findWaitingOrderProjectionsByServiceAndWorkDate(serviceId, workDate);
            peopleAheadByTicketId = computePeopleAheadMap(waitingOrderRows);
        }

        Map<String, TicketResponse> result = new LinkedHashMap<>(fetchedTickets.size());
        for (TenantQueueTicket ticket : fetchedTickets) {
            TenantService service = ticket.getService();
            int standardDuration = (service != null && service.getStandardDuration() != null && service.getStandardDuration() > 0)
                    ? service.getStandardDuration()
                    : 15;

            long peopleAhead = 0L;
            long queuePosition = 0L;
            if (ticket.getStatus() == TicketStatus.WAITING && ticket.getIssuedAt() != null) {
                Long computedAhead = peopleAheadByTicketId.get(ticket.getId());
                if (computedAhead != null) {
                    peopleAhead = computedAhead;
                    queuePosition = peopleAhead + 1;
                }
            }

            long estimatedWaitMinutes = peopleAhead * standardDuration;
            result.put(
                    ticket.getId(),
                    TicketResponse.fromEntity(ticket, currentServingNumber, peopleAhead, queuePosition, estimatedWaitMinutes)
            );
        }
        return result;
    }

    private Map<String, Long> computePeopleAheadMap(List<Object[]> waitingOrderRows) {
        if (waitingOrderRows == null || waitingOrderRows.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, Long> aheadMap = new HashMap<>(waitingOrderRows.size());
        long strictlyAhead = 0L;
        LocalDateTime prevIssuedAt = null;
        Integer prevSequence = null;

        for (int i = 0; i < waitingOrderRows.size(); i++) {
            Object[] row = waitingOrderRows.get(i);
            if (row == null || row.length < 3 || row[0] == null) {
                continue;
            }
            String id = row[0].toString();
            LocalDateTime issuedAt = (LocalDateTime) row[1];
            Integer seq = row[2] instanceof Number num ? num.intValue() : null;

            if (i > 0 && (!Objects.equals(issuedAt, prevIssuedAt) || !Objects.equals(seq, prevSequence))) {
                strictlyAhead = i;
            }
            aheadMap.put(id, strictlyAhead);
            prevIssuedAt = issuedAt;
            prevSequence = seq;
        }
        return aheadMap;
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
