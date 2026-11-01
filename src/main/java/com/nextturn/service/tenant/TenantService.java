package com.nextturn.service.tenant;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nextturn.constant.ValkeyKeyConstant;
import com.nextturn.dto.tenant.CreateTenantRequest;
import com.nextturn.dto.tenant.RejectTenantRequest;
import com.nextturn.dto.tenant.TenantMonitoringResponse;
import com.nextturn.dto.tenant.TenantQuotaDto;
import com.nextturn.dto.tenant.TenantQuotaResponse;
import com.nextturn.dto.tenant.TenantRegistrationRequest;
import com.nextturn.dto.tenant.TenantResponse;
import com.nextturn.dto.tenant.UpdateTenantRequest;
import com.nextturn.dto.tenant.UpdateTenantStatusRequest;
import com.nextturn.entity.CentralTenant;
import com.nextturn.entity.CentralTenantQuota;
import com.nextturn.enums.StaffRole;
import com.nextturn.enums.StaffStatus;
import com.nextturn.enums.TenantStatus;
import com.nextturn.exception.tenant.TenantErrorCode;
import com.nextturn.exception.tenant.TenantException;
import com.nextturn.infrastructure.multitenancy.TenantContextHolder;
import com.nextturn.infrastructure.security.keycloak.KeycloakProperties;
import com.nextturn.infrastructure.security.keycloak.KeycloakTokenService;
import com.nextturn.repository.auth.CentralTenantRepository;
import com.nextturn.repository.auth.TenantStaffRepository;
import com.nextturn.repository.tenant.CentralTenantQuotaRepository;
import com.nextturn.service.notification.EmailService;
import com.nextturn.util.EmailValidationUtil;
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
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Service orchestrating tenant lifecycle, onboarding provisioning (REQ-TNT-01),
 * administrative updates, operational quota management (REQ-TNT-02),
 * lifecycle state suspension and reactivation (REQ-TNT-03),
 * and global tenant search, filtering, and telemetry monitoring (REQ-TNT-04).
 */
@Service
public class TenantService {

    private static final Logger log = LoggerFactory.getLogger(TenantService.class);

    private final CentralTenantRepository centralTenantRepository;
    private final CentralTenantQuotaRepository quotaRepository;
    private final TenantStaffRepository staffRepository;
    private final KeycloakTokenService keycloakTokenService;
    private final KeycloakProperties keycloakProperties;
    private final EmailService emailService;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;
    private final DataSource dataSource;

    @Autowired
    public TenantService(
            CentralTenantRepository centralTenantRepository,
            CentralTenantQuotaRepository quotaRepository,
            @Autowired(required = false) TenantStaffRepository staffRepository,
            KeycloakTokenService keycloakTokenService,
            KeycloakProperties keycloakProperties,
            EmailService emailService,
            @Autowired(required = false) StringRedisTemplate redisTemplate,
            @Autowired(required = false) ObjectMapper objectMapper,
            @Autowired(required = false) TransactionTemplate transactionTemplate,
            @Autowired(required = false) DataSource dataSource
    ) {
        this.centralTenantRepository = centralTenantRepository;
        this.quotaRepository = quotaRepository;
        this.staffRepository = staffRepository;
        this.keycloakTokenService = keycloakTokenService;
        this.keycloakProperties = keycloakProperties;
        this.emailService = emailService;
        this.redisTemplate = redisTemplate;
        this.objectMapper = (objectMapper != null) ? objectMapper : new ObjectMapper().findAndRegisterModules();
        this.transactionTemplate = transactionTemplate;
        this.dataSource = dataSource;
    }

    /**
     * Provisions a new tenant facility and its default administrator (REQ-TNT-01).
     *
     * @param request tenant creation specifications
     * @return provisioned tenant profile and operational limits
     */
    public TenantResponse createTenant(CreateTenantRequest request) {
        if (request == null) {
            throw new TenantException(TenantErrorCode.TENANT_NOT_FOUND, "Request body cannot be null");
        }

        String normalizedCode = request.getTenantCode().trim().toLowerCase();
        String normalizedEmail = request.getAdminEmail().trim().toLowerCase();
        String cleanPhone = request.getPhoneNumber() != null ? request.getPhoneNumber().trim() : null;
        String tenantName = request.getTenantName().trim();

        // 1. Strict email validation: disallow '+' and enforce correct '.' placement
        if (!EmailValidationUtil.isValid(normalizedEmail)) {
            throw new TenantException(
                    TenantErrorCode.INVALID_ADMIN_EMAIL_FORMAT,
                    "Email không hợp lệ: không được chứa ký tự '+', không bắt đầu/kết thúc bằng dấu '.', không chứa dấu chấm liên tiếp '..', và phải có tên miền hợp lệ."
            );
        }

        // 2. Check uniqueness constraint for tenant_code and admin_email in database and Keycloak
        if (centralTenantRepository.existsByTenantCodeIgnoreCaseAndIsDeletedFalse(normalizedCode)) {
            throw new TenantException(
                    TenantErrorCode.TENANT_OR_EMAIL_ALREADY_EXISTS,
                    "Mã định danh tổ chức hoặc email quản trị này đã được đăng ký trong hệ thống. Vui lòng kiểm tra lại."
            );
        }

        if (centralTenantRepository.findByAdminEmailIgnoreCaseAndIsDeletedFalse(normalizedEmail).isPresent()) {
            throw new TenantException(
                    TenantErrorCode.TENANT_OR_EMAIL_ALREADY_EXISTS,
                    "Mã định danh tổ chức hoặc email quản trị này đã được đăng ký trong hệ thống. Vui lòng kiểm tra lại."
            );
        }

        if (keycloakTokenService.organizationExists(normalizedCode)) {
            throw new TenantException(
                    TenantErrorCode.TENANT_OR_EMAIL_ALREADY_EXISTS,
                    "Mã định danh tổ chức hoặc email quản trị này đã được đăng ký trong hệ thống. Vui lòng kiểm tra lại."
            );
        }

        if (keycloakTokenService.userExistsByEmail(normalizedEmail)) {
            throw new TenantException(
                    TenantErrorCode.TENANT_OR_EMAIL_ALREADY_EXISTS,
                    "Mã định danh tổ chức hoặc email quản trị này đã được đăng ký trong hệ thống. Vui lòng kiểm tra lại."
            );
        }

        // Validate quota initial values if present
        TenantQuotaDto quotaDto = request.getQuotas();
        validateQuotaInputs(
                quotaDto != null ? quotaDto.getMaxStaffs() : null,
                quotaDto != null ? quotaDto.getDailyTicketQuota() : null,
                quotaDto != null ? quotaDto.getMaxServices() : null,
                quotaDto != null ? quotaDto.getMaxAppointments() : null
        );

        // 2. Build tenant entity
        String tenantId = UUID.randomUUID().toString();
        CentralTenant tenant = CentralTenant.builder()
                .id(tenantId)
                .tenantCode(normalizedCode)
                .tenantName(tenantName)
                .adminEmail(normalizedEmail)
                .phoneNumber(cleanPhone)
                .status(TenantStatus.ACTIVE)
                .isDeleted(false)
                .widgetApiKey("nt_pub_" + UUID.randomUUID().toString().replace("-", ""))
                .widgetSecretKey("nt_sec_" + UUID.randomUUID().toString().replace("-", ""))
                .build();

        // 3. Build quota entity
        int maxStaffs = (quotaDto != null && quotaDto.getMaxStaffs() != null) ? quotaDto.getMaxStaffs() : 5;
        int dailyTicketQuota = (quotaDto != null && quotaDto.getDailyTicketQuota() != null) ? quotaDto.getDailyTicketQuota() : 200;
        int maxServices = (quotaDto != null && quotaDto.getMaxServices() != null) ? quotaDto.getMaxServices() : 20;
        int maxAppointments = (quotaDto != null && quotaDto.getMaxAppointments() != null) ? quotaDto.getMaxAppointments() : 500;

        CentralTenantQuota quota = CentralTenantQuota.builder()
                .id(UUID.randomUUID().toString())
                .tenantId(tenantId)
                .maxStaffs(maxStaffs)
                .dailyTicketQuota(dailyTicketQuota)
                .maxServices(maxServices)
                .maxAppointments(maxAppointments)
                .build();

        // 4. Persist in database atomically
        CentralTenant savedTenant;
        CentralTenantQuota savedQuota;
        try {
            record SavedTenantAndQuota(CentralTenant tenant, CentralTenantQuota quota) {}
            SavedTenantAndQuota saved = executeInTransaction(status -> {
                CentralTenant t = centralTenantRepository.save(tenant);
                CentralTenantQuota q = quotaRepository.save(quota);
                return new SavedTenantAndQuota(t, q);
            });
            savedTenant = saved.tenant();
            savedQuota = saved.quota();
        } catch (Exception dbEx) {
            log.error("Database save failed during tenant creation for [{}]: {}", normalizedCode, dbEx.getMessage(), dbEx);
            throw dbEx;
        }

        // 5. Synchronize with Keycloak identity server: create a brand new organization
        String orgId = null;
        String keycloakAdminId = null;
        try {
            orgId = keycloakTokenService.createOrganization(normalizedCode, tenantName);
            final String finalOrgId = orgId;
            savedTenant.setOrganisationId(finalOrgId);
            executeInTransaction(status -> centralTenantRepository.save(savedTenant));

            String adminFullName = request.getAdminFullName() != null && !request.getAdminFullName().isBlank()
                    ? request.getAdminFullName().trim()
                    : "Admin " + tenantName;

            keycloakAdminId = keycloakTokenService.createStaffUser(
                    normalizedCode,
                    normalizedEmail,
                    adminFullName,
                    request.getInitialPassword(),
                    StaffRole.ADMIN
            );

            if (orgId != null && keycloakAdminId != null) {
                keycloakTokenService.addMemberToOrganization(orgId, keycloakAdminId);
            }
        } catch (Exception kcEx) {
            log.error("Keycloak synchronization failed for tenant [{}]. Initiating compensating rollback: {}",
                    normalizedCode, kcEx.getMessage(), kcEx);

            // Rollback database changes (compensation)
            try {
                final String targetTenantId = savedTenant.getId();
                executeInTransaction(status -> {
                    quotaRepository.deleteByTenantId(targetTenantId);
                    centralTenantRepository.deleteById(targetTenantId);
                    return null;
                });
                log.info("Compensated database records for rolled-back tenant [{}]", normalizedCode);
            } catch (Exception rollbackEx) {
                log.error("Failed to compensate database records for tenant [{}]: {}", normalizedCode, rollbackEx.getMessage());
            }

            // Cleanup Keycloak user and organization if partially provisioned
            if (orgId != null) {
                if (keycloakAdminId != null) {
                    try {
                        keycloakTokenService.deleteStaffUserInKeycloak(orgId, keycloakAdminId);
                    } catch (Exception kcCleanEx) {
                        log.warn("Failed to delete orphaned Keycloak user [{}] during rollback: {}", keycloakAdminId, kcCleanEx.getMessage());
                    }
                }
                try {
                    keycloakTokenService.deleteOrganizationInKeycloak(orgId);
                } catch (Exception orgCleanEx) {
                    log.warn("Failed to delete orphaned Keycloak organization [{}] during rollback: {}", orgId, orgCleanEx.getMessage());
                }
            }

            throw new TenantException(
                    TenantErrorCode.AUTH_SERVICE_SYNC_FAILED,
                    "Quá trình đồng bộ tài khoản sang Keycloak thất bại. Vui lòng kiểm tra dịch vụ xác thực."
            );
        }

        // 6. Send automated activation email to facility administrator
        String activationLink = resolveActivationLink(normalizedCode, normalizedEmail);
        try {
            emailService.sendTenantAdminActivationEmail(normalizedEmail, tenantName, activationLink);
        } catch (Exception emailEx) {
            log.warn("Non-fatal: Failed to dispatch tenant activation email to [{}]: {}", normalizedEmail, emailEx.getMessage());
        }

        log.info("Successfully provisioned tenant [{}] (ID: [{}]) with admin [{}] and Keycloak Org [{}]",
                normalizedCode, tenantId, normalizedEmail, orgId);

        TenantQuotaResponse quotaResponse = TenantQuotaResponse.fromEntity(savedQuota, 1L);
        TenantResponse response = TenantResponse.fromEntity(savedTenant, quotaResponse);

        // 7. Warm cache in Valkey
        cacheTenantConfig(response);

        return response;
    }

    /**
     * Self-registers a new enterprise tenant organization from the public portal (REQ-TNT-05).
     * The tenant starts in PENDING_APPROVAL status.
     * Staging Keycloak organization and Admin account are created in DISABLED state.
     *
     * @param request registration specifications
     * @return registered tenant profile in PENDING_APPROVAL status
     */
    public TenantResponse registerTenant(TenantRegistrationRequest request) {
        if (request == null) {
            throw new TenantException(TenantErrorCode.TENANT_NOT_FOUND, "Request body cannot be null");
        }

        // 1. Validate inputs and normalize
        String normalizedCode = request.getTenantCode() != null ? request.getTenantCode().trim().toLowerCase() : "";
        String normalizedEmail = request.getAdminEmail() != null ? request.getAdminEmail().trim().toLowerCase() : "";
        String tenantName = request.getTenantName() != null ? request.getTenantName().trim() : "";
        String cleanPhone = request.getPhoneNumber() != null ? request.getPhoneNumber().trim() : null;

        if (!EmailValidationUtil.isValid(normalizedEmail)) {
            throw new TenantException(
                    TenantErrorCode.INVALID_ADMIN_EMAIL_FORMAT,
                    "Email không hợp lệ: không được chứa ký tự '+', không bắt đầu/kết thúc bằng dấu '.', không chứa dấu chấm liên tiếp '..', và phải có tên miền hợp lệ"
            );
        }

        if (centralTenantRepository.existsByTenantCodeIgnoreCaseAndIsDeletedFalse(normalizedCode)) {
            throw new TenantException(
                    TenantErrorCode.TENANT_OR_EMAIL_ALREADY_EXISTS,
                    "Mã định danh tổ chức hoặc email quản trị này đã được đăng ký trong hệ thống. Vui lòng kiểm tra lại."
            );
        }

        if (centralTenantRepository.findByAdminEmailIgnoreCaseAndIsDeletedFalse(normalizedEmail).isPresent()) {
            throw new TenantException(
                    TenantErrorCode.TENANT_OR_EMAIL_ALREADY_EXISTS,
                    "Mã định danh tổ chức hoặc email quản trị này đã được đăng ký trong hệ thống. Vui lòng kiểm tra lại."
            );
        }

        if (keycloakTokenService.organizationExists(normalizedCode)) {
            throw new TenantException(
                    TenantErrorCode.TENANT_OR_EMAIL_ALREADY_EXISTS,
                    "Mã định danh tổ chức hoặc email quản trị này đã được đăng ký trong hệ thống. Vui lòng kiểm tra lại."
            );
        }

        if (keycloakTokenService.userExistsByEmail(normalizedEmail)) {
            throw new TenantException(
                    TenantErrorCode.TENANT_OR_EMAIL_ALREADY_EXISTS,
                    "Mã định danh tổ chức hoặc email quản trị này đã được đăng ký trong hệ thống. Vui lòng kiểm tra lại."
            );
        }

        // 2. Build tenant entity in PENDING_APPROVAL status
        String tenantId = UUID.randomUUID().toString();
        CentralTenant tenant = CentralTenant.builder()
                .id(tenantId)
                .tenantCode(normalizedCode)
                .tenantName(tenantName)
                .adminEmail(normalizedEmail)
                .phoneNumber(cleanPhone)
                .status(TenantStatus.PENDING_APPROVAL)
                .registrationId(UUID.randomUUID().toString())
                .isDeleted(false)
                .widgetApiKey("nt_pub_" + UUID.randomUUID().toString().replace("-", ""))
                .widgetSecretKey("nt_sec_" + UUID.randomUUID().toString().replace("-", ""))
                .build();

        // 3. Build standard default quotas
        CentralTenantQuota quota = CentralTenantQuota.builder()
                .id(UUID.randomUUID().toString())
                .tenantId(tenantId)
                .maxStaffs(5)
                .dailyTicketQuota(200)
                .maxServices(20)
                .maxAppointments(500)
                .build();

        // 4. Persist in database atomically
        CentralTenant savedTenant;
        CentralTenantQuota savedQuota;
        try {
            record SavedTenantAndQuota(CentralTenant tenant, CentralTenantQuota quota) {}
            SavedTenantAndQuota saved = executeInTransaction(status -> {
                CentralTenant t = centralTenantRepository.save(tenant);
                CentralTenantQuota q = quotaRepository.save(quota);
                return new SavedTenantAndQuota(t, q);
            });
            savedTenant = saved.tenant();
            savedQuota = saved.quota();
        } catch (Exception dbEx) {
            log.error("Database save failed during tenant registration for [{}]: {}", normalizedCode, dbEx.getMessage(), dbEx);
            throw dbEx;
        }

        // 5. Synchronize with Keycloak: create disabled Organization and disabled Admin user
        String orgId = null;
        String keycloakAdminId = null;
        try {
            orgId = keycloakTokenService.createOrganization(normalizedCode, tenantName, false);
            final String finalOrgId = orgId;
            savedTenant.setOrganisationId(finalOrgId);
            executeInTransaction(status -> centralTenantRepository.save(savedTenant));

            String adminFullName = request.getAdminFullName() != null && !request.getAdminFullName().isBlank()
                    ? request.getAdminFullName().trim()
                    : "Admin " + tenantName;

            keycloakAdminId = keycloakTokenService.createStaffUser(
                    normalizedCode,
                    normalizedEmail,
                    adminFullName,
                    request.getInitialPassword(),
                    StaffRole.ADMIN,
                    false
            );

            if (orgId != null && keycloakAdminId != null) {
                keycloakTokenService.addMemberToOrganization(orgId, keycloakAdminId);
            }
        } catch (Exception kcEx) {
            log.error("Keycloak staging failed for tenant registration [{}]. Initiating compensating rollback: {}",
                    normalizedCode, kcEx.getMessage(), kcEx);

            // Rollback database changes
            try {
                final String targetTenantId = savedTenant.getId();
                executeInTransaction(status -> {
                    quotaRepository.deleteByTenantId(targetTenantId);
                    centralTenantRepository.deleteById(targetTenantId);
                    return null;
                });
                log.info("Compensated database records for rolled-back tenant registration [{}]", normalizedCode);
            } catch (Exception rollbackEx) {
                log.error("Failed to compensate database records for tenant registration [{}]: {}", normalizedCode, rollbackEx.getMessage());
            }

            // Cleanup Keycloak resources
            if (orgId != null) {
                if (keycloakAdminId != null) {
                    try {
                        keycloakTokenService.deleteStaffUserInKeycloak(orgId, keycloakAdminId);
                    } catch (Exception cleanEx) {
                        log.warn("Failed to delete Keycloak staging user [{}] during rollback: {}", keycloakAdminId, cleanEx.getMessage());
                    }
                }
                try {
                    keycloakTokenService.deleteOrganizationInKeycloak(orgId);
                } catch (Exception cleanEx) {
                    log.warn("Failed to delete Keycloak staging org [{}] during rollback: {}", orgId, cleanEx.getMessage());
                }
            }

            throw new TenantException(
                    TenantErrorCode.AUTH_SERVICE_SYNC_FAILED,
                    "Quá trình đồng bộ tài khoản tạm thời sang Keycloak thất bại. Vui lòng kiểm tra dịch vụ xác thực."
            );
        }

        // 6. Send confirmation email to registrant
        try {
            emailService.sendTenantRegistrationReceivedEmail(normalizedEmail, tenantName, normalizedCode);
        } catch (Exception emailEx) {
            log.warn("Non-fatal: Failed to dispatch registration confirmation email to [{}]: {}", normalizedEmail, emailEx.getMessage());
        }

        // 7. Send internal notification email to Superadministrator
        try {
            emailService.sendTenantRegistrationInternalNotification(
                    "superadmin@nextturn.com",
                    tenantName,
                    normalizedCode,
                    normalizedEmail
            );
        } catch (Exception notifyEx) {
            log.warn("Non-fatal: Failed to dispatch internal registration notification: {}", notifyEx.getMessage());
        }

        log.info("Successfully registered tenant [{}] in PENDING_APPROVAL status (ID: [{}])", normalizedCode, tenantId);

        TenantQuotaResponse quotaResponse = TenantQuotaResponse.fromEntity(savedQuota, 0L);
        return TenantResponse.fromEntity(savedTenant, quotaResponse);
    }

    /**
     * Updates tenant administrative information and operational capacity quotas (REQ-TNT-02).
     *
     * @param tenantIdentifier tenant ID or tenant code
     * @param request          update specifications
     * @return updated tenant profile and limits
     */
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
        validateQuotaInputs(
                request.getMaxStaffs(),
                request.getDailyTicketQuota(),
                request.getMaxServices(),
                request.getMaxAppointments()
        );

        // 3. Check actual active staff count vs new max_staffs quota limit
        long currentActiveStaff = countActiveStaff(tenant.getTenantCode());
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

        // 6. Invalidate and refresh Valkey/Redis cache for tenant configuration (REQ-TNT-02)
        cacheTenantConfig(response);

        return response;
    }

    /**
     * Suspends tenant operations and locks all associated Keycloak staff accounts (REQ-TNT-03).
     *
     * @param tenantIdentifier tenant ID or tenant code
     * @return updated tenant profile
     */
    public TenantResponse suspendTenant(String tenantIdentifier) {
        CentralTenant tenant = resolveTenant(tenantIdentifier);

        TenantStatus previousStatus = tenant.getStatus();
        if (previousStatus == TenantStatus.SUSPENDED) {
            throw new TenantException(
                    TenantErrorCode.TENANT_ALREADY_SUSPENDED,
                    "Tổ chức này đã ở trạng thái tạm ngừng (SUSPENDED)."
            );
        }

        tenant.setStatus(TenantStatus.SUSPENDED);
        CentralTenant savedTenant = executeInTransaction(status -> centralTenantRepository.save(tenant));

        // Synchronize with Keycloak: disable organization, disable all staff, revoke sessions
        if (savedTenant.getOrganisationId() != null) {
            try {
                keycloakTokenService.setOrganizationAndMembersActive(savedTenant.getOrganisationId(), false);
            } catch (Exception kcEx) {
                log.error("Failed to suspend Keycloak organization [{}]. Compensating database status for tenant [{}]: {}",
                        savedTenant.getOrganisationId(), savedTenant.getTenantCode(), kcEx.getMessage());
                executeInTransaction(status -> {
                    tenant.setStatus(previousStatus);
                    return centralTenantRepository.save(tenant);
                });
                throw new TenantException(
                        TenantErrorCode.AUTH_SERVICE_SYNC_FAILED,
                        "Quá trình đồng bộ tạm khóa sang Keycloak thất bại. Đã khôi phục trạng thái ban đầu."
                );
            }
        }

        // Evict and refresh Valkey cache
        evictTenantConfigCache(savedTenant.getId(), savedTenant.getTenantCode());

        CentralTenantQuota quota = quotaRepository.findByTenantId(savedTenant.getId()).orElse(null);
        long activeStaff = countActiveStaff(savedTenant.getTenantCode());
        TenantQuotaResponse quotaResponse = TenantQuotaResponse.fromEntity(quota, activeStaff);
        TenantResponse response = TenantResponse.fromEntity(savedTenant, quotaResponse);
        cacheTenantConfig(response);

        log.info("Successfully suspended tenant [{}] and revoked all active sessions in Keycloak", savedTenant.getTenantCode());
        return response;
    }

    /**
     * Reactivates a suspended tenant and restores Keycloak login permissions (REQ-TNT-03).
     *
     * @param tenantIdentifier tenant ID or tenant code
     * @return updated tenant profile
     */
    public TenantResponse activateTenant(String tenantIdentifier) {
        CentralTenant tenant = resolveTenant(tenantIdentifier);

        TenantStatus previousStatus = tenant.getStatus();
        if (previousStatus == TenantStatus.ACTIVE) {
            throw new TenantException(
                    TenantErrorCode.TENANT_ALREADY_ACTIVE,
                    "Tổ chức này đã ở trạng thái hoạt động (ACTIVE)."
            );
        }

        tenant.setStatus(TenantStatus.ACTIVE);
        CentralTenant savedTenant = executeInTransaction(status -> centralTenantRepository.save(tenant));

        // Synchronize with Keycloak: enable organization and restore staff login rights
        if (savedTenant.getOrganisationId() != null) {
            try {
                keycloakTokenService.setOrganizationAndMembersActive(savedTenant.getOrganisationId(), true);
            } catch (Exception kcEx) {
                log.error("Failed to activate Keycloak organization [{}]. Compensating database status for tenant [{}]: {}",
                        savedTenant.getOrganisationId(), savedTenant.getTenantCode(), kcEx.getMessage());
                executeInTransaction(status -> {
                    tenant.setStatus(previousStatus);
                    return centralTenantRepository.save(tenant);
                });
                throw new TenantException(
                        TenantErrorCode.AUTH_SERVICE_SYNC_FAILED,
                        "Quá trình đồng bộ kích hoạt lại sang Keycloak thất bại. Đã khôi phục trạng thái ban đầu."
                );
            }
        }

        // Evict and refresh Valkey cache
        evictTenantConfigCache(savedTenant.getId(), savedTenant.getTenantCode());

        CentralTenantQuota quota = quotaRepository.findByTenantId(savedTenant.getId()).orElse(null);
        long activeStaff = countActiveStaff(savedTenant.getTenantCode());
        TenantQuotaResponse quotaResponse = TenantQuotaResponse.fromEntity(quota, activeStaff);
        TenantResponse response = TenantResponse.fromEntity(savedTenant, quotaResponse);
        cacheTenantConfig(response);

        log.info("Successfully reactivated tenant [{}] and restored login permissions in Keycloak", savedTenant.getTenantCode());
        return response;
    }

    /**
     * Dispatches tenant lifecycle transition based on request status (REQ-TNT-03).
     *
     * @param tenantIdentifier tenant ID or tenant code
     * @param request          status transition payload
     * @return updated tenant profile
     */
    public TenantResponse updateTenantStatus(String tenantIdentifier, UpdateTenantStatusRequest request) {
        if (request == null || request.getStatus() == null) {
            throw new TenantException(TenantErrorCode.TENANT_NOT_FOUND, "Trạng thái mục tiêu không được để trống");
        }
        if (request.getStatus() == TenantStatus.SUSPENDED) {
            return suspendTenant(tenantIdentifier);
        } else if (request.getStatus() == TenantStatus.ACTIVE) {
            return activateTenant(tenantIdentifier);
        } else {
            throw new TenantException(
                    TenantErrorCode.TENANT_NOT_ACTIVE,
                    "Chỉ hỗ trợ chuyển đổi giữa trạng thái ACTIVE và SUSPENDED"
            );
        }
    }

    /**
     * Soft-deletes a tenant organization without physical deletion (REQ-TNT-03).
     * Strictly refuses hard deletion if historical data exists.
     * Sets isDeleted = true, status = SUSPENDED, disables Keycloak organization, and revokes active sessions.
     *
     * @param tenantIdentifier tenant ID or tenant code
     */
    public void deleteTenant(String tenantIdentifier) {
        CentralTenant tenant = resolveTenant(tenantIdentifier);

        // Check if organization has generated historical tickets, appointments, or reviews
        if (checkTenantHistoricalData(tenant.getTenantCode())) {
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

        evictTenantConfigCache(tenant.getId(), tenant.getTenantCode());
        log.info("Successfully soft-deleted tenant [{}]", tenant.getTenantCode());
    }

    /**
     * Approves a pending tenant registration application (REQ-TNT-06).
     * Transitions status from PENDING_APPROVAL to ACTIVE, enables Keycloak organization and admin account,
     * and sends congratulatory email with login instructions.
     *
     * @param tenantIdentifier tenant ID or tenant code
     * @return updated tenant profile in ACTIVE status
     */
    public TenantResponse approveTenantRegistration(String tenantIdentifier) {
        CentralTenant tenant = resolveTenant(tenantIdentifier);

        if (tenant.getStatus() != TenantStatus.PENDING_APPROVAL) {
            throw new TenantException(
                    TenantErrorCode.TENANT_NOT_PENDING_APPROVAL,
                    "Chỉ có thể phê duyệt tổ chức đang ở trạng thái chờ phê duyệt (PENDING_APPROVAL)."
            );
        }

        tenant.setStatus(TenantStatus.ACTIVE);
        CentralTenant savedTenant = executeInTransaction(status -> centralTenantRepository.save(tenant));

        // Activate organization and admin account in Keycloak
        if (savedTenant.getOrganisationId() != null) {
            try {
                keycloakTokenService.setOrganizationAndMembersActive(savedTenant.getOrganisationId(), true);
            } catch (Exception kcEx) {
                log.error("Failed to activate Keycloak organization [{}]. Compensating database status for tenant [{}]: {}",
                        savedTenant.getOrganisationId(), savedTenant.getTenantCode(), kcEx.getMessage());
                executeInTransaction(status -> {
                    tenant.setStatus(TenantStatus.PENDING_APPROVAL);
                    return centralTenantRepository.save(tenant);
                });
                throw new TenantException(
                        TenantErrorCode.AUTH_SERVICE_SYNC_FAILED,
                        "Quá trình kích hoạt tài khoản trên Keycloak thất bại. Đã khôi phục trạng thái ban đầu."
                );
            }
        }

        // Evict and warm cache
        evictTenantConfigCache(savedTenant.getId(), savedTenant.getTenantCode());

        CentralTenantQuota quota = quotaRepository.findByTenantId(savedTenant.getId()).orElse(null);
        long activeStaff = countActiveStaff(savedTenant.getTenantCode());
        TenantQuotaResponse quotaResponse = TenantQuotaResponse.fromEntity(quota, activeStaff);
        TenantResponse response = TenantResponse.fromEntity(savedTenant, quotaResponse);
        cacheTenantConfig(response);

        // Send congratulatory approval email with activation link
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

        log.info("Successfully approved tenant registration for [{}]", savedTenant.getTenantCode());
        return response;
    }

    /**
     * Denies a pending tenant registration application with a mandatory rejection reason (REQ-TNT-06).
     * Transitions status to REJECTED, deletes temporary Keycloak credentials, and sends notification email.
     *
     * @param tenantIdentifier tenant ID or tenant code
     * @param request          rejection specifications containing mandatory reason
     * @return updated tenant profile in REJECTED status
     */
    public TenantResponse rejectTenantRegistration(String tenantIdentifier, RejectTenantRequest request) {
        if (request == null || request.getReason() == null || request.getReason().isBlank()) {
            throw new TenantException(
                    TenantErrorCode.REJECTION_REASON_REQUIRED,
                    "Lý do từ chối đăng ký tổ chức là bắt buộc."
            );
        }

        CentralTenant tenant = resolveTenant(tenantIdentifier);

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

        evictTenantConfigCache(savedTenant.getId(), savedTenant.getTenantCode());

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
     * Searches, filters with pagination, and provides operational monitoring metrics (REQ-TNT-04).
     * Target API latency: <= 300ms.
     *
     * @param keyword       optional keyword filter
     * @param status        optional lifecycle status filter
     * @param page          0-based page index
     * @param limit         page size
     * @param sortBy        sort field
     * @param sortDirection sort direction (ASC or DESC)
     * @return paged collection of tenant telemetry and monitoring responses
     */
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
            long activeStaff = countActiveStaff(t.getTenantCode());
            long todayTickets = countTodayTickets(t.getTenantCode());
            TenantQuotaResponse quotaResponse = TenantQuotaResponse.fromEntity(quota, activeStaff);
            return TenantMonitoringResponse.fromEntity(t, quotaResponse, activeStaff, todayTickets);
        }).toList();

        return new PageImpl<>(items, pageable, tenantPage.getTotalElements());
    }

    /**
     * Counts queue tickets created today in tenant schema (REQ-TNT-04).
     * Uses Valkey telemetry cache first with fallback to database on cache miss.
     */
    public long countTodayTickets(String tenantCode) {
        if (tenantCode == null || tenantCode.isBlank()) {
            return 0L;
        }
        String cacheKey = ValkeyKeyConstant.tenantTodayTicketsKey(tenantCode);
        Long cached = getCachedCount(cacheKey);
        if (cached != null) {
            return cached;
        }

        if (dataSource == null) {
            return 0L;
        }
        long count = 0L;
        String previousTenant = TenantContextHolder.getTenantCode();
        try {
            TenantContextHolder.setTenantCode(tenantCode.trim());
            try (Connection conn = dataSource.getConnection()) {
                String sql = "SELECT COUNT(*) FROM tenant__queue_tickets WHERE created_at >= CURDATE() AND is_deleted = false";
                try (PreparedStatement stmt = conn.prepareStatement(sql);
                     ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        count = rs.getLong(1);
                    }
                }
            }
        } catch (Exception ex) {
            log.trace("Today tickets count query noted for tenant [{}]: {}", tenantCode, ex.getMessage());
        } finally {
            if (previousTenant != null) {
                TenantContextHolder.setTenantCode(previousTenant);
            } else {
                TenantContextHolder.clear();
            }
        }

        setCachedCount(cacheKey, count, Duration.ofSeconds(60));
        return count;
    }

    /**
     * Checks if tenant has generated historical operational data (tickets, appointments, reviews) (REQ-TNT-03).
     */
    public boolean checkTenantHistoricalData(String tenantCode) {
        if (tenantCode == null || tenantCode.isBlank() || dataSource == null) {
            return false;
        }
        String previousTenant = TenantContextHolder.getTenantCode();
        try {
            TenantContextHolder.setTenantCode(tenantCode.trim());
            try (Connection conn = dataSource.getConnection()) {
                return countTableRows(conn, "tenant__queue_tickets") > 0
                        || countTableRows(conn, "tenant__appointments") > 0
                        || countTableRows(conn, "tenant__feedbacks") > 0;
            }
        } catch (Exception ex) {
            log.debug("Historical operational data check noted for schema [tenant_{}]: {}", tenantCode, ex.getMessage());
            return false;
        } finally {
            if (previousTenant != null) {
                TenantContextHolder.setTenantCode(previousTenant);
            } else {
                TenantContextHolder.clear();
            }
        }
    }

    private long countTableRows(Connection conn, String tableName) {
        String sql = "SELECT COUNT(*) FROM " + tableName;
        try (PreparedStatement stmt = conn.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {
            if (rs.next()) {
                return rs.getLong(1);
            }
        } catch (SQLException ex) {
            log.trace("Table [{}] not found or query failed in tenant catalog: {}", tableName, ex.getMessage());
        }
        return 0L;
    }

    /**
     * Retrieves detailed profile and quotas of a tenant by ID or tenant code.
     * Checks Valkey cache first before querying PostgreSQL.
     */
    public TenantResponse getTenant(String tenantIdentifier) {
        TenantResponse cached = getCachedTenantConfig(tenantIdentifier);
        if (cached != null) {
            log.debug("Valkey cache hit for tenant [{}]", tenantIdentifier);
            return cached;
        }

        CentralTenant tenant = resolveTenant(tenantIdentifier);
        CentralTenantQuota quota = quotaRepository.findByTenantId(tenant.getId()).orElse(null);
        long activeStaff = countActiveStaff(tenant.getTenantCode());
        TenantQuotaResponse quotaResponse = TenantQuotaResponse.fromEntity(quota, activeStaff);
        TenantResponse response = TenantResponse.fromEntity(tenant, quotaResponse);

        cacheTenantConfig(response);
        return response;
    }

    /**
     * Retrieves operational quotas of a tenant by ID or tenant code.
     * Checks Valkey cache first before querying PostgreSQL.
     */
    public TenantQuotaResponse getTenantQuota(String tenantIdentifier) {
        TenantResponse cached = getCachedTenantConfig(tenantIdentifier);
        if (cached != null && cached.getQuotas() != null) {
            log.debug("Valkey cache hit for tenant quota [{}]", tenantIdentifier);
            return cached.getQuotas();
        }

        CentralTenant tenant = resolveTenant(tenantIdentifier);
        CentralTenantQuota quota = quotaRepository.findByTenantId(tenant.getId())
                .orElseThrow(() -> new TenantException(TenantErrorCode.TENANT_NOT_FOUND, "Quota not found for tenant [" + tenantIdentifier + "]"));
        long activeStaff = countActiveStaff(tenant.getTenantCode());
        TenantQuotaResponse quotaResponse = TenantQuotaResponse.fromEntity(quota, activeStaff);

        cacheTenantConfig(TenantResponse.fromEntity(tenant, quotaResponse));
        return quotaResponse;
    }

    private CentralTenant resolveTenant(String identifier) {
        if (identifier == null || identifier.isBlank()) {
            throw new TenantException(TenantErrorCode.TENANT_NOT_FOUND, "Tenant identifier cannot be blank");
        }
        String cleanId = identifier.trim();
        return centralTenantRepository.findById(cleanId)
                .filter(t -> !Boolean.TRUE.equals(t.getIsDeleted()))
                .or(() -> centralTenantRepository.findByTenantCodeIgnoreCaseAndIsDeletedFalse(cleanId))
                .orElseThrow(() -> new TenantException(TenantErrorCode.TENANT_NOT_FOUND, "Tenant [" + cleanId + "] not found in registry"));
    }

    private long countActiveStaff(String tenantCode) {
        if (tenantCode == null || tenantCode.isBlank()) {
            return 0L;
        }
        String cacheKey = ValkeyKeyConstant.tenantActiveStaffKey(tenantCode);
        Long cached = getCachedCount(cacheKey);
        if (cached != null) {
            return cached;
        }

        if (staffRepository == null) {
            return 0L;
        }
        long count = 0L;
        String previousTenant = TenantContextHolder.getTenantCode();
        try {
            TenantContextHolder.setTenantCode(tenantCode.trim());
            count = staffRepository.countByStatusAndIsDeletedFalse(StaffStatus.ACTIVE);
        } catch (Exception ex) {
            log.warn("Could not query staff count in tenant schema [{}] (table might not exist yet): {}",
                    tenantCode, ex.getMessage());
        } finally {
            if (previousTenant != null) {
                TenantContextHolder.setTenantCode(previousTenant);
            } else {
                TenantContextHolder.clear();
            }
        }

        setCachedCount(cacheKey, count, Duration.ofMinutes(5));
        return count;
    }

    private Long getCachedCount(String key) {
        if (redisTemplate == null || key == null || key.isBlank()) {
            return null;
        }
        try {
            var ops = redisTemplate.opsForValue();
            if (ops != null) {
                String val = ops.get(key);
                if (val != null && !val.isBlank()) {
                    return Long.parseLong(val.trim());
                }
            }
        } catch (Exception ex) {
            log.trace("Cache read error for count key [{}]: {}", key, ex.getMessage());
        }
        return null;
    }

    private void setCachedCount(String key, long count, Duration ttl) {
        if (redisTemplate == null || key == null || key.isBlank()) {
            return;
        }
        try {
            var ops = redisTemplate.opsForValue();
            if (ops != null) {
                ops.set(key, String.valueOf(count), ttl);
            }
        } catch (Exception ex) {
            log.trace("Cache write error for count key [{}]: {}", key, ex.getMessage());
        }
    }

    private void validateQuotaInputs(Integer maxStaffs, Integer dailyTicketQuota, Integer maxServices, Integer maxAppointments) {
        if (maxStaffs != null && maxStaffs < 1) {
            throw new TenantException(
                    TenantErrorCode.INVALID_QUOTA_VALUE,
                    "Số lượng nhân viên tối đa phải là số nguyên dương >= 1"
            );
        }
        if (dailyTicketQuota != null && dailyTicketQuota < 1 && dailyTicketQuota != -1) {
            throw new TenantException(
                    TenantErrorCode.INVALID_QUOTA_VALUE,
                    "Hạn ngạch vé tối đa trong ngày phải là số nguyên dương >= 1 hoặc -1 (không giới hạn)"
            );
        }
        if (maxServices != null && maxServices < 1 && maxServices != -1) {
            throw new TenantException(
                    TenantErrorCode.INVALID_QUOTA_VALUE,
                    "Hạn ngạch số lượng dịch vụ tối đa phải là số nguyên dương >= 1 hoặc -1 (không giới hạn)"
            );
        }
        if (maxAppointments != null && maxAppointments < 1 && maxAppointments != -1) {
            throw new TenantException(
                    TenantErrorCode.INVALID_QUOTA_VALUE,
                    "Số lượng cuộc hẹn tối đa trong tương lai phải là số nguyên dương >= 1 hoặc -1 (không giới hạn)"
            );
        }
    }

    private void cacheTenantConfig(TenantResponse response) {
        if (redisTemplate == null || response == null) {
            return;
        }
        try {
            var ops = redisTemplate.opsForValue();
            if (ops == null) {
                return;
            }
            String json = objectMapper.writeValueAsString(response);
            Duration ttl = Duration.ofHours(24);
            if (response.getId() != null) {
                ops.set(ValkeyKeyConstant.tenantConfigKey(response.getId()), json, ttl);
            }
            if (response.getTenantCode() != null) {
                ops.set(ValkeyKeyConstant.tenantConfigKey(response.getTenantCode()), json, ttl);
            }
            log.debug("Refreshed Valkey tenant config cache for [{}] / [{}]", response.getId(), response.getTenantCode());
        } catch (Exception ex) {
            log.warn("Failed to cache tenant config in Valkey: {}", ex.getMessage());
        }
    }

    private TenantResponse getCachedTenantConfig(String tenantIdentifier) {
        if (redisTemplate == null || tenantIdentifier == null || tenantIdentifier.isBlank()) {
            return null;
        }
        try {
            var ops = redisTemplate.opsForValue();
            if (ops == null) {
                return null;
            }
            String key = ValkeyKeyConstant.tenantConfigKey(tenantIdentifier);
            String json = ops.get(key);
            if (json != null && !json.isBlank()) {
                return objectMapper.readValue(json, TenantResponse.class);
            }
        } catch (Exception ex) {
            log.warn("Failed to read cached tenant config from Valkey for [{}]: {}", tenantIdentifier, ex.getMessage());
        }
        return null;
    }

    private void evictTenantConfigCache(String tenantId, String tenantCode) {
        if (redisTemplate != null) {
            try {
                List<String> keys = new ArrayList<>();
                if (tenantId != null) {
                    keys.add(ValkeyKeyConstant.tenantConfigKey(tenantId));
                }
                if (tenantCode != null) {
                    keys.add(ValkeyKeyConstant.tenantConfigKey(tenantCode));
                    keys.add(ValkeyKeyConstant.tenantTodayTicketsKey(tenantCode));
                    keys.add(ValkeyKeyConstant.tenantActiveStaffKey(tenantCode));
                }
                redisTemplate.delete(keys);
                log.debug("Evicted Valkey cache keys: {}", keys);
            } catch (Exception ex) {
                log.warn("Valkey cache eviction failed for tenant [{}] / [{}]: {}", tenantId, tenantCode, ex.getMessage());
            }
        }
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
