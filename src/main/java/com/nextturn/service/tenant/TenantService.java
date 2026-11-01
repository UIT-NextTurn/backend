package com.nextturn.service.tenant;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nextturn.constant.ValkeyKeyConstant;
import com.nextturn.dto.tenant.CreateTenantRequest;
import com.nextturn.dto.tenant.TenantQuotaDto;
import com.nextturn.dto.tenant.TenantQuotaResponse;
import com.nextturn.dto.tenant.TenantResponse;
import com.nextturn.dto.tenant.UpdateTenantRequest;
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
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * Service orchestrating tenant lifecycle, onboarding provisioning (REQ-TNT-01),
 * administrative updates, and operational quota management (REQ-TNT-02).
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
            @Autowired(required = false) TransactionTemplate transactionTemplate
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

        // 4. Persist in database
        CentralTenant savedTenant;
        CentralTenantQuota savedQuota;
        try {
            savedTenant = executeInTransaction(status -> centralTenantRepository.save(tenant));
            savedQuota = executeInTransaction(status -> quotaRepository.save(quota));
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

        CentralTenant updatedTenant = executeInTransaction(status -> centralTenantRepository.save(tenant));

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

        CentralTenantQuota updatedQuota = executeInTransaction(status -> quotaRepository.save(quota));

        log.info("Successfully updated tenant [{}] profile and quotas (max_staffs: {}, daily_quota: {})",
                tenant.getTenantCode(), updatedQuota.getMaxStaffs(), updatedQuota.getDailyTicketQuota());

        TenantQuotaResponse quotaResponse = TenantQuotaResponse.fromEntity(updatedQuota, currentActiveStaff);
        TenantResponse response = TenantResponse.fromEntity(updatedTenant, quotaResponse);

        // 6. Invalidate and refresh Valkey/Redis cache for tenant configuration (REQ-TNT-02)
        cacheTenantConfig(response);

        return response;
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
        if (staffRepository == null || tenantCode == null || tenantCode.isBlank()) {
            return 0L;
        }
        String previousTenant = TenantContextHolder.getTenantCode();
        try {
            TenantContextHolder.setTenantCode(tenantCode.trim());
            return staffRepository.countByStatusAndIsDeletedFalse(StaffStatus.ACTIVE);
        } catch (Exception ex) {
            log.warn("Could not query staff count in tenant schema [{}] (table might not exist yet): {}",
                    tenantCode, ex.getMessage());
            return 0L;
        } finally {
            if (previousTenant != null) {
                TenantContextHolder.setTenantCode(previousTenant);
            } else {
                TenantContextHolder.clear();
            }
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
                String keyById = ValkeyKeyConstant.tenantConfigKey(tenantId);
                String keyByCode = ValkeyKeyConstant.tenantConfigKey(tenantCode);
                redisTemplate.delete(List.of(keyById, keyByCode));
                log.debug("Evicted Valkey cache keys [{}] and [{}]", keyById, keyByCode);
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
