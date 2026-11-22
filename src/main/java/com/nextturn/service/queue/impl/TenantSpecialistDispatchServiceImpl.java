package com.nextturn.service.queue.impl;

import com.nextturn.dto.queue.SpecialistLoadResponse;
import com.nextturn.entity.TenantService;
import com.nextturn.entity.TenantStaff;
import com.nextturn.exception.queue.QueueErrorCode;
import com.nextturn.exception.queue.QueueException;
import com.nextturn.repository.calendar.TenantShiftRepository;
import com.nextturn.repository.ticket.TenantQueueTicketRepository;
import com.nextturn.service.queue.TenantSpecialistDispatchService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Default implementation of {@link TenantSpecialistDispatchService} checking live specialist availability
 * within active shifts and balancing handoffs across certified specialists (REQ-QC-01, REQ-QC-02).
 */
@Component
public class TenantSpecialistDispatchServiceImpl implements TenantSpecialistDispatchService {

    private static final long MAX_CONCURRENT_SERVING_C_MAX = 1L;

    private final TenantShiftRepository shiftRepository;
    private final TenantQueueTicketRepository ticketRepository;

    @Autowired
    public TenantSpecialistDispatchServiceImpl(
            TenantShiftRepository shiftRepository,
            TenantQueueTicketRepository ticketRepository
    ) {
        this.shiftRepository = shiftRepository;
        this.ticketRepository = ticketRepository;
    }

    @Override
    public void assertSpecialistCapacityAvailable(TenantService service, LocalDate workDate, LocalTime currentTime) {
        if (service == null || shiftRepository == null) {
            return;
        }
        List<TenantStaff> activeSpecialists = shiftRepository.findActiveSpecialistsInShiftForService(
                service.getId(), workDate, currentTime
        );
        if (activeSpecialists.isEmpty()) {
            throw new QueueException(
                    QueueErrorCode.NO_AVAILABLE_SPECIALIST,
                    String.format(QueueErrorCode.NO_AVAILABLE_SPECIALIST.message(), service.getServiceName())
            );
        }

        Map<String, Long> servingCounts = toCountMap(ticketRepository.countServingTicketsGroupedByProvider(workDate));
        boolean hasFreeSpecialist = activeSpecialists.stream()
                .anyMatch(st -> servingCounts.getOrDefault(st.getId(), 0L) < MAX_CONCURRENT_SERVING_C_MAX);

        if (!hasFreeSpecialist) {
            throw new QueueException(
                    QueueErrorCode.NO_AVAILABLE_SPECIALIST,
                    String.format(QueueErrorCode.NO_AVAILABLE_SPECIALIST.message(), service.getServiceName())
            );
        }
    }

    @Override
    public TenantStaff selectOrValidateSpecialist(
            String serviceId,
            String requestedProviderId,
            LocalDate workDate,
            LocalTime currentTime
    ) {
        List<TenantStaff> activeSpecialists = shiftRepository.findActiveSpecialistsInShiftForService(
                serviceId, workDate, currentTime
        );
        Map<String, Long> servingCounts = toCountMap(ticketRepository.countServingTicketsGroupedByProvider(workDate));

        if (requestedProviderId != null && !requestedProviderId.isBlank()) {
            String targetId = requestedProviderId.trim();
            TenantStaff target = activeSpecialists.stream()
                    .filter(st -> st.getId().equals(targetId))
                    .findFirst()
                    .orElseThrow(() -> new QueueException(QueueErrorCode.SPECIALIST_NOT_AVAILABLE));

            if (servingCounts.getOrDefault(target.getId(), 0L) >= MAX_CONCURRENT_SERVING_C_MAX) {
                throw new QueueException(QueueErrorCode.SPECIALIST_NOT_AVAILABLE);
            }
            return target;
        }

        Map<String, Long> handledCounts = toCountMap(ticketRepository.countHandledTicketsGroupedByProvider(workDate));
        return activeSpecialists.stream()
                .filter(st -> servingCounts.getOrDefault(st.getId(), 0L) < MAX_CONCURRENT_SERVING_C_MAX)
                .min(Comparator
                        .comparingLong((TenantStaff st) -> handledCounts.getOrDefault(st.getId(), 0L))
                        .thenComparing(st -> st.getStaffCode() != null ? st.getStaffCode() : ""))
                .orElseThrow(() -> new QueueException(QueueErrorCode.SPECIALIST_NOT_AVAILABLE));
    }

    @Override
    public List<SpecialistLoadResponse> getSpecialistWorkloads(String serviceId, LocalDate workDate, LocalTime currentTime) {
        if (serviceId == null || serviceId.isBlank() || shiftRepository == null) {
            return List.of();
        }
        List<TenantStaff> activeSpecialists = shiftRepository.findActiveSpecialistsInShiftForService(
                serviceId.trim(), workDate, currentTime
        );
        Map<String, Long> servingCounts = toCountMap(ticketRepository.countServingTicketsGroupedByProvider(workDate));
        Map<String, Long> handledCounts = toCountMap(ticketRepository.countHandledTicketsGroupedByProvider(workDate));

        return activeSpecialists.stream()
                .map(st -> {
                    long busy = servingCounts.getOrDefault(st.getId(), 0L);
                    return SpecialistLoadResponse.builder()
                            .staffId(st.getId())
                            .staffCode(st.getStaffCode())
                            .fullName(st.getFullName())
                            .busyServingCount(busy)
                            .servedCountToday(handledCounts.getOrDefault(st.getId(), 0L))
                            .available(busy < MAX_CONCURRENT_SERVING_C_MAX)
                            .build();
                })
                .toList();
    }

    private Map<String, Long> toCountMap(List<Object[]> rows) {
        Map<String, Long> map = new HashMap<>();
        if (rows == null) {
            return map;
        }
        for (Object[] row : rows) {
            if (row != null && row.length >= 2 && row[0] != null && row[1] != null) {
                map.put(row[0].toString(), ((Number) row[1]).longValue());
            }
        }
        return map;
    }
}
