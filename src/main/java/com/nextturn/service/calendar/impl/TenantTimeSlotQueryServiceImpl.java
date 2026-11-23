package com.nextturn.service.calendar.impl;

import com.nextturn.dto.calendar.CalendarSettingsResponse;
import com.nextturn.dto.calendar.TimeSlotResponse;
import com.nextturn.entity.TenantTimeSlot;
import com.nextturn.enums.TimeSlotStatus;
import com.nextturn.repository.calendar.TenantTimeSlotRepository;
import com.nextturn.service.BaseService;
import com.nextturn.service.calendar.TenantCalendarSettingsService;
import com.nextturn.service.calendar.TenantTimeSlotQueryService;
import com.nextturn.service.tenant.TenantCacheService;
import com.nextturn.infrastructure.logging.LogContract;
import com.nextturn.infrastructure.logging.LogDomain;
import com.nextturn.infrastructure.logging.LogOutcome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Default implementation of {@link TenantTimeSlotQueryService} providing cached queries for booking slots
 * and enforcing facility advance booking and lead-time policies (REQ-APT-01).
 */
@Service
public class TenantTimeSlotQueryServiceImpl extends BaseService implements TenantTimeSlotQueryService {

    private static final Logger log = LoggerFactory.getLogger(TenantTimeSlotQueryServiceImpl.class);

    private final TenantTimeSlotRepository timeSlotRepository;
    private final TenantCacheService tenantCacheService;
    private final TenantCalendarSettingsService calendarSettingsService;

    @Autowired
    public TenantTimeSlotQueryServiceImpl(
            TenantTimeSlotRepository timeSlotRepository,
            TenantCacheService tenantCacheService,
            @Autowired(required = false) TenantCalendarSettingsService calendarSettingsService,
            @Autowired(required = false) TransactionTemplate transactionTemplate
    ) {
        super(transactionTemplate);
        this.timeSlotRepository = timeSlotRepository;
        this.tenantCacheService = tenantCacheService;
        this.calendarSettingsService = calendarSettingsService;
    }

    public TenantTimeSlotQueryServiceImpl(
            TenantTimeSlotRepository timeSlotRepository,
            TenantCacheService tenantCacheService,
            TransactionTemplate transactionTemplate
    ) {
        this(timeSlotRepository, tenantCacheService, null, transactionTemplate);
    }

    @Override
    @Transactional(readOnly = true)
    public List<TimeSlotResponse> getAvailableSlots(String serviceId, LocalDate workDate) {
        String tenantCode = resolveCurrentTenantCode();

        // Check Valkey cache first
        List<TimeSlotResponse> responses = tenantCacheService.getCachedTenantSlots(tenantCode, serviceId, workDate);
        if (responses != null) {
            LogContract.debug(log, LogDomain.CALENDAR, "slots_cache_hit")
                    .outcome(LogOutcome.SUCCESS)
                    .tenant(tenantCode)
                    .field("count", responses.size())
                    .field("serviceId", serviceId)
                    .field("workDate", workDate)
                    .emit();
        } else {
            // Cache miss: query database
            LogContract.debug(log, LogDomain.CALENDAR, "slots_cache_miss")
                    .outcome(LogOutcome.SUCCESS)
                    .tenant(tenantCode)
                    .field("serviceId", serviceId)
                    .field("workDate", workDate)
                    .emit();
            List<TenantTimeSlot> slots = timeSlotRepository
                    .findByServiceIdAndWorkDateAndStatusOrderBySlotStartAsc(serviceId, workDate, TimeSlotStatus.AVAILABLE);

            responses = slots.stream()
                    .map(TimeSlotResponse::fromEntity)
                    .collect(Collectors.toList());

            // Populate cache
            tenantCacheService.cacheTenantSlots(tenantCode, serviceId, workDate, responses);
        }

        return applyTenantBookingPolicyFilter(responses, workDate);
    }

    private List<TimeSlotResponse> applyTenantBookingPolicyFilter(List<TimeSlotResponse> slots, LocalDate workDate) {
        if (slots == null || slots.isEmpty() || calendarSettingsService == null || workDate == null) {
            return slots;
        }
        CalendarSettingsResponse settings = calendarSettingsService.getSettings();
        if (settings == null) {
            return slots;
        }

        int minLeadHours = settings.getMinLeadTimeHours() != null ? settings.getMinLeadTimeHours() : 2;
        int maxAdvanceDays = settings.getMaxAdvanceBookingDays() != null ? settings.getMaxAdvanceBookingDays() : 30;

        LocalDate today = LocalDate.now();
        if (workDate.isBefore(today) || workDate.isAfter(today.plusDays(maxAdvanceDays))) {
            return List.of();
        }

        LocalDateTime earliestAllowedStart = LocalDateTime.now().plusHours(minLeadHours);
        return slots.stream()
                .filter(slot -> slot.getSlotStart() == null
                        || !LocalDateTime.of(workDate, slot.getSlotStart()).isBefore(earliestAllowedStart))
                .collect(Collectors.toList());
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

