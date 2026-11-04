package com.nextturn.service.staff;

import com.nextturn.dto.service.ServiceResponse;
import com.nextturn.dto.staff.AssignStaffServicesRequest;
import com.nextturn.dto.staff.CreateStaffRequest;
import com.nextturn.dto.staff.StaffResponse;
import com.nextturn.dto.staff.UpdateStaffRequest;
import com.nextturn.entity.TenantStaff;
import com.nextturn.enums.StaffRole;
import com.nextturn.enums.StaffStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;

/**
 * Service contract orchestrating staff onboarding, profile management, status lifecycle,
 * and Keycloak IAM integration while delegating specialized responsibilities
 * (guards, assignments, queries, code generation, JIT sync) to dedicated domain services.
 */
public interface TenantStaffService {

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
    StaffResponse createStaff(CreateStaffRequest request);

    /**
     * Assigns a list of services to a staff member by delegating to {@link TenantStaffAssignmentService}.
     *
     * @param staffId staff member UUID
     * @param request assignment request containing target service UUIDs
     * @return updated {@link StaffResponse} with list of assigned services
     */
    StaffResponse assignServices(String staffId, AssignStaffServicesRequest request);

    /**
     * Retrieves the list of services currently assigned to a staff member.
     *
     * @param staffId staff member UUID
     * @return list of {@link ServiceResponse}
     */
    List<ServiceResponse> getAssignedServices(String staffId);

    /**
     * Searches and lists active staff members in the caller's tenant with pagination and optional multi-criteria filters.
     *
     * @param keyword optional search keyword matching full name, email, phone, or badge code
     * @param role optional role filter (ADMIN, COUNTER, SPECIALIST)
     * @param status optional status filter (ACTIVE, INACTIVE)
     * @param pageable pagination parameters
     * @return page of {@link StaffResponse}
     */
    Page<StaffResponse> searchStaff(String keyword, StaffRole role, StaffStatus status, Pageable pageable);

    /**
     * Lists active staff members in the caller's tenant with pagination.
     *
     * @param pageable pagination parameters
     * @return page of {@link StaffResponse}
     */
    Page<StaffResponse> getStaffList(Pageable pageable);

    /**
     * Retrieves a single staff member by ID within the caller's tenant.
     *
     * @param staffId staff member UUID
     * @return {@link StaffResponse}
     */
    StaffResponse getStaffById(String staffId);

    /**
     * Updates profile, operational role, or active status of an existing staff member.
     * When deactivating a staff member (status = INACTIVE), checks relational constraints
     * across upcoming appointments and operational shifts, revokes active Keycloak sessions,
     * and invalidates cached service assignments.
     *
     * @param staffId staff UUID
     * @param request update payload
     * @return updated {@link StaffResponse}
     */
    StaffResponse updateStaff(String staffId, UpdateStaffRequest request);

    /**
     * Soft-deletes a staff member from the active roster with relational integrity checks.
     *
     * @param staffId staff UUID
     */
    void deleteStaff(String staffId);

    /**
     * Deactivates and soft-deletes a staff member, or performs hard deletion if explicitly requested.
     * Prevents deactivation/deletion if incomplete appointments or upcoming scheduled shifts exist.
     * Strictly rejects hard deletion if the staff member has historical records (tickets served, called, feedbacks).
     *
     * @param staffId staff UUID
     * @param hardDelete true for physical deletion, false for soft-delete (status = INACTIVE, is_deleted = true)
     */
    void deleteStaff(String staffId, boolean hardDelete);

    /**
     * Synchronizes existing staff profile with claims from Keycloak/IdP upon login,
     * or Just-In-Time (JIT) provisions a new staff record in the tenant database.
     *
     * @param tenantCode the verified tenant code
     * @param keycloakUserId Keycloak sub UUID
     * @param email authenticated email address
     * @param fullName full name claim from IdP / token
     * @param roles client roles claim from token
     * @return updated or newly provisioned {@link TenantStaff}
     */
    TenantStaff syncOrProvisionStaffOnLogin(
            String tenantCode,
            String keycloakUserId,
            String email,
            String fullName,
            List<String> roles
    );

    /**
     * Generates sequential staff code in format STAFF-xxxx.
     *
     * @return unique sequential badge code
     */
    String generateNextStaffCode();
}
