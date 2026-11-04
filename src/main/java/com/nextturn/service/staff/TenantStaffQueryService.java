package com.nextturn.service.staff;

import com.nextturn.dto.staff.StaffResponse;
import com.nextturn.enums.StaffRole;
import com.nextturn.enums.StaffStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * Read-only query service contract for tenant staff members, roster pagination, and profile lookups.
 */
public interface TenantStaffQueryService {

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
     * @return {@link StaffResponse} with assigned services
     */
    StaffResponse getStaffById(String staffId);
}
