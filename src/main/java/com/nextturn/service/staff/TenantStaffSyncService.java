package com.nextturn.service.staff;

import com.nextturn.entity.TenantStaff;
import com.nextturn.enums.StaffRole;
import com.nextturn.enums.StaffStatus;
import com.nextturn.infrastructure.multitenancy.TenantContextHolder;
import com.nextturn.repository.staff.TenantStaffRepository;
import com.nextturn.service.BaseService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Service managing enterprise Identity Provider (IdP) SSO login synchronization
 * and Just-In-Time (JIT) provisioning for tenant staff records.
 */
@Service
public class TenantStaffSyncService extends BaseService {

    private static final Logger log = LoggerFactory.getLogger(TenantStaffSyncService.class);

    private final TenantStaffRepository staffRepository;
    private final TenantStaffCodeGenerator codeGenerator;

    @Autowired
    public TenantStaffSyncService(
            TenantStaffRepository staffRepository,
            TenantStaffCodeGenerator codeGenerator,
            @Autowired(required = false) TransactionTemplate transactionTemplate
    ) {
        super(transactionTemplate);
        this.staffRepository = staffRepository;
        this.codeGenerator = codeGenerator;
    }

    /**
     * Synchronizes existing staff profile with claims from Keycloak/IdP upon login,
     * or Just-In-Time (JIT) provisions a new staff record in the tenant database if the user
     * authenticated via enterprise SSO.
     *
     * @param tenantCode the verified tenant code
     * @param keycloakUserId Keycloak sub UUID
     * @param email authenticated email address
     * @param fullName full name claim from IdP / token
     * @param roles client roles claim from token
     * @return updated or newly provisioned {@link TenantStaff}
     */
    public TenantStaff syncOrProvisionStaffOnLogin(
            String tenantCode,
            String keycloakUserId,
            String email,
            String fullName,
            List<String> roles
    ) {
        String previousTenant = TenantContextHolder.getTenantCode();
        try {
            TenantContextHolder.setTenantCode(tenantCode.trim().toLowerCase());
            StaffRole assignedRole = StaffRole.COUNTER;
            if (roles != null) {
                if (roles.contains("TENANT_ADMIN") || roles.contains("ROLE_TENANT_ADMIN")) {
                    assignedRole = StaffRole.ADMIN;
                } else if (roles.contains("COUNTER_STAFF") || roles.contains("ROLE_COUNTER_STAFF")) {
                    assignedRole = StaffRole.COUNTER;
                }
            }

            Optional<TenantStaff> staffOpt = Optional.empty();
            if (keycloakUserId != null && !keycloakUserId.isBlank()) {
                staffOpt = staffRepository.findByKeycloakUserIdAndIsDeletedFalse(keycloakUserId);
            }
            if (staffOpt.isEmpty() && email != null && !email.isBlank()) {
                staffOpt = staffRepository.findByEmailAndIsDeletedFalse(email.trim().toLowerCase());
            }

            if (staffOpt.isPresent()) {
                TenantStaff staff = staffOpt.get();
                boolean changed = false;
                if (keycloakUserId != null && !keycloakUserId.equals(staff.getKeycloakUserId())) {
                    staff.setKeycloakUserId(keycloakUserId);
                    changed = true;
                }
                if (fullName != null && !fullName.isBlank() && !fullName.equals(staff.getFullName())) {
                    staff.setFullName(fullName.trim());
                    changed = true;
                }
                if (assignedRole != staff.getRole()) {
                    staff.setRole(assignedRole);
                    changed = true;
                }
                if (changed) {
                    executeInTransaction(status -> staffRepository.save(staff));
                    log.info("Synchronized profile for staff [{}] in tenant [{}] upon login", staff.getEmail(), tenantCode);
                }
                return staff;
            }

            // JIT Provisioning
            String staffCode = codeGenerator.generateNextStaffCode();
            String cleanName = (fullName != null && !fullName.isBlank()) ? fullName.trim() : (email != null ? email.trim() : "Staff Member");
            String cleanEmail = email != null ? email.trim().toLowerCase() : (keycloakUserId + "@tenant.internal");

            // Generate synthetic unique phone number respecting table-wide unique constraint
            int phoneSeed = Math.abs((keycloakUserId != null ? keycloakUserId : cleanEmail).hashCode() % 90000000) + 10000000;
            String syntheticPhone = "09" + phoneSeed;
            for (int i = 0; i < 50; i++) {
                String candidatePhone = String.format("09%08d", (phoneSeed + i) % 100000000);
                if (!staffRepository.existsByPhoneNumber(candidatePhone)) {
                    syntheticPhone = candidatePhone;
                    break;
                }
            }

            TenantStaff newStaff = TenantStaff.builder()
                    .id(UUID.randomUUID().toString())
                    .staffCode(staffCode)
                    .keycloakUserId(keycloakUserId)
                    .fullName(cleanName)
                    .email(cleanEmail)
                    .phoneNumber(syntheticPhone)
                    .role(assignedRole)
                    .status(StaffStatus.ACTIVE)
                    .isDeleted(false)
                    .build();

            TenantStaff saved = executeInTransaction(status -> staffRepository.save(newStaff));
            log.info("JIT provisioned new staff member [{}] (badge: [{}], role: [{}]) for tenant [{}] via SSO",
                    cleanEmail, staffCode, assignedRole, tenantCode);
            return saved;
        } finally {
            if (previousTenant != null) {
                TenantContextHolder.setTenantCode(previousTenant);
            } else {
                TenantContextHolder.clear();
            }
        }
    }
}

