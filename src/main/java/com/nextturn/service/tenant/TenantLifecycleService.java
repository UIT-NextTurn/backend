package com.nextturn.service.tenant;

import com.nextturn.dto.tenant.RejectTenantRequest;
import com.nextturn.dto.tenant.TenantQuotaResponse;
import com.nextturn.dto.tenant.TenantResponse;
import com.nextturn.dto.tenant.UpdateTenantStatusRequest;
import com.nextturn.entity.CentralTenant;
import com.nextturn.entity.CentralTenantQuota;
import com.nextturn.enums.TenantStatus;
import com.nextturn.exception.tenant.TenantErrorCode;
import com.nextturn.exception.tenant.TenantException;
import com.nextturn.infrastructure.security.keycloak.KeycloakProperties;
import com.nextturn.infrastructure.security.keycloak.KeycloakTokenService;
import com.nextturn.repository.auth.CentralTenantRepository;
import com.nextturn.repository.tenant.CentralTenantQuotaRepository;
import com.nextturn.service.notification.EmailService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Service managing tenant lifecycle state transitions (SUSPENDED, ACTIVE, REJECTED),
 * registration approval/rejection workflows, and safe soft-deletion (REQ-TNT-03, REQ-TNT-06).
 */
@Service
public class TenantLifecycleService {

    private static final Logger log = LoggerFactory.getLogger(TenantLifecycleService.class);

    private final CentralTenantRepository centralTenantRepository;
    private final CentralTenantQuotaRepository quotaRepository;
    private final KeycloakTokenService keycloakTokenService;
    private final KeycloakProperties keycloakProperties;
    private final EmailService emailService;
    private final TenantTelemetryService telemetryService;
    private final TenantCacheService cacheService;
    private final TransactionTemplate transactionTemplate;

    @Autowired
    public TenantLifecycleService(
            CentralTenantRepository centralTenantRepository,
            CentralTenantQuotaRepository quotaRepository,
            KeycloakTokenService keycloakTokenService,
            @Autowired(required = false) KeycloakProperties keycloakProperties,
            EmailService emailService,
            TenantTelemetryService telemetryService,
            TenantCacheService cacheService,
            @Autowired(required = false) TransactionTemplate transactionTemplate
    ) {
        this.centralTenantRepository = centralTenantRepository;
        this.quotaRepository = quotaRepository;
        this.keycloakTokenService = keycloakTokenService;
        this.keycloakProperties = keycloakProperties;
        this.emailService = emailService;
        this.telemetryService = telemetryService;
        this.cacheService = cacheService;
        this.transactionTemplate = transactionTemplate;
    }

    /**
     * Suspends tenant operations and locks all associated Keycloak staff accounts (REQ-TNT-03).
     */
    public TenantResponse suspendTenant(CentralTenant tenant) {
        TenantStatus previousStatus = tenant.getStatus();
        if (previousStatus == TenantStatus.SUSPENDED) {
            throw new TenantException(
                    TenantErrorCode.TENANT_ALREADY_SUSPENDED,
                    "Tổ chức đã ở trạng thái tạm ngừng (SUSPENDED)."
            );
        }
        if (previousStatus != TenantStatus.ACTIVE) {
            throw new TenantException(
                    TenantErrorCode.TENANT_NOT_ACTIVE,
                    "Chỉ có thể tạm ngừng tổ chức đang ở trạng thái hoạt động (ACTIVE)."
            );
        }

        tenant.setStatus(TenantStatus.SUSPENDED);
        CentralTenant updatedTenant = executeInTransaction(status -> centralTenantRepository.save(tenant));

        // Synchronize with Keycloak: disable organization and members
        if (updatedTenant.getOrganisationId() != null) {
            try {
                keycloakTokenService.setOrganizationAndMembersActive(updatedTenant.getOrganisationId(), false);
            } catch (Exception kcEx) {
                log.error("Failed to suspend Keycloak organization [{}] for tenant [{}]. Rolling back database: {}",
                        updatedTenant.getOrganisationId(), updatedTenant.getTenantCode(), kcEx.getMessage());
                tenant.setStatus(previousStatus);
                executeInTransaction(status -> centralTenantRepository.save(tenant));
                throw new TenantException(
                        TenantErrorCode.AUTH_SERVICE_SYNC_FAILED,
                        "Quá trình khóa tài khoản Keycloak thất bại: " + kcEx.getMessage()
                );
            }
        }

        cacheService.evictTenantConfigCache(updatedTenant.getId(), updatedTenant.getTenantCode());

        log.info("Successfully suspended tenant [{}] (ID: [{}])", updatedTenant.getTenantCode(), updatedTenant.getId());

        CentralTenantQuota quota = quotaRepository.findByTenantId(updatedTenant.getId()).orElse(null);
        long activeStaff = telemetryService.countActiveStaff(updatedTenant.getTenantCode());
        TenantQuotaResponse quotaResponse = TenantQuotaResponse.fromEntity(quota, activeStaff);
        return TenantResponse.fromEntity(updatedTenant, quotaResponse);
    }

    /**
     * Reactivates a suspended or inactive tenant facility and restores user access (REQ-TNT-03).
     */
    public TenantResponse activateTenant(CentralTenant tenant) {
        TenantStatus previousStatus = tenant.getStatus();
        if (previousStatus == TenantStatus.ACTIVE) {
            throw new TenantException(
                    TenantErrorCode.TENANT_ALREADY_ACTIVE,
                    "Tổ chức đã ở trạng thái hoạt động (ACTIVE)."
            );
        }
        if (previousStatus != TenantStatus.SUSPENDED) {
            throw new TenantException(
                    TenantErrorCode.TENANT_NOT_SUSPENDED,
                    "Chỉ có thể kích hoạt lại tổ chức đang ở trạng thái tạm ngừng (SUSPENDED)."
            );
        }

        tenant.setStatus(TenantStatus.ACTIVE);
        CentralTenant updatedTenant = executeInTransaction(status -> centralTenantRepository.save(tenant));

        // Synchronize with Keycloak: enable organization and members
        if (updatedTenant.getOrganisationId() != null) {
            try {
                keycloakTokenService.setOrganizationAndMembersActive(updatedTenant.getOrganisationId(), true);
            } catch (Exception kcEx) {
                log.error("Failed to reactivate Keycloak organization [{}] for tenant [{}]. Rolling back database: {}",
                        updatedTenant.getOrganisationId(), updatedTenant.getTenantCode(), kcEx.getMessage());
                tenant.setStatus(previousStatus);
                executeInTransaction(status -> centralTenantRepository.save(tenant));
                throw new TenantException(
                        TenantErrorCode.AUTH_SERVICE_SYNC_FAILED,
                        "Quá trình kích hoạt tài khoản Keycloak thất bại: " + kcEx.getMessage()
                );
            }
        }

        cacheService.evictTenantConfigCache(updatedTenant.getId(), updatedTenant.getTenantCode());

        log.info("Successfully activated tenant [{}] (ID: [{}])", updatedTenant.getTenantCode(), updatedTenant.getId());

        CentralTenantQuota quota = quotaRepository.findByTenantId(updatedTenant.getId()).orElse(null);
        long activeStaff = telemetryService.countActiveStaff(updatedTenant.getTenantCode());
        TenantQuotaResponse quotaResponse = TenantQuotaResponse.fromEntity(quota, activeStaff);
        return TenantResponse.fromEntity(updatedTenant, quotaResponse);
    }

    /**
     * Updates tenant lifecycle status dynamically (REQ-TNT-03).
     */
    public TenantResponse updateTenantStatus(CentralTenant tenant, UpdateTenantStatusRequest request) {
        if (request == null || request.getStatus() == null) {
            throw new TenantException(TenantErrorCode.TENANT_NOT_FOUND, "Trạng thái mục tiêu không được để trống");
        }

        TenantStatus newStatus = request.getStatus();
        if (newStatus == TenantStatus.SUSPENDED) {
            return suspendTenant(tenant);
        } else if (newStatus == TenantStatus.ACTIVE) {
            return activateTenant(tenant);
        } else {
            throw new TenantException(
                    TenantErrorCode.TENANT_NOT_ACTIVE,
                    "Chỉ hỗ trợ chuyển đổi giữa trạng thái ACTIVE và SUSPENDED"
            );
        }
    }

    /**
     * Approves a pending organization registration application (REQ-TNT-06).
     */
    public TenantResponse approveTenantRegistration(CentralTenant tenant) {
        if (tenant.getStatus() != TenantStatus.PENDING_APPROVAL) {
            throw new TenantException(
                    TenantErrorCode.TENANT_NOT_PENDING_APPROVAL,
                    "Chỉ có thể phê duyệt tổ chức đang ở trạng thái chờ phê duyệt (PENDING_APPROVAL)."
            );
        }

        tenant.setStatus(TenantStatus.ACTIVE);
        CentralTenant savedTenant = executeInTransaction(status -> centralTenantRepository.save(tenant));

        // Activate Keycloak Organization & Members (REQ-TNT-06 Step 4)
        if (savedTenant.getOrganisationId() != null) {
            try {
                keycloakTokenService.setOrganizationAndMembersActive(savedTenant.getOrganisationId(), true);
            } catch (Exception kcEx) {
                log.error("Failed to activate Keycloak organization [{}] during approval: {}. Rolling back DB status.",
                        savedTenant.getOrganisationId(), kcEx.getMessage());
                tenant.setStatus(TenantStatus.PENDING_APPROVAL);
                executeInTransaction(status -> centralTenantRepository.save(tenant));
                throw new TenantException(
                        TenantErrorCode.AUTH_SERVICE_SYNC_FAILED,
                        "Kích hoạt tổ chức trên Keycloak thất bại: " + kcEx.getMessage()
                );
            }
        }

        cacheService.evictTenantConfigCache(savedTenant.getId(), savedTenant.getTenantCode());

        // Send congratulatory approval email (REQ-TNT-06 Step 5)
        String activationLink = resolveActivationLink(savedTenant.getTenantCode(), savedTenant.getAdminEmail());
        try {
            emailService.sendTenantRegistrationApprovedEmail(
                    savedTenant.getAdminEmail(),
                    savedTenant.getTenantName(),
                    savedTenant.getTenantCode(),
                    activationLink
            );
        } catch (Exception emailEx) {
            log.warn("Non-fatal: Failed to send tenant approval email to [{}]: {}", savedTenant.getAdminEmail(), emailEx.getMessage());
        }

        log.info("Successfully approved tenant registration for [{}] (ID: [{}])", savedTenant.getTenantCode(), savedTenant.getId());

        CentralTenantQuota quota = quotaRepository.findByTenantId(savedTenant.getId()).orElse(null);
        long activeStaff = telemetryService.countActiveStaff(savedTenant.getTenantCode());
        TenantQuotaResponse quotaResponse = TenantQuotaResponse.fromEntity(quota, activeStaff);
        return TenantResponse.fromEntity(savedTenant, quotaResponse);
    }

    /**
     * Rejects a pending organization registration application with a mandatory reason (REQ-TNT-06).
     */
    public TenantResponse rejectTenantRegistration(CentralTenant tenant, RejectTenantRequest request) {
        if (request == null || request.getReason() == null || request.getReason().isBlank()) {
            throw new TenantException(
                    TenantErrorCode.REJECTION_REASON_REQUIRED,
                    "Lý do từ chối hồ sơ đăng ký là bắt buộc."
            );
        }

        if (tenant.getStatus() != TenantStatus.PENDING_APPROVAL) {
            throw new TenantException(
                    TenantErrorCode.TENANT_NOT_PENDING_APPROVAL,
                    "Chỉ có thể từ chối tổ chức đang ở trạng thái chờ phê duyệt (PENDING_APPROVAL)."
            );
        }

        String reason = request.getReason().trim();
        tenant.setStatus(TenantStatus.REJECTED);
        tenant.setRejectionReason(reason);
        CentralTenant savedTenant = executeInTransaction(status -> centralTenantRepository.save(tenant));

        // Delete temporary organization and user in Keycloak (REQ-TNT-06 Step 3)
        if (savedTenant.getOrganisationId() != null) {
            try {
                keycloakTokenService.deleteOrganizationInKeycloak(savedTenant.getOrganisationId());
            } catch (Exception kcEx) {
                log.warn("Non-fatal: Failed to delete Keycloak staging organization [{}] during rejection: {}",
                        savedTenant.getOrganisationId(), kcEx.getMessage());
            }
        }

        cacheService.evictTenantConfigCache(savedTenant.getId(), savedTenant.getTenantCode());

        // Send rejection notification email with reason (REQ-TNT-06 Step 4)
        try {
            emailService.sendTenantRegistrationRejectedEmail(
                    savedTenant.getAdminEmail(),
                    savedTenant.getTenantName(),
                    savedTenant.getTenantCode(),
                    reason
            );
        } catch (Exception emailEx) {
            log.warn("Non-fatal: Failed to send tenant rejection email to [{}]: {}", savedTenant.getAdminEmail(), emailEx.getMessage());
        }

        log.info("Successfully rejected tenant registration for [{}] with reason: {}", savedTenant.getTenantCode(), reason);

        CentralTenantQuota quota = quotaRepository.findByTenantId(savedTenant.getId()).orElse(null);
        TenantQuotaResponse quotaResponse = TenantQuotaResponse.fromEntity(quota, 0L);
        return TenantResponse.fromEntity(savedTenant, quotaResponse);
    }

    /**
     * Safely soft-deletes a tenant facility, preventing hard deletion if historical data exists (REQ-TNT-03).
     */
    public void deleteTenant(CentralTenant tenant) {
        // Check if organization has generated historical tickets, appointments, or reviews
        if (telemetryService.checkTenantHistoricalData(tenant.getTenantCode())) {
            throw new TenantException(
                    TenantErrorCode.TENANT_CANNOT_HARD_DELETE,
                    "Hệ thống tuyệt đối từ chối thao tác xóa vật lý bản ghi tổ chức đã phát sinh dữ liệu lịch sử vé, lịch hẹn hoặc đánh giá của khách hàng."
            );
        }

        // Soft deletion: marks isDeleted = true and suspends operations
        TenantStatus previousStatus = tenant.getStatus();
        tenant.setIsDeleted(true);
        tenant.setStatus(TenantStatus.SUSPENDED);
        executeInTransaction(status -> centralTenantRepository.save(tenant));

        // Invalidate Keycloak organization access
        if (tenant.getOrganisationId() != null) {
            try {
                keycloakTokenService.setOrganizationAndMembersActive(tenant.getOrganisationId(), false);
            } catch (Exception kcEx) {
                log.error("Failed to disable Keycloak organization [{}] during tenant soft-deletion. Compensating database record for [{}]: {}",
                        tenant.getOrganisationId(), tenant.getTenantCode(), kcEx.getMessage());
                executeInTransaction(status -> {
                    tenant.setIsDeleted(false);
                    tenant.setStatus(previousStatus);
                    return centralTenantRepository.save(tenant);
                });
                throw new TenantException(
                        TenantErrorCode.AUTH_SERVICE_SYNC_FAILED,
                        "Quá trình vô hiệu hóa tài khoản trên Keycloak thất bại. Đã khôi phục dữ liệu ban đầu."
                );
            }
        }

        cacheService.evictTenantConfigCache(tenant.getId(), tenant.getTenantCode());
        log.info("Successfully soft-deleted tenant [{}]", tenant.getTenantCode());
    }

    private String resolveActivationLink(String tenantCode, String adminEmail) {
        if (keycloakProperties != null && keycloakProperties.authServerUrl() != null && !keycloakProperties.authServerUrl().isBlank()) {
            return keycloakProperties.authServerUrl() + "/realms/" + keycloakProperties.realm() + "/account";
        }
        return "http://localhost:8180/realms/nextturn/account";
    }

    private <T> T executeInTransaction(TransactionCallback<T> action) {
        if (transactionTemplate != null) {
            return transactionTemplate.execute(action);
        }
        return action.doInTransaction(null);
    }
}
