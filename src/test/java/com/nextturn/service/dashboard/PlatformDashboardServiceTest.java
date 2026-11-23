package com.nextturn.service.dashboard;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nextturn.dto.dashboard.PlatformDashboardOverviewResponse;
import com.nextturn.dto.dashboard.TenantQuotaMonitoringResponse;
import com.nextturn.entity.CentralTenant;
import com.nextturn.entity.CentralTenantQuota;
import com.nextturn.enums.QuotaAlertLevel;
import com.nextturn.enums.QuotaMonitorFilter;
import com.nextturn.enums.TenantStatus;
import com.nextturn.exception.dashboard.DashboardErrorCode;
import com.nextturn.exception.dashboard.DashboardException;
import com.nextturn.repository.auth.CentralTenantRepository;
import com.nextturn.repository.auth.CentralUserRepository;
import com.nextturn.repository.dashboard.DashboardAnalyticsRepository;
import com.nextturn.repository.tenant.CentralTenantQuotaRepository;
import com.nextturn.service.dashboard.impl.PlatformDashboardServiceImpl;
import com.nextturn.service.tenant.TenantTelemetryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PlatformDashboardServiceTest {

    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2026-11-23T08:00:00Z"), ZoneOffset.UTC);

    @Mock
    private CentralTenantRepository centralTenantRepository;
    @Mock
    private CentralTenantQuotaRepository quotaRepository;
    @Mock
    private CentralUserRepository centralUserRepository;
    @Mock
    private TenantTelemetryService telemetryService;

    private PlatformDashboardServiceImpl platformDashboardService;

    @BeforeEach
    void setUp() {
        platformDashboardService = new PlatformDashboardServiceImpl(
                centralTenantRepository,
                quotaRepository,
                centralUserRepository,
                telemetryService,
                (DashboardAnalyticsRepository) null,
                null,
                new ObjectMapper().findAndRegisterModules(),
                FIXED_CLOCK
        );
    }

    @Test
    @DisplayName("REQ-DASHBOARD-05: Computes platform tenant lifecycle counts, user count, and validates cycle parameter")
    void getPlatformOverview_ComputesCountsAndValidatesDays() {
        assertThatThrownBy(() -> platformDashboardService.getPlatformOverview(15))
                .isInstanceOf(DashboardException.class)
                .satisfies(ex -> assertThat(((DashboardException) ex).getCode())
                        .isEqualTo(DashboardErrorCode.INVALID_FILTER_PARAMETER.code()));

        List<CentralTenant> tenants = List.of(
                tenant("t1", "clinic-a", "Clinic A", TenantStatus.ACTIVE),
                tenant("t2", "clinic-b", "Clinic B", TenantStatus.SUSPENDED),
                tenant("t3", "clinic-c", "Clinic C", TenantStatus.PENDING_APPROVAL)
        );
        when(centralTenantRepository.findAll()).thenReturn(tenants);
        when(centralUserRepository.count()).thenReturn(42L);
        when(telemetryService.countTodayTickets("clinic-a")).thenReturn(15L);

        PlatformDashboardOverviewResponse overview = platformDashboardService.getPlatformOverview(7);

        assertThat(overview.getActiveTenants()).isEqualTo(1L);
        assertThat(overview.getSuspendedTenants()).isEqualTo(1L);
        assertThat(overview.getPendingApprovalTenants()).isEqualTo(1L);
        assertThat(overview.getTotalTenants()).isEqualTo(3L);
        assertThat(overview.getTotalActiveUsers()).isEqualTo(42L);
        assertThat(overview.getTotalPlatformTicketsToday()).isEqualTo(15L);
    }

    @Test
    @DisplayName("REQ-DASHBOARD-06: Evaluates UNLIMITED (-1), WARNING (>=85%/90%), CRITICAL (>=100%), pinning, and filtering")
    void getTenantQuotaMonitoring_ClassifiesSortsAndFiltersTenants() {
        CentralTenant tCrit = tenant("1", "t-crit", "Critical Clinic", TenantStatus.ACTIVE);
        CentralTenant tWarn = tenant("2", "t-warn", "Warning Staff Clinic", TenantStatus.ACTIVE);
        CentralTenant tUnl = tenant("3", "t-unl", "Unlimited Enterprise", TenantStatus.ACTIVE);

        when(centralTenantRepository.findAll()).thenReturn(List.of(tUnl, tWarn, tCrit));
        when(quotaRepository.findAll()).thenReturn(List.of(
                CentralTenantQuota.builder().tenantId("1").dailyTicketQuota(100).maxStaffs(10).maxServices(20).build(),
                CentralTenantQuota.builder().tenantId("2").dailyTicketQuota(100).maxStaffs(10).maxServices(20).build(),
                CentralTenantQuota.builder().tenantId("3").dailyTicketQuota(-1).maxStaffs(-1).maxServices(-1).build()
        ));

        when(telemetryService.countTodayTickets("t-crit")).thenReturn(100L); // 100% -> CRITICAL
        when(telemetryService.countActiveStaff("t-crit")).thenReturn(5L);
        when(telemetryService.countTodayTickets("t-warn")).thenReturn(20L);
        when(telemetryService.countActiveStaff("t-warn")).thenReturn(9L);   // 90% -> WARNING
        when(telemetryService.countTodayTickets("t-unl")).thenReturn(500L);
        when(telemetryService.countActiveStaff("t-unl")).thenReturn(30L);

        TenantQuotaMonitoringResponse all = platformDashboardService.getTenantQuotaMonitoring(QuotaMonitorFilter.ALL);
        assertThat(all.getCriticalCount()).isEqualTo(1L);
        assertThat(all.getWarningCount()).isEqualTo(1L);
        assertThat(all.getTenants().get(0).getTenantCode()).isEqualTo("t-crit");
        assertThat(all.getTenants().get(0).isQuotaExceeded()).isTrue();
        assertThat(all.getTenants().get(0).getAlertLevel()).isEqualTo(QuotaAlertLevel.CRITICAL);
        assertThat(all.getTenants().get(1).getAlertLevel()).isEqualTo(QuotaAlertLevel.WARNING);
        assertThat(all.getTenants().get(2).getAlertLevel()).isEqualTo(QuotaAlertLevel.UNLIMITED);

        TenantQuotaMonitoringResponse staffWarn = platformDashboardService.getTenantQuotaMonitoring(QuotaMonitorFilter.STAFF_WARNING);
        assertThat(staffWarn.getTenants()).extracting(TenantQuotaMonitoringResponse.TenantQuotaUsageRow::getTenantCode)
                .containsExactly("t-warn");

        TenantQuotaMonitoringResponse exceededOnly = platformDashboardService.getTenantQuotaMonitoring(QuotaMonitorFilter.TICKET_EXCEEDED);
        assertThat(exceededOnly.getTenants()).extracting(TenantQuotaMonitoringResponse.TenantQuotaUsageRow::getTenantCode)
                .containsExactly("t-crit");
    }

    private CentralTenant tenant(String id, String code, String name, TenantStatus status) {
        return CentralTenant.builder()
                .id(id)
                .tenantCode(code)
                .tenantName(name)
                .status(status)
                .isDeleted(false)
                .build();
    }
}
