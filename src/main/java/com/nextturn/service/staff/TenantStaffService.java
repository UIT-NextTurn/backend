package com.nextturn.service.staff;

import com.nextturn.dto.service.ServiceResponse;
import com.nextturn.dto.staff.AssignStaffServicesRequest;
import com.nextturn.dto.staff.CreateStaffRequest;
import com.nextturn.dto.staff.StaffResponse;
import com.nextturn.dto.staff.UpdateStaffRequest;
import com.nextturn.entity.CentralTenant;
import com.nextturn.entity.TenantStaff;
import com.nextturn.enums.StaffRole;
import com.nextturn.enums.StaffStatus;
import com.nextturn.exception.staff.StaffErrorCode;
import com.nextturn.exception.staff.StaffException;
import com.nextturn.exception.tenant.TenantErrorCode;
import com.nextturn.exception.tenant.TenantException;
import com.nextturn.infrastructure.multitenancy.TenantContextHolder;
import com.nextturn.infrastructure.security.keycloak.KeycloakTokenService;
import com.nextturn.repository.auth.CentralTenantRepository;
import com.nextturn.repository.service.TenantServiceRepository;
import com.nextturn.repository.staff.TenantStaffRepository;
import com.nextturn.repository.staff.TenantStaffServiceRepository;
import com.nextturn.service.BaseService;
import com.nextturn.service.notification.EmailService;
import com.nextturn.service.tenant.TenantCacheService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Service orchestrating staff onboarding, profile management, status lifecycle,
 * and Keycloak IAM integration while delegating specialized responsibilities
 * (guards, assignments, queries, code generation, JIT sync) to dedicated domain services.
 */
@Service
public class TenantStaffService extends BaseService {

    private static final Logger log = LoggerFactory.getLogger(TenantStaffService.class);

    private final TenantStaffRepository staffRepository;
    private final TenantStaffServiceRepository staffServiceRepository;
    private final TenantServiceRepository serviceRepository;
    private final CentralTenantRepository centralTenantRepository;
    private final KeycloakTokenService keycloakTokenService;
    private final TenantCacheService tenantCacheService;
    private final EmailService emailService;
    private final TenantStaffCodeGenerator codeGenerator;
    private final TenantStaffGuardService guardService;
    private final TenantStaffAssignmentService assignmentService;
    private final TenantStaffSyncService syncService;
    private final TenantStaffQueryService queryService;

    @Autowired
    public TenantStaffService(
            TenantStaffRepository staffRepository,
            TenantStaffServiceRepository staffServiceRepository,
            TenantServiceRepository serviceRepository,
            CentralTenantRepository centralTenantRepository,
            KeycloakTokenService keycloakTokenService,
            TenantCacheService tenantCacheService,
            EmailService emailService,
            @Autowired(required = false) TenantStaffCodeGenerator codeGenerator,
            @Autowired(required = false) TenantStaffGuardService guardService,
            @Autowired(required = false) TenantStaffAssignmentService assignmentService,
            @Autowired(required = false) TenantStaffSyncService syncService,
            @Autowired(required = false) TenantStaffQueryService queryService,
            @Autowired(required = false) TransactionTemplate transactionTemplate
    ) {
        super(transactionTemplate);
        this.staffRepository = staffRepository;
        this.staffServiceRepository = staffServiceRepository;
        this.serviceRepository = serviceRepository;
        this.centralTenantRepository = centralTenantRepository;
        this.keycloakTokenService = keycloakTokenService;
        this.tenantCacheService = tenantCacheService;
        this.emailService = emailService;
        this.codeGenerator = codeGenerator != null ? codeGenerator : new TenantStaffCodeGenerator(staffRepository);
        this.guardService = guardService != null ? guardService : new TenantStaffGuardService(staffRepository);
        this.assignmentService = assignmentService != null ? assignmentService : new TenantStaffAssignmentService(staffRepository, staffServiceRepository, serviceRepository, tenantCacheService, transactionTemplate);
        this.syncService = syncService != null ? syncService : new TenantStaffSyncService(staffRepository, this.codeGenerator, transactionTemplate);
        this.queryService = queryService != null ? queryService : new TenantStaffQueryService(staffRepository, staffServiceRepository);
    }

    /**
     * Fallback constructor for backward compatibility and Mockito unit testing.
     */
    public TenantStaffService(
            TenantStaffRepository staffRepository,
            TenantStaffServiceRepository staffServiceRepository,
            TenantServiceRepository serviceRepository,
            CentralTenantRepository centralTenantRepository,
            KeycloakTokenService keycloakTokenService,
            TenantCacheService tenantCacheService,
            EmailService emailService
    ) {
        this(
                staffRepository,
                staffServiceRepository,
                serviceRepository,
                centralTenantRepository,
                keycloakTokenService,
                tenantCacheService,
                emailService,
                new TenantStaffCodeGenerator(staffRepository),
                new TenantStaffGuardService(staffRepository),
                new TenantStaffAssignmentService(staffRepository, staffServiceRepository, serviceRepository, tenantCacheService, null),
                new TenantStaffSyncService(staffRepository, new TenantStaffCodeGenerator(staffRepository), null),
                new TenantStaffQueryService(staffRepository, staffServiceRepository),
                null
        );
    }

    private CentralTenant resolveCentralTenant(String tenantCode) {
        return centralTenantRepository.findByTenantCodeAndIsDeletedFalse(tenantCode)
                .orElseThrow(() -> new TenantException(TenantErrorCode.TENANT_NOT_FOUND, "Tenant [" + tenantCode + "] not found in registry"));
    }

    /**
     * Onboards a new staff member into the tenant database and synchronizes with Keycloak IAM:
     * 1. Checks unique email and phone number constraints in MySQL.
     * 2. Synchronizes identity user in Keycloak IAM for interactive login roles (ADMIN, COUNTER).
     * 3. Saves staff record in tenant-isolated schema with status ACTIVE and sequential badge code (STAFF-xxxx).
     * 4. Sends automated activation email containing initial password setup link.
     *
     * @param request staff creation payload
     * @return created {@link StaffResponse}
     */
    public StaffResponse createStaff(CreateStaffRequest request) {
        String tenantCode = resolveCurrentTenantCode();
        String normalizedEmail = request.getEmail().trim().toLowerCase();
        String cleanPhone = request.getPhoneNumber().trim();

        if (staffRepository.existsByEmail(normalizedEmail)
                || staffRepository.existsByPhoneNumber(cleanPhone)) {
            throw new StaffException(
                    StaffErrorCode.STAFF_ALREADY_EXISTS,
                    "Email hoặc số điện thoại này đã được đăng ký bởi một nhân viên khác trong hệ thống."
            );
        }

        CentralTenant tenant = resolveCentralTenant(tenantCode);
        String orgId = tenant.getOrganisationId();
        if (orgId == null || orgId.isBlank()) {
            orgId = keycloakTokenService.ensureOrganizationExists(tenantCode, tenant.getTenantName());
            final String finalOrgId = orgId;
            executeInTransaction(status -> {
                tenant.setOrganisationId(finalOrgId);
                return centralTenantRepository.save(tenant);
            });
        }

        StaffRole role = request.getRole() != null ? request.getRole() : StaffRole.COUNTER;
        String keycloakUserId = null;

        if (role != StaffRole.SPECIALIST) {
            try {
                keycloakUserId = keycloakTokenService.createStaffUser(
                        tenantCode,
                        normalizedEmail,
                        request.getFullName(),
                        request.getInitialPassword(),
                        role
                );

                if (orgId != null && keycloakUserId != null) {
                    keycloakTokenService.addMemberToOrganization(orgId, keycloakUserId);
                }
            } catch (Exception kcEx) {
                log.error("Failed to synchronize user [{}] with Keycloak IAM: {}", normalizedEmail, kcEx.getMessage());
                throw new StaffException(StaffErrorCode.STAFF_KEYCLOAK_SYNC_FAILED, "Lỗi đồng bộ tài khoản xác thực Keycloak: " + kcEx.getMessage());
            }
        } else {
            log.info("Staff member [{}] has role SPECIALIST; recorded in roster without separate login credentials", normalizedEmail);
        }

        String staffCode = codeGenerator.generateNextStaffCode();

        TenantStaff staff = TenantStaff.builder()
                .id(UUID.randomUUID().toString())
                .staffCode(staffCode)
                .keycloakUserId(keycloakUserId)
                .fullName(request.getFullName().trim())
                .email(normalizedEmail)
                .phoneNumber(cleanPhone)
                .role(role)
                .status(StaffStatus.ACTIVE)
                .isDeleted(false)
                .build();

        TenantStaff savedStaff;
        try {
            savedStaff = executeInTransaction(status -> staffRepository.save(staff));
        } catch (Exception dbEx) {
            if (orgId != null && keycloakUserId != null) {
                log.warn("Database save failed for staff [{}]. Triggering compensating cleanup in Keycloak for user [{}]",
                        normalizedEmail, keycloakUserId);
                try {
                    keycloakTokenService.deleteStaffUserInKeycloak(orgId, keycloakUserId);
                } catch (Exception cleanupEx) {
                    log.error("Failed to execute compensating Keycloak user deletion for [{}]: {}", keycloakUserId, cleanupEx.getMessage());
                }
            }
            throw dbEx;
        }

        String activationLink = "http://localhost:8080/set-password?email=" + normalizedEmail + "&tenant=" + tenantCode;
        emailService.sendStaffActivationEmail(normalizedEmail, request.getFullName().trim(), tenant.getTenantName(), activationLink);

        log.info("Successfully registered staff [{}] with badge [{}] in tenant [{}]", normalizedEmail, staffCode, tenantCode);

        return StaffResponse.fromEntity(savedStaff);
    }

    /**
     * Assigns a list of services to a staff member by delegating to {@link TenantStaffAssignmentService}.
     *
     * @param staffId staff member UUID
     * @param request assignment request containing target service UUIDs
     * @return updated {@link StaffResponse} with list of assigned services
     */
    public StaffResponse assignServices(String staffId, AssignStaffServicesRequest request) {
        return assignmentService.assignServices(staffId, request);
    }

    /**
     * Retrieves the list of services currently assigned to a staff member.
     *
     * @param staffId staff member UUID
     * @return list of {@link ServiceResponse}
     */
    @Transactional(readOnly = true)
    public List<ServiceResponse> getAssignedServices(String staffId) {
        return assignmentService.getAssignedServices(staffId);
    }

    /**
     * Searches and lists active staff members in the caller's tenant with pagination and optional multi-criteria filters.
     *
     * @param keyword optional search keyword matching full name, email, phone, or badge code
     * @param role optional role filter (ADMIN, COUNTER, SPECIALIST)
     * @param status optional status filter (ACTIVE, INACTIVE)
     * @param pageable pagination parameters
     * @return page of {@link StaffResponse}
     */
    @Transactional(readOnly = true)
    public Page<StaffResponse> searchStaff(String keyword, StaffRole role, StaffStatus status, Pageable pageable) {
        return queryService.searchStaff(keyword, role, status, pageable);
    }

    /**
     * Lists active staff members in the caller's tenant with pagination.
     */
    @Transactional(readOnly = true)
    public Page<StaffResponse> getStaffList(Pageable pageable) {
        return queryService.getStaffList(pageable);
    }

    /**
     * Retrieves a single staff member by ID within the caller's tenant.
     */
    @Transactional(readOnly = true)
    public StaffResponse getStaffById(String staffId) {
        return queryService.getStaffById(staffId);
    }

    /**
     * Updates profile, operational role, or active status of an existing staff member.
     * When deactivating a staff member (status = INACTIVE), checks relational constraints
     * across upcoming appointments and operational shifts, revokes active Keycloak sessions,
     * and invalidates cached service assignments.
     */
    public StaffResponse updateStaff(String staffId, UpdateStaffRequest request) {
        String tenantCode = resolveCurrentTenantCode();
        TenantStaff staff = staffRepository.findByIdAndIsDeletedFalse(staffId)
                .orElseThrow(() -> new StaffException(
                        StaffErrorCode.STAFF_NOT_FOUND,
                        "Nhân viên không tồn tại trong hệ thống hoặc đã bị xóa."
                ));

        String oldPhone = staff.getPhoneNumber();
        StaffStatus oldStatus = staff.getStatus();

        if (request.getFullName() != null && !request.getFullName().isBlank()) {
            staff.setFullName(request.getFullName().trim());
        }

        if (request.getPhoneNumber() != null && !request.getPhoneNumber().isBlank()) {
            String newPhone = request.getPhoneNumber().trim();
            if (!newPhone.equalsIgnoreCase(oldPhone) && staffRepository.existsByPhoneNumberAndIsDeletedFalse(newPhone)) {
                throw new StaffException(
                        StaffErrorCode.STAFF_ALREADY_EXISTS,
                        "Email hoặc số điện thoại này đã được đăng ký bởi một nhân viên khác trong hệ thống."
                );
            }
            staff.setPhoneNumber(newPhone);
        }

        if (request.getRole() != null) {
            staff.setRole(request.getRole());
        }

        if (request.getStatus() != null) {
            StaffStatus newStatus = request.getStatus();
            if (newStatus == StaffStatus.INACTIVE && oldStatus != StaffStatus.INACTIVE) {
                guardService.validateNoActiveCommitmentsForDeactivation(staffId, staff.getStaffCode(), staff.getFullName(), tenantCode, "vô hiệu hóa");

                staff.setStatus(StaffStatus.INACTIVE);
                if (staff.getKeycloakUserId() != null) {
                    keycloakTokenService.updateUserStatusInKeycloak(staff.getKeycloakUserId(), false);
                    keycloakTokenService.logoutUserInKeycloak(staff.getKeycloakUserId());
                }
                assignmentService.evictAssignedServicesCache(tenantCode, staffId);
            } else if (newStatus == StaffStatus.ACTIVE && oldStatus != StaffStatus.ACTIVE) {
                staff.setStatus(StaffStatus.ACTIVE);
                if (staff.getKeycloakUserId() != null) {
                    keycloakTokenService.updateUserStatusInKeycloak(staff.getKeycloakUserId(), true);
                }
                assignmentService.evictAssignedServicesCache(tenantCode, staffId);
            }
        }

        TenantStaff saved = executeInTransaction(status -> staffRepository.save(staff));

        List<ServiceResponse> services = staffServiceRepository.findByStaffIdWithService(staffId)
                .stream()
                .map(assignment -> ServiceResponse.fromEntity(assignment.getService()))
                .collect(Collectors.toList());

        log.info("Updated staff [{}] ({}) status=[{}] in tenant [{}]", saved.getStaffCode(), saved.getFullName(), saved.getStatus(), tenantCode);

        return StaffResponse.fromEntity(saved, services);
    }

    /**
     * Soft-deletes a staff member from the active roster with relational integrity checks.
     */
    public void deleteStaff(String staffId) {
        deleteStaff(staffId, false);
    }

    /**
     * Deactivates and soft-deletes a staff member, or performs hard deletion if explicitly requested.
     * Prevents deactivation/deletion if incomplete appointments or upcoming scheduled shifts exist.
     * Strictly rejects hard deletion if the staff member has historical records (tickets served, called, feedbacks).
     *
     * @param staffId staff UUID
     * @param hardDelete true for physical deletion, false for soft-delete (status = INACTIVE, is_deleted = true)
     */
    public void deleteStaff(String staffId, boolean hardDelete) {
        String tenantCode = resolveCurrentTenantCode();

        if (hardDelete) {
            TenantStaff staff = staffRepository.findById(staffId)
                    .orElseThrow(() -> new StaffException(
                            StaffErrorCode.STAFF_NOT_FOUND,
                            "Nhân viên không tồn tại trong hệ thống."
                    ));

            guardService.validateSafeForHardDelete(staffId, staff.getStaffCode(), staff.getFullName(), tenantCode);
            guardService.validateNoActiveCommitmentsForDeactivation(staffId, staff.getStaffCode(), staff.getFullName(), tenantCode, "xóa");

            executeInTransaction(status -> {
                staffServiceRepository.deleteByStaffId(staffId);
                staffRepository.delete(staff);
                return null;
            });

            if (staff.getKeycloakUserId() != null) {
                try {
                    CentralTenant tenant = centralTenantRepository.findByTenantCodeAndIsDeletedFalse(tenantCode)
                            .orElse(null);
                    String orgId = tenant != null ? tenant.getOrganisationId() : null;
                    keycloakTokenService.deleteStaffUserInKeycloak(orgId, staff.getKeycloakUserId());
                } catch (Exception ex) {
                    log.warn("Failed to delete Keycloak user [{}] during hard delete: {}", staff.getKeycloakUserId(), ex.getMessage());
                }
            }

            assignmentService.evictAssignedServicesCache(tenantCode, staffId);
            log.info("Hard-deleted staff [{}] ({}) in tenant [{}]", staff.getStaffCode(), staff.getFullName(), tenantCode);
        } else {
            TenantStaff staff = staffRepository.findByIdAndIsDeletedFalse(staffId)
                    .orElseThrow(() -> new StaffException(
                            StaffErrorCode.STAFF_NOT_FOUND,
                            "Nhân viên không tồn tại trong hệ thống hoặc đã bị xóa."
                    ));

            guardService.validateNoActiveCommitmentsForDeactivation(staffId, staff.getStaffCode(), staff.getFullName(), tenantCode, "vô hiệu hóa");

            staff.setIsDeleted(true);
            staff.setStatus(StaffStatus.INACTIVE);
            executeInTransaction(status -> staffRepository.save(staff));

            if (staff.getKeycloakUserId() != null) {
                keycloakTokenService.updateUserStatusInKeycloak(staff.getKeycloakUserId(), false);
                keycloakTokenService.logoutUserInKeycloak(staff.getKeycloakUserId());
            }

            assignmentService.evictAssignedServicesCache(tenantCode, staffId);
            log.info("Soft-deleted staff [{}] ({}) in tenant [{}]", staff.getStaffCode(), staff.getFullName(), tenantCode);
        }
    }

    /**
     * Synchronizes existing staff profile with claims from Keycloak/IdP upon login,
     * or Just-In-Time (JIT) provisions a new staff record in the tenant database.
     * Delegates to {@link TenantStaffSyncService}.
     */
    public TenantStaff syncOrProvisionStaffOnLogin(
            String tenantCode,
            String keycloakUserId,
            String email,
            String fullName,
            List<String> roles
    ) {
        return syncService.syncOrProvisionStaffOnLogin(tenantCode, keycloakUserId, email, fullName, roles);
    }

    /**
     * Generates sequential staff code in format STAFF-xxxx.
     * Delegates to {@link TenantStaffCodeGenerator}.
     */
    public String generateNextStaffCode() {
        return codeGenerator.generateNextStaffCode();
    }
}
