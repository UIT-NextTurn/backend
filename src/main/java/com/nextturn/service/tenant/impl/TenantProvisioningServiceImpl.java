package com.nextturn.service.tenant.impl;

import com.nextturn.dto.tenant.CreateTenantRequest;
import com.nextturn.dto.tenant.TenantQuotaDto;
import com.nextturn.dto.tenant.TenantQuotaResponse;
import com.nextturn.dto.tenant.TenantRegistrationRequest;
import com.nextturn.dto.tenant.TenantResponse;
import com.nextturn.entity.CentralTenant;
import com.nextturn.entity.CentralTenantQuota;
import com.nextturn.enums.StaffRole;
import com.nextturn.enums.TenantStatus;
import com.nextturn.exception.tenant.TenantErrorCode;
import com.nextturn.exception.tenant.TenantException;
import com.nextturn.infrastructure.security.keycloak.KeycloakProperties;
import com.nextturn.infrastructure.security.keycloak.KeycloakTokenService;
import com.nextturn.repository.auth.CentralTenantRepository;
import com.nextturn.repository.tenant.CentralTenantQuotaRepository;
import com.nextturn.service.notification.EmailService;
import com.nextturn.service.tenant.TenantCacheService;
import com.nextturn.service.tenant.TenantDatabaseProvisioningService;
import com.nextturn.service.tenant.TenantProvisioningService;
import com.nextturn.util.EmailValidationUtil;
import com.nextturn.service.BaseService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

/**
 * Service orchestrating new tenant facility provisioning and public onboarding self-registration
 * with distributed compensating rollback transactions (REQ-TNT-01, REQ-TNT-05).
 */
@Service
public class TenantProvisioningServiceImpl extends BaseService implements TenantProvisioningService {

    private static final Logger log = LoggerFactory.getLogger(TenantProvisioningServiceImpl.class);

    private final CentralTenantRepository centralTenantRepository;
    private final CentralTenantQuotaRepository quotaRepository;
    private final KeycloakTokenService keycloakTokenService;
    private final KeycloakProperties keycloakProperties;
    private final EmailService emailService;
    private final TenantCacheService cacheService;
    private final TenantDatabaseProvisioningService databaseProvisioningService;

    @Autowired
    public TenantProvisioningServiceImpl(
            CentralTenantRepository centralTenantRepository,
            CentralTenantQuotaRepository quotaRepository,
            KeycloakTokenService keycloakTokenService,
            KeycloakProperties keycloakProperties,
            EmailService emailService,
            TenantCacheService cacheService,
            @Autowired(required = false) TenantDatabaseProvisioningService databaseProvisioningService,
            @Autowired(required = false) TransactionTemplate transactionTemplate
    ) {
        super(transactionTemplate);
        this.centralTenantRepository = centralTenantRepository;
        this.quotaRepository = quotaRepository;
        this.keycloakTokenService = keycloakTokenService;
        this.keycloakProperties = keycloakProperties;
        this.emailService = emailService;
        this.cacheService = cacheService;
        this.databaseProvisioningService = databaseProvisioningService != null ? databaseProvisioningService : new TenantDatabaseProvisioningServiceImpl(null);
    }

    public TenantProvisioningServiceImpl(
            CentralTenantRepository centralTenantRepository,
            CentralTenantQuotaRepository quotaRepository,
            KeycloakTokenService keycloakTokenService,
            KeycloakProperties keycloakProperties,
            EmailService emailService,
            TenantCacheService cacheService,
            TransactionTemplate transactionTemplate
    ) {
        this(
                centralTenantRepository,
                quotaRepository,
                keycloakTokenService,
                keycloakProperties,
                emailService,
                cacheService,
                new TenantDatabaseProvisioningServiceImpl(null),
                transactionTemplate
        );
    }

    public TenantProvisioningServiceImpl(
            CentralTenantRepository centralTenantRepository,
            CentralTenantQuotaRepository quotaRepository,
            KeycloakTokenService keycloakTokenService,
            KeycloakProperties keycloakProperties,
            EmailService emailService,
            TenantCacheService cacheService
    ) {
        this(
                centralTenantRepository,
                quotaRepository,
                keycloakTokenService,
                keycloakProperties,
                emailService,
                cacheService,
                new TenantDatabaseProvisioningServiceImpl(null),
                null
        );
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

        // 3. Build tenant entity
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

        // 4. Build quota entity
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

        // 5. Persist in database atomically
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

        // 6. Synchronize with Keycloak identity server: create a brand new organization
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

        // 7. Provision dedicated tenant database schema and seed initial administrator
        if (databaseProvisioningService != null) {
            try {
                String adminFullName = request.getAdminFullName() != null && !request.getAdminFullName().isBlank()
                        ? request.getAdminFullName().trim()
                        : "Admin " + tenantName;
                databaseProvisioningService.provisionTenantDatabase(
                        normalizedCode,
                        normalizedEmail,
                        adminFullName,
                        cleanPhone,
                        keycloakAdminId
                );
            } catch (Exception dbSchemaEx) {
                log.error("Tenant database schema provisioning failed for [{}]. Initiating compensating rollback: {}",
                        normalizedCode, dbSchemaEx.getMessage(), dbSchemaEx);

                // Compensation: drop tenant schema, cleanup Keycloak, rollback central records
                try {
                    databaseProvisioningService.dropTenantDatabase(normalizedCode);
                } catch (Exception cleanDbEx) {
                    log.warn("Failed to drop partially created tenant database [{}]: {}", normalizedCode, cleanDbEx.getMessage());
                }

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

                final String targetTenantId = savedTenant.getId();
                executeInTransaction(status -> {
                    quotaRepository.deleteByTenantId(targetTenantId);
                    centralTenantRepository.deleteById(targetTenantId);
                    return null;
                });

                if (dbSchemaEx instanceof TenantException te) {
                    throw te;
                }
                throw new TenantException(
                        TenantErrorCode.TENANT_SCHEMA_MIGRATION_FAILED,
                        "Khởi tạo cơ sở dữ liệu cho chi nhánh thất bại: " + dbSchemaEx.getMessage()
                );
            }
        }

        // 8. Send automated activation email to facility administrator
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

        // 9. Warm cache in Valkey
        cacheService.cacheTenantConfig(response);

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

        // 2. Build tenant entity with status = PENDING_APPROVAL
        String tenantId = UUID.randomUUID().toString();
        CentralTenant tenant = CentralTenant.builder()
                .id(tenantId)
                .tenantCode(normalizedCode)
                .tenantName(tenantName)
                .adminEmail(normalizedEmail)
                .phoneNumber(cleanPhone)
                .status(TenantStatus.PENDING_APPROVAL)
                .isDeleted(false)
                .widgetApiKey("nt_pub_" + UUID.randomUUID().toString().replace("-", ""))
                .widgetSecretKey("nt_sec_" + UUID.randomUUID().toString().replace("-", ""))
                .build();

        // 3. Initialize default quota limits (5 staff, 200 tickets/day, 20 services, 500 appointments)
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

        // 5. Staging in Keycloak: Organization and Admin user created in DISABLED state
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

            // Cleanup Keycloak partial staging
            if (orgId != null) {
                if (keycloakAdminId != null) {
                    try {
                        keycloakTokenService.deleteStaffUserInKeycloak(orgId, keycloakAdminId);
                    } catch (Exception cleanUserEx) {
                        log.warn("Failed to delete staged Keycloak user [{}] during rollback: {}", keycloakAdminId, cleanUserEx.getMessage());
                    }
                }
                try {
                    keycloakTokenService.deleteOrganizationInKeycloak(orgId);
                } catch (Exception cleanOrgEx) {
                    log.warn("Failed to delete staged Keycloak organization [{}] during rollback: {}", orgId, cleanOrgEx.getMessage());
                }
            }

            throw new TenantException(
                    TenantErrorCode.AUTH_SERVICE_SYNC_FAILED,
                    "Quá trình đồng bộ tài khoản sang Keycloak thất bại. Vui lòng kiểm tra dịch vụ xác thực."
            );
        }

        // 6. Send confirmation email to applicant (REQ-TNT-05 Step 6)
        try {
            emailService.sendTenantRegistrationReceivedEmail(normalizedEmail, tenantName, normalizedCode);
        } catch (Exception emailEx) {
            log.warn("Non-fatal: Failed to send registration received email to [{}]: {}", normalizedEmail, emailEx.getMessage());
        }

        // 7. Send internal alert to Superadministrator (REQ-TNT-05 Step 7)
        try {
            emailService.sendTenantRegistrationInternalNotification(
                    "sa@nextturn.internal",
                    tenantName,
                    normalizedCode,
                    normalizedEmail
            );
        } catch (Exception saEmailEx) {
            log.warn("Non-fatal: Failed to send internal registration notification email to SA: {}", saEmailEx.getMessage());
        }

        log.info("Successfully registered tenant [{}] in PENDING_APPROVAL status (ID: [{}])", normalizedCode, tenantId);

        TenantQuotaResponse quotaResponse = TenantQuotaResponse.fromEntity(savedQuota, 0L);
        TenantResponse response = TenantResponse.fromEntity(savedTenant, quotaResponse);

        // 8. Cache tenant config in Valkey
        cacheService.cacheTenantConfig(response);

        return response;
    }

    public void validateQuotaInputs(Integer maxStaffs, Integer dailyTicketQuota, Integer maxServices, Integer maxAppointments) {
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

    private String resolveActivationLink(String tenantCode, String adminEmail) {
        if (keycloakProperties != null && keycloakProperties.authServerUrl() != null && !keycloakProperties.authServerUrl().isBlank()) {
            return keycloakProperties.authServerUrl() + "/realms/" + keycloakProperties.realm() + "/account";
        }
        return "http://localhost:8180/realms/nextturn/account";
    }
}
