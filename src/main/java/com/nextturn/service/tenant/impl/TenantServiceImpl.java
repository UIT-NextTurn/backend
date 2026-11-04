package com.nextturn.service.tenant.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nextturn.dto.tenant.CreateTenantRequest;
import com.nextturn.dto.tenant.RejectTenantRequest;
import com.nextturn.dto.tenant.TenantMonitoringResponse;
import com.nextturn.dto.tenant.TenantQuotaResponse;
import com.nextturn.dto.tenant.TenantRegistrationRequest;
import com.nextturn.dto.tenant.TenantResponse;
import com.nextturn.dto.tenant.UpdateTenantRequest;
import com.nextturn.dto.tenant.UpdateTenantStatusRequest;
import com.nextturn.entity.CentralTenant;
import com.nextturn.entity.CentralTenantQuota;
import com.nextturn.enums.TenantStatus;
import com.nextturn.exception.tenant.TenantErrorCode;
import com.nextturn.exception.tenant.TenantException;
import com.nextturn.infrastructure.security.keycloak.KeycloakProperties;
import com.nextturn.infrastructure.security.keycloak.KeycloakTokenService;
import com.nextturn.repository.auth.CentralTenantRepository;
import com.nextturn.repository.staff.TenantStaffRepository;
import com.nextturn.repository.tenant.CentralTenantQuotaRepository;
import com.nextturn.service.notification.EmailService;
import com.nextturn.service.BaseService;
import com.nextturn.service.tenant.TenantCacheService;
import com.nextturn.service.tenant.TenantDatabaseProvisioningService;
import com.nextturn.service.tenant.TenantLifecycleService;
import com.nextturn.service.tenant.TenantProvisioningService;
import com.nextturn.service.tenant.TenantService;
import com.nextturn.service.tenant.TenantTelemetryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.util.List;
import java.util.UUID;

/**
 * Primary orchestrator service for the Tenant management module.
 * <p>
 * Coordinates tenant resolution, administrative profile updates (REQ-TNT-02),
 * quota constraints, and search with telemetry decoration (REQ-TNT-04).
 * Delegates specialized concerns to:
 * <ul>
 *     <li>{@link TenantProvisioningService} for facility creation and self-registration onboarding (REQ-TNT-01, REQ-TNT-05)</li>
 *     <li>{@link TenantLifecycleService} for status transitions, suspension, approval/rejection, and soft-delete (REQ-TNT-03, REQ-TNT-06)</li>
 *     <li>{@link TenantTelemetryService} for live tenant staff/ticket counting and historical operational checks (REQ-TNT-04)</li>
 *     <li>{@link TenantCacheService} for Valkey/Redis cache read-through and cache eviction (REQ-TNT-02)</li>
 * </ul>
 */
@Service
public class TenantServiceImpl extends BaseService implements TenantService {

    private static final Logger log = LoggerFactory.getLogger(TenantServiceImpl.class);

    private final CentralTenantRepository centralTenantRepository;
    private final CentralTenantQuotaRepository quotaRepository;
    private final TenantProvisioningService tenantProvisioningService;
    private final TenantLifecycleService tenantLifecycleService;
    private final TenantTelemetryService tenantTelemetryService;
    private final TenantCacheService tenantCacheService;

    @Autowired
    public TenantServiceImpl(
            CentralTenantRepository centralTenantRepository,
            CentralTenantQuotaRepository quotaRepository,
            TenantProvisioningService tenantProvisioningService,
            TenantLifecycleService tenantLifecycleService,
            TenantTelemetryService tenantTelemetryService,
            TenantCacheService tenantCacheService,
            @Autowired(required = false) TransactionTemplate transactionTemplate
    ) {
        super(transactionTemplate);
        this.centralTenantRepository = centralTenantRepository;
        this.quotaRepository = quotaRepository;
        this.tenantProvisioningService = tenantProvisioningService;
        this.tenantLifecycleService = tenantLifecycleService;
        this.tenantTelemetryService = tenantTelemetryService;
        this.tenantCacheService = tenantCacheService;
    }

    /**
     * Backward-compatible constructor for testing and manual wiring.
     */
    public TenantServiceImpl(
            CentralTenantRepository centralTenantRepository,
            CentralTenantQuotaRepository quotaRepository,
            TenantStaffRepository staffRepository,
            KeycloakTokenService keycloakTokenService,
            KeycloakProperties keycloakProperties,
            EmailService emailService,
            StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper,
            TransactionTemplate transactionTemplate,
            DataSource dataSource
    ) {
        super(transactionTemplate);
        this.centralTenantRepository = centralTenantRepository;
        this.quotaRepository = quotaRepository;

        this.tenantCacheService = new TenantCacheServiceImpl(redisTemplate, objectMapper);
        this.tenantTelemetryService = new TenantTelemetryServiceImpl(this.tenantCacheService, staffRepository, dataSource);
        TenantDatabaseProvisioningService dbProvService = dataSource != null ? new TenantDatabaseProvisioningServiceImpl(dataSource) : null;
        this.tenantProvisioningService = new TenantProvisioningServiceImpl(
                centralTenantRepository,
                quotaRepository,
                keycloakTokenService,
                keycloakProperties,
                emailService,
                this.tenantCacheService,
                dbProvService,
                transactionTemplate
        );
        this.tenantLifecycleService = new TenantLifecycleServiceImpl(
                centralTenantRepository,
                quotaRepository,
                keycloakTokenService,
                keycloakProperties,
                emailService,
                this.tenantTelemetryService,
                this.tenantCacheService,
                dbProvService,
                transactionTemplate
        );
    }

    // =========================================================================
    // REQ-TNT-01 & REQ-TNT-05: Provisioning & Self-Registration
    // =========================================================================

    public TenantResponse createTenant(CreateTenantRequest request) {
        return tenantProvisioningService.createTenant(request);
    }

    public TenantResponse registerTenant(TenantRegistrationRequest request) {
        return tenantProvisioningService.registerTenant(request);
    }

    // =========================================================================
    // REQ-TNT-02: Profile & Operational Quota Updates
    // =========================================================================

    public TenantResponse updateTenant(String tenantIdentifier, UpdateTenantRequest request) {
        if (request == null) {
            throw new TenantException(TenantErrorCode.TENANT_NOT_FOUND, "Request body cannot be null");
        }

        CentralTenant tenant = resolveTenant(tenantIdentifier);

        // 1. Tenant must exist and be in ACTIVE status
        if (tenant.getStatus() != TenantStatus.ACTIVE) {
            throw new TenantException(
                    TenantErrorCode.TENANT_NOT_ACTIVE,
                    "Tổ chức không ở trạng thái hoạt động (ACTIVE)."
            );
        }

        // 2. Validate quota numerical constraints
        tenantProvisioningService.validateQuotaInputs(
                request.getMaxStaffs(),
                request.getDailyTicketQuota(),
                request.getMaxServices(),
                request.getMaxAppointments()
        );

        // 3. Check actual active staff count vs new max_staffs quota limit
        long currentActiveStaff = tenantTelemetryService.countActiveStaff(tenant.getTenantCode());
        if (request.getMaxStaffs() != null && currentActiveStaff > request.getMaxStaffs()) {
            throw new TenantException(
                    TenantErrorCode.STAFF_QUOTA_BELOW_CURRENT,
                    "Hạn ngạch nhân sự mới không được nhỏ hơn số lượng nhân viên hiện có của cơ sở."
            );
        }

        // 4. Update administrative information if supplied
        if (request.getTenantName() != null && !request.getTenantName().isBlank()) {
            tenant.setTenantName(request.getTenantName().trim());
        }
        if (request.getPhoneNumber() != null && !request.getPhoneNumber().isBlank()) {
            tenant.setPhoneNumber(request.getPhoneNumber().trim());
        }

        // 5. Update or create operational quota entity
        CentralTenantQuota quota = quotaRepository.findByTenantId(tenant.getId())
                .orElseGet(() -> CentralTenantQuota.builder()
                        .id(UUID.randomUUID().toString())
                        .tenantId(tenant.getId())
                        .maxStaffs(5)
                        .dailyTicketQuota(200)
                        .maxServices(20)
                        .maxAppointments(500)
                        .build());

        if (request.getMaxStaffs() != null) {
            quota.setMaxStaffs(request.getMaxStaffs());
        }
        if (request.getDailyTicketQuota() != null) {
            quota.setDailyTicketQuota(request.getDailyTicketQuota());
        }
        if (request.getMaxServices() != null) {
            quota.setMaxServices(request.getMaxServices());
        }
        if (request.getMaxAppointments() != null) {
            quota.setMaxAppointments(request.getMaxAppointments());
        }

        CentralTenant updatedTenant;
        CentralTenantQuota updatedQuota;
        try {
            record UpdatedTenantAndQuota(CentralTenant tenant, CentralTenantQuota quota) {}
            UpdatedTenantAndQuota saved = executeInTransaction(status -> {
                CentralTenant t = centralTenantRepository.save(tenant);
                CentralTenantQuota q = quotaRepository.save(quota);
                return new UpdatedTenantAndQuota(t, q);
            });
            updatedTenant = saved.tenant();
            updatedQuota = saved.quota();
        } catch (Exception dbEx) {
            log.error("Database save failed during quota update for [{}]: {}", tenant.getTenantCode(), dbEx.getMessage(), dbEx);
            throw dbEx;
        }

        log.info("Successfully updated tenant [{}] profile and quotas (max_staffs: {}, daily_quota: {})",
                tenant.getTenantCode(), updatedQuota.getMaxStaffs(), updatedQuota.getDailyTicketQuota());

        TenantQuotaResponse quotaResponse = TenantQuotaResponse.fromEntity(updatedQuota, currentActiveStaff);
        TenantResponse response = TenantResponse.fromEntity(updatedTenant, quotaResponse);

        tenantCacheService.cacheTenantConfig(response);
        return response;
    }

    // =========================================================================
    // REQ-TNT-03 & REQ-TNT-06: Lifecycle Transitions, Approval, Soft-Delete
    // =========================================================================

    public TenantResponse suspendTenant(String tenantIdentifier) {
        return tenantLifecycleService.suspendTenant(resolveTenant(tenantIdentifier));
    }

    public TenantResponse activateTenant(String tenantIdentifier) {
        return tenantLifecycleService.activateTenant(resolveTenant(tenantIdentifier));
    }

    public TenantResponse updateTenantStatus(String tenantIdentifier, UpdateTenantStatusRequest request) {
        return tenantLifecycleService.updateTenantStatus(resolveTenant(tenantIdentifier), request);
    }

    public void deleteTenant(String tenantIdentifier) {
        tenantLifecycleService.deleteTenant(resolveTenant(tenantIdentifier));
    }

    public TenantResponse approveTenantRegistration(String tenantIdentifier) {
        return tenantLifecycleService.approveTenantRegistration(resolveTenant(tenantIdentifier));
    }

    public TenantResponse rejectTenantRegistration(String tenantIdentifier, RejectTenantRequest request) {
        if (request == null || request.getReason() == null || request.getReason().isBlank()) {
            throw new TenantException(
                    TenantErrorCode.REJECTION_REASON_REQUIRED,
                    "Lý do từ chối hồ sơ đăng ký là bắt buộc."
            );
        }
        return tenantLifecycleService.rejectTenantRegistration(resolveTenant(tenantIdentifier), request);
    }

    // =========================================================================
    // REQ-TNT-04: Search, Filtering & Telemetry Retrieval
    // =========================================================================

    public Page<TenantMonitoringResponse> searchTenants(
            String keyword,
            TenantStatus status,
            int page,
            int limit,
            String sortBy,
            String sortDirection
    ) {
        int sanitizedPage = Math.max(0, page);
        int sanitizedLimit = Math.min(100, Math.max(1, limit));

        String sanitizedSortBy = "createdAt";
        if ("tenantName".equalsIgnoreCase(sortBy) || "tenantCode".equalsIgnoreCase(sortBy) || "status".equalsIgnoreCase(sortBy)) {
            sanitizedSortBy = sortBy;
        }

        Sort.Direction direction = "ASC".equalsIgnoreCase(sortDirection) ? Sort.Direction.ASC : Sort.Direction.DESC;
        Pageable pageable = PageRequest.of(sanitizedPage, sanitizedLimit, Sort.by(direction, sanitizedSortBy));

        String cleanKeyword = (keyword != null && !keyword.isBlank()) ? keyword.trim() : null;

        Page<CentralTenant> tenantPage = centralTenantRepository.searchTenants(cleanKeyword, status, pageable);

        List<TenantMonitoringResponse> items = tenantPage.getContent().stream().map(t -> {
            CentralTenantQuota quota = quotaRepository.findByTenantId(t.getId()).orElse(null);
            long activeStaff = tenantTelemetryService.countActiveStaff(t.getTenantCode());
            long todayTickets = tenantTelemetryService.countTodayTickets(t.getTenantCode());
            TenantQuotaResponse quotaResponse = TenantQuotaResponse.fromEntity(quota, activeStaff);
            return TenantMonitoringResponse.fromEntity(t, quotaResponse, activeStaff, todayTickets);
        }).toList();

        return new PageImpl<>(items, pageable, tenantPage.getTotalElements());
    }

    public long countTodayTickets(String tenantCode) {
        return tenantTelemetryService.countTodayTickets(tenantCode);
    }

    public boolean checkTenantHistoricalData(String tenantCode) {
        return tenantTelemetryService.checkTenantHistoricalData(tenantCode);
    }

    public TenantResponse getTenant(String tenantIdentifier) {
        TenantResponse cached = tenantCacheService.getCachedTenantConfig(tenantIdentifier);
        if (cached != null) {
            log.debug("Valkey cache hit for tenant [{}]", tenantIdentifier);
            return cached;
        }

        CentralTenant tenant = resolveTenant(tenantIdentifier);
        CentralTenantQuota quota = quotaRepository.findByTenantId(tenant.getId()).orElse(null);
        long activeStaff = tenantTelemetryService.countActiveStaff(tenant.getTenantCode());
        TenantQuotaResponse quotaResponse = TenantQuotaResponse.fromEntity(quota, activeStaff);
        TenantResponse response = TenantResponse.fromEntity(tenant, quotaResponse);

        tenantCacheService.cacheTenantConfig(response);
        return response;
    }

    public TenantQuotaResponse getTenantQuota(String tenantIdentifier) {
        TenantResponse cached = tenantCacheService.getCachedTenantConfig(tenantIdentifier);
        if (cached != null && cached.getQuotas() != null) {
            log.debug("Valkey cache hit for tenant quota [{}]", tenantIdentifier);
            return cached.getQuotas();
        }

        CentralTenant tenant = resolveTenant(tenantIdentifier);
        CentralTenantQuota quota = quotaRepository.findByTenantId(tenant.getId())
                .orElseThrow(() -> new TenantException(TenantErrorCode.TENANT_NOT_FOUND, "Quota not found for tenant [" + tenantIdentifier + "]"));
        long activeStaff = tenantTelemetryService.countActiveStaff(tenant.getTenantCode());
        TenantQuotaResponse quotaResponse = TenantQuotaResponse.fromEntity(quota, activeStaff);

        tenantCacheService.cacheTenantConfig(TenantResponse.fromEntity(tenant, quotaResponse));
        return quotaResponse;
    }

    public CentralTenant resolveTenant(String identifier) {
        if (identifier == null || identifier.isBlank()) {
            throw new TenantException(TenantErrorCode.TENANT_NOT_FOUND, "Tenant identifier cannot be blank");
        }
        String cleanId = identifier.trim();
        return centralTenantRepository.findById(cleanId)
                .filter(t -> !Boolean.TRUE.equals(t.getIsDeleted()))
                .or(() -> centralTenantRepository.findByTenantCodeIgnoreCaseAndIsDeletedFalse(cleanId))
                .orElseThrow(() -> new TenantException(TenantErrorCode.TENANT_NOT_FOUND, "Tenant [" + cleanId + "] not found in registry"));
    }
}
