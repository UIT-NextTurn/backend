package com.nextturn.service.auth;

import com.nextturn.dto.auth.CreateStaffRequest;
import com.nextturn.dto.auth.StaffResponse;
import com.nextturn.dto.auth.UpdateStaffRequest;
import com.nextturn.entity.CentralTenant;
import com.nextturn.entity.TenantStaff;
import com.nextturn.enums.StaffRole;
import com.nextturn.enums.StaffStatus;
import com.nextturn.exception.admin.AdminErrorCode;
import com.nextturn.exception.admin.AdminException;
import com.nextturn.exception.tenant.TenantErrorCode;
import com.nextturn.exception.tenant.TenantException;
import com.nextturn.infrastructure.multitenancy.TenantContextHolder;
import com.nextturn.infrastructure.security.keycloak.KeycloakTokenService;
import com.nextturn.repository.auth.CentralTenantRepository;
import com.nextturn.repository.auth.TenantStaffRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.UUID;

/**
 * Service orchestrating healthcare encounter staff onboarding, lifecycle, and Keycloak integration.
 * Belongs to the authentication & identity domain, operating within the caller's verified tenant context.
 */
@Service
public class TenantStaffService {

    private static final Logger log = LoggerFactory.getLogger(TenantStaffService.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final TenantStaffRepository staffRepository;
    private final CentralTenantRepository centralTenantRepository;
    private final KeycloakTokenService keycloakTokenService;

    public TenantStaffService(
            TenantStaffRepository staffRepository,
            CentralTenantRepository centralTenantRepository,
            KeycloakTokenService keycloakTokenService
    ) {
        this.staffRepository = staffRepository;
        this.centralTenantRepository = centralTenantRepository;
        this.keycloakTokenService = keycloakTokenService;
    }

    /**
     * Provisions a new encounter staff member in the tenant database and synchronizes with Keycloak IAM:
     * 1. Validates caller's active tenant context.
     * 2. Checks local uniqueness constraints (email, phone).
     * 3. Creates the user in Keycloak with COUNTER_STAFF or TENANT_ADMIN role.
     * 4. Binds the user to the tenant facility's Keycloak Organization.
     * 5. Saves the staff profile in {@code tenant__staffs}.
     *
     * @param request staff creation payload
     * @return {@link StaffResponse} with generated ID and badge code
     */
    @Transactional
    public StaffResponse createStaff(CreateStaffRequest request) {
        String tenantCode = resolveCurrentTenantCode();
        String normalizedEmail = request.getEmail().trim().toLowerCase();
        String cleanPhone = request.getPhoneNumber().trim();

        if (staffRepository.existsByEmailAndIsDeletedFalse(normalizedEmail)) {
            throw new AdminException(AdminErrorCode.STAFF_ALREADY_EXISTS, "Staff member with email [" + normalizedEmail + "] already exists in this tenant");
        }

        if (staffRepository.existsByPhoneNumberAndIsDeletedFalse(cleanPhone)) {
            throw new AdminException(AdminErrorCode.STAFF_ALREADY_EXISTS, "Staff member with phone [" + cleanPhone + "] already exists in this tenant");
        }

        CentralTenant tenant = resolveCentralTenant(tenantCode);
        String orgId = tenant.getOrganisationId();
        if (orgId == null || orgId.isBlank()) {
            orgId = keycloakTokenService.ensureOrganizationExists(tenantCode, tenant.getTenantName());
            tenant.setOrganisationId(orgId);
            centralTenantRepository.save(tenant);
        }

        StaffRole role = request.getRole() != null ? request.getRole() : StaffRole.COUNTER;
        String keycloakUserId = null;

        // In NextTurn tenant staff management:
        // - Only ADMIN and COUNTER have login access via Keycloak.
        // - SPECIALIST is internal roster-only (danh sách chuyên gia); no Keycloak user is created.
        if (role != StaffRole.SPECIALIST) {
            // 1. Provision user in Keycloak IAM
            keycloakUserId = keycloakTokenService.createStaffUser(
                    tenantCode,
                    normalizedEmail,
                    request.getFullName(),
                    request.getInitialPassword(),
                    role
            );

            // 2. Associate with Keycloak Organization
            if (orgId != null && keycloakUserId != null) {
                keycloakTokenService.addMemberToOrganization(orgId, keycloakUserId);
            }
        } else {
            log.info("Staff member [{}] is a SPECIALIST. Storing in tenant roster only (no Keycloak login account created)", normalizedEmail);
        }

        // 3. Generate badge code (e.g. STF-A8C2D1)
        String staffCode = generateUniqueStaffCode();

        // 4. Persist in tenant-isolated schema
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

        TenantStaff savedStaff = staffRepository.save(staff);
        log.info("Successfully provisioned staff [{}] with badge [{}] in tenant [{}]", normalizedEmail, staffCode, tenantCode);

        return StaffResponse.fromEntity(savedStaff);
    }

    /**
     * Lists all active staff members in the caller's tenant.
     */
    @Transactional(readOnly = true)
    public Page<StaffResponse> getStaffList(Pageable pageable) {
        resolveCurrentTenantCode();
        return staffRepository.findAllByIsDeletedFalse(pageable)
                .map(StaffResponse::fromEntity);
    }

    /**
     * Retrieves a single staff member by ID within the caller's tenant.
     */
    @Transactional(readOnly = true)
    public StaffResponse getStaffById(String staffId) {
        resolveCurrentTenantCode();
        TenantStaff staff = staffRepository.findByIdAndIsDeletedFalse(staffId)
                .orElseThrow(() -> new AdminException(AdminErrorCode.STAFF_USER_NOT_FOUND, "Staff member not found with ID: " + staffId));
        return StaffResponse.fromEntity(staff);
    }

    /**
     * Updates profile or status of an existing staff member.
     * When status changes, updates user status in Keycloak as well.
     */
    @Transactional
    public StaffResponse updateStaff(String staffId, UpdateStaffRequest request) {
        String tenantCode = resolveCurrentTenantCode();
        TenantStaff staff = staffRepository.findByIdAndIsDeletedFalse(staffId)
                .orElseThrow(() -> new AdminException(AdminErrorCode.STAFF_USER_NOT_FOUND, "Staff member not found with ID: " + staffId));

        if (request.getFullName() != null && !request.getFullName().isBlank()) {
            staff.setFullName(request.getFullName().trim());
        }

        if (request.getPhoneNumber() != null && !request.getPhoneNumber().isBlank()) {
            String newPhone = request.getPhoneNumber().trim();
            if (!newPhone.equalsIgnoreCase(staff.getPhoneNumber()) && staffRepository.existsByPhoneNumberAndIsDeletedFalse(newPhone)) {
                throw new AdminException(AdminErrorCode.STAFF_ALREADY_EXISTS, "Phone number is already assigned to another staff member");
            }
            staff.setPhoneNumber(newPhone);
        }

        if (request.getRole() != null && request.getRole() != staff.getRole()) {
            StaffRole oldRole = staff.getRole();
            StaffRole newRole = request.getRole();
            staff.setRole(newRole);

            CentralTenant tenant = resolveCentralTenant(tenantCode);

            // Upgraded from roster-only specialist to interactive login role -> provision Keycloak user
            if (oldRole == StaffRole.SPECIALIST && (newRole == StaffRole.COUNTER || newRole == StaffRole.ADMIN)) {
                if (staff.getKeycloakUserId() == null) {
                    String orgId = tenant.getOrganisationId();
                    if (orgId == null || orgId.isBlank()) {
                        orgId = keycloakTokenService.ensureOrganizationExists(tenantCode, tenant.getTenantName());
                        tenant.setOrganisationId(orgId);
                        centralTenantRepository.save(tenant);
                    }
                    String keycloakUserId = keycloakTokenService.createStaffUser(
                            tenantCode,
                            staff.getEmail(),
                            staff.getFullName(),
                            null,
                            newRole
                    );
                    if (orgId != null && keycloakUserId != null) {
                        keycloakTokenService.addMemberToOrganization(orgId, keycloakUserId);
                    }
                    staff.setKeycloakUserId(keycloakUserId);
                }
            } else if ((oldRole == StaffRole.COUNTER || oldRole == StaffRole.ADMIN) && newRole == StaffRole.SPECIALIST) {
                // Downgraded to specialist roster-only -> remove Keycloak login identity
                if (staff.getKeycloakUserId() != null) {
                    keycloakTokenService.deleteStaffUserInKeycloak(tenant.getOrganisationId(), staff.getKeycloakUserId());
                    staff.setKeycloakUserId(null);
                }
            }
        }

        if (request.getStatus() != null && request.getStatus() != staff.getStatus()) {
            staff.setStatus(request.getStatus());
            if (staff.getKeycloakUserId() != null) {
                keycloakTokenService.updateUserStatusInKeycloak(staff.getKeycloakUserId(), request.getStatus() == StaffStatus.ACTIVE);
            }
        }

        TenantStaff updated = staffRepository.save(staff);
        return StaffResponse.fromEntity(updated);
    }

    /**
     * Soft-deletes a staff member from the tenant database and deactivates/removes them from Keycloak.
     */
    @Transactional
    public void deleteStaff(String staffId) {
        String tenantCode = resolveCurrentTenantCode();
        TenantStaff staff = staffRepository.findByIdAndIsDeletedFalse(staffId)
                .orElseThrow(() -> new AdminException(AdminErrorCode.STAFF_USER_NOT_FOUND, "Staff member not found with ID: " + staffId));

        CentralTenant tenant = resolveCentralTenant(tenantCode);

        // Remove from Keycloak Organization and delete user
        if (staff.getKeycloakUserId() != null) {
            keycloakTokenService.deleteStaffUserInKeycloak(tenant.getOrganisationId(), staff.getKeycloakUserId());
        }

        staff.setIsDeleted(true);
        staff.setStatus(StaffStatus.INACTIVE);
        staffRepository.save(staff);
        log.info("Soft-deleted staff member [{}] in tenant [{}]", staff.getEmail(), tenantCode);
    }

    private String resolveCurrentTenantCode() {
        String tenantCode = TenantContextHolder.getTenantCode();
        if (tenantCode == null || tenantCode.isBlank()) {
            throw new TenantException(TenantErrorCode.TENANT_CONTEXT_MISSING);
        }
        return tenantCode.trim();
    }

    private CentralTenant resolveCentralTenant(String tenantCode) {
        return centralTenantRepository.findByTenantCodeAndIsDeletedFalse(tenantCode)
                .orElseThrow(() -> new TenantException(TenantErrorCode.TENANT_NOT_FOUND, "Tenant [" + tenantCode + "] not found in registry"));
    }

    private String generateUniqueStaffCode() {
        for (int i = 0; i < 10; i++) {
            String code = "STF-" + String.format("%06d", RANDOM.nextInt(1_000_000));
            if (!staffRepository.existsByStaffCodeAndIsDeletedFalse(code)) {
                return code;
            }
        }
        return "STF-" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
    }
}
