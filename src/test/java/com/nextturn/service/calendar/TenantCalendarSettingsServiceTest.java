package com.nextturn.service.calendar;

import com.nextturn.dto.calendar.CalendarSettingsRequest;
import com.nextturn.dto.calendar.CalendarSettingsResponse;
import com.nextturn.entity.TenantSettings;
import com.nextturn.infrastructure.multitenancy.TenantContextHolder;
import com.nextturn.repository.calendar.TenantSettingsRepository;
import com.nextturn.service.calendar.impl.TenantCalendarSettingsServiceImpl;
import com.nextturn.service.tenant.TenantCacheService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TenantCalendarSettingsServiceTest {

    private static final String TENANT_CODE = "tenant-alpha";

    @Mock
    private TenantSettingsRepository settingsRepository;

    @Mock
    private TenantCacheService tenantCacheService;

    @InjectMocks
    private TenantCalendarSettingsServiceImpl settingsService;

    private TenantSettings existingSettings;

    @BeforeEach
    void setUp() {
        TenantContextHolder.setTenantCode(TENANT_CODE);

        existingSettings = TenantSettings.builder()
                .id("settings-1")
                .minLeadTimeHours(4)
                .maxAdvanceBookingDays(14)
                .cancellationDeadlineHours(6)
                .updatedAt(LocalDateTime.now())
                .build();
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
    }

    @Test
    @DisplayName("getSettings: returns cached settings when cache hit occurs")
    void getSettings_CacheHit() {
        CalendarSettingsResponse cached = CalendarSettingsResponse.builder()
                .id("settings-cached")
                .minLeadTimeHours(3)
                .maxAdvanceBookingDays(21)
                .cancellationDeadlineHours(8)
                .build();

        when(tenantCacheService.getCachedTenantCalendarSettings(TENANT_CODE)).thenReturn(cached);

        CalendarSettingsResponse result = settingsService.getSettings();

        assertThat(result).isNotNull();
        assertThat(result.getMinLeadTimeHours()).isEqualTo(3);
        assertThat(result.getMaxAdvanceBookingDays()).isEqualTo(21);
        verify(settingsRepository, never()).findFirstByOrderByIdAsc();
    }

    @Test
    @DisplayName("getSettings: queries database and caches to Valkey when cache miss occurs")
    void getSettings_CacheMiss_DBHit() {
        when(tenantCacheService.getCachedTenantCalendarSettings(TENANT_CODE)).thenReturn(null);
        when(settingsRepository.findFirstByOrderByIdAsc()).thenReturn(Optional.of(existingSettings));

        CalendarSettingsResponse result = settingsService.getSettings();

        assertThat(result).isNotNull();
        assertThat(result.getId()).isEqualTo("settings-1");
        assertThat(result.getMinLeadTimeHours()).isEqualTo(4);
        assertThat(result.getMaxAdvanceBookingDays()).isEqualTo(14);
        assertThat(result.getCancellationDeadlineHours()).isEqualTo(6);

        verify(tenantCacheService).cacheTenantCalendarSettings(eq(TENANT_CODE), any(CalendarSettingsResponse.class));
    }

    @Test
    @DisplayName("getSettings: initializes default settings when database has no records")
    void getSettings_DBEmpty_CreatesDefault() {
        when(tenantCacheService.getCachedTenantCalendarSettings(TENANT_CODE)).thenReturn(null);
        when(settingsRepository.findFirstByOrderByIdAsc()).thenReturn(Optional.empty());
        when(settingsRepository.save(any(TenantSettings.class))).thenAnswer(i -> i.getArgument(0));

        CalendarSettingsResponse result = settingsService.getSettings();

        assertThat(result).isNotNull();
        assertThat(result.getMinLeadTimeHours()).isEqualTo(2);
        assertThat(result.getMaxAdvanceBookingDays()).isEqualTo(30);
        assertThat(result.getCancellationDeadlineHours()).isEqualTo(12);

        verify(settingsRepository).save(any(TenantSettings.class));
        verify(tenantCacheService).cacheTenantCalendarSettings(eq(TENANT_CODE), any(CalendarSettingsResponse.class));
    }

    @Test
    @DisplayName("updateSettings: updates values in database and synchronizes Valkey cache")
    void updateSettings_Success() {
        CalendarSettingsRequest request = CalendarSettingsRequest.builder()
                .minLeadTimeHours(1)
                .maxAdvanceBookingDays(60)
                .cancellationDeadlineHours(24)
                .build();

        when(settingsRepository.findFirstByOrderByIdAsc()).thenReturn(Optional.of(existingSettings));
        when(settingsRepository.save(any(TenantSettings.class))).thenAnswer(i -> i.getArgument(0));

        CalendarSettingsResponse result = settingsService.updateSettings(request);

        assertThat(result).isNotNull();
        assertThat(result.getMinLeadTimeHours()).isEqualTo(1);
        assertThat(result.getMaxAdvanceBookingDays()).isEqualTo(60);
        assertThat(result.getCancellationDeadlineHours()).isEqualTo(24);

        assertThat(existingSettings.getMinLeadTimeHours()).isEqualTo(1);
        assertThat(existingSettings.getMaxAdvanceBookingDays()).isEqualTo(60);
        assertThat(existingSettings.getCancellationDeadlineHours()).isEqualTo(24);

        verify(settingsRepository).save(existingSettings);
        verify(tenantCacheService).cacheTenantCalendarSettings(eq(TENANT_CODE), any(CalendarSettingsResponse.class));
    }
}

