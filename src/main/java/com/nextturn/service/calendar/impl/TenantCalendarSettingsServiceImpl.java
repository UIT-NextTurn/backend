package com.nextturn.service.calendar.impl;

import com.nextturn.dto.calendar.CalendarSettingsRequest;
import com.nextturn.dto.calendar.CalendarSettingsResponse;
import com.nextturn.entity.TenantSettings;
import com.nextturn.repository.calendar.TenantSettingsRepository;
import com.nextturn.service.BaseService;
import com.nextturn.service.calendar.TenantCalendarSettingsService;
import com.nextturn.service.tenant.TenantCacheService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;

/**
 * Default implementation of {@link TenantCalendarSettingsService} managing facility-level
 * advance booking rules, lead times, cancellation deadlines, and Valkey caching.
 */
@Service
public class TenantCalendarSettingsServiceImpl extends BaseService implements TenantCalendarSettingsService {

    private static final Logger log = LoggerFactory.getLogger(TenantCalendarSettingsServiceImpl.class);

    private static final String DEFAULT_SETTINGS_ID = "tenant-settings-default";

    private final TenantSettingsRepository settingsRepository;
    private final TenantCacheService tenantCacheService;

    public TenantCalendarSettingsServiceImpl(
            TenantSettingsRepository settingsRepository,
            TenantCacheService tenantCacheService,
            TransactionTemplate transactionTemplate
    ) {
        super(transactionTemplate);
        this.settingsRepository = settingsRepository;
        this.tenantCacheService = tenantCacheService;
    }

    @Override
    public CalendarSettingsResponse getSettings() {
        String tenantCode = resolveCurrentTenantCode();

        // 1. Try cache first
        CalendarSettingsResponse cached = tenantCacheService.getCachedTenantCalendarSettings(tenantCode);
        if (cached != null) {
            return cached;
        }

        // 2. Fetch from DB or initialize default
        TenantSettings settings = settingsRepository.findFirstByOrderByIdAsc()
                .orElseGet(() -> executeInTransaction(status -> {
                    TenantSettings defaultSettings = TenantSettings.builder()
                            .id(DEFAULT_SETTINGS_ID)
                            .minLeadTimeHours(2)
                            .maxAdvanceBookingDays(30)
                            .cancellationDeadlineHours(12)
                            .updatedAt(LocalDateTime.now())
                            .build();
                    return settingsRepository.save(defaultSettings);
                }));

        CalendarSettingsResponse response = CalendarSettingsResponse.fromEntity(settings);

        // 3. Cache response in Valkey
        tenantCacheService.cacheTenantCalendarSettings(tenantCode, response);

        return response;
    }

    @Override
    public CalendarSettingsResponse updateSettings(CalendarSettingsRequest request) {
        String tenantCode = resolveCurrentTenantCode();

        return executeInTransaction(status -> {
            TenantSettings settings = settingsRepository.findFirstByOrderByIdAsc()
                    .orElseGet(() -> TenantSettings.builder()
                            .id(DEFAULT_SETTINGS_ID)
                            .build());

            settings.setMinLeadTimeHours(request.getMinLeadTimeHours());
            settings.setMaxAdvanceBookingDays(request.getMaxAdvanceBookingDays());
            settings.setCancellationDeadlineHours(request.getCancellationDeadlineHours());
            settings.setUpdatedAt(LocalDateTime.now());

            TenantSettings saved = settingsRepository.save(settings);
            settingsRepository.flush();

            CalendarSettingsResponse response = CalendarSettingsResponse.fromEntity(saved);

            // Update cache in Valkey
            tenantCacheService.cacheTenantCalendarSettings(tenantCode, response);

            log.info("Updated calendar booking settings for tenant [{}]: minLeadTime={}h, maxAdvanceBooking={}d, cancellationDeadline={}h",
                    tenantCode, saved.getMinLeadTimeHours(), saved.getMaxAdvanceBookingDays(), saved.getCancellationDeadlineHours());

            return response;
        });
    }
}

