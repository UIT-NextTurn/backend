package com.nextturn.service.calendar.impl;

import com.nextturn.dto.calendar.TimeSlotResponse;
import com.nextturn.entity.TenantTimeSlot;
import com.nextturn.enums.TimeSlotStatus;
import com.nextturn.repository.calendar.TenantTimeSlotRepository;
import com.nextturn.service.BaseService;
import com.nextturn.service.calendar.TenantTimeSlotQueryService;
import com.nextturn.service.tenant.TenantCacheService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Default implementation of {@link TenantTimeSlotQueryService} providing cached queries for booking slots.
 */
@Service
public class TenantTimeSlotQueryServiceImpl extends BaseService implements TenantTimeSlotQueryService {

    private static final Logger log = LoggerFactory.getLogger(TenantTimeSlotQueryServiceImpl.class);

    private final TenantTimeSlotRepository timeSlotRepository;
    private final TenantCacheService tenantCacheService;

    public TenantTimeSlotQueryServiceImpl(
            TenantTimeSlotRepository timeSlotRepository,
            TenantCacheService tenantCacheService,
            TransactionTemplate transactionTemplate
    ) {
        super(transactionTemplate);
        this.timeSlotRepository = timeSlotRepository;
        this.tenantCacheService = tenantCacheService;
    }

    @Override
    @Transactional(readOnly = true)
    public List<TimeSlotResponse> getAvailableSlots(String serviceId, LocalDate workDate) {
        String tenantCode = resolveCurrentTenantCode();

        // Check Valkey cache first
        List<TimeSlotResponse> cached = tenantCacheService.getCachedTenantSlots(tenantCode, serviceId, workDate);
        if (cached != null) {
            log.debug("Cache hit: retrieved {} available slots from Valkey for tenant [{}] service [{}] on [{}]",
                    cached.size(), tenantCode, serviceId, workDate);
            return cached;
        }

        // Cache miss: query database
        log.debug("Cache miss: querying database for available slots for service [{}] on [{}]", serviceId, workDate);
        List<TenantTimeSlot> slots = timeSlotRepository
                .findByServiceIdAndWorkDateAndStatusOrderBySlotStartAsc(serviceId, workDate, TimeSlotStatus.AVAILABLE);

        List<TimeSlotResponse> responses = slots.stream()
                .map(TimeSlotResponse::fromEntity)
                .collect(Collectors.toList());

        // Populate cache
        tenantCacheService.cacheTenantSlots(tenantCode, serviceId, workDate, responses);
        return responses;
    }

    @Override
    @Transactional(readOnly = true)
    public List<TimeSlotResponse> getAllSlotsForDate(String serviceId, LocalDate workDate) {
        List<TenantTimeSlot> slots = timeSlotRepository
                .findByServiceIdAndWorkDateOrderBySlotStartAsc(serviceId, workDate);

        return slots.stream()
                .map(TimeSlotResponse::fromEntity)
                .collect(Collectors.toList());
    }
}

