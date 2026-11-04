package com.nextturn.service.staff;

import com.nextturn.dto.service.ServiceResponse;
import com.nextturn.dto.staff.AssignStaffServicesRequest;
import com.nextturn.dto.staff.StaffResponse;

import java.util.List;

/**
 * Service contract managing staff-service qualification assignments and associated Valkey cache invalidation.
 */
public interface TenantStaffAssignmentService {

    /**
     * Assigns a list of services to an encounter staff member:
     * 1. Validates that staff exists and is currently ACTIVE.
     * 2. Validates that all requested services exist in the tenant catalog and are ACTIVE.
     * 3. Updates the many-to-many junction table tenant__staff_services.
     * 4. Invalidates and clears cache on Valkey for affected service staff lists.
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
     * Evicts Valkey cache entries for all services currently assigned to a staff member.
     *
     * @param tenantCode active facility code
     * @param staffId staff member UUID
     */
    void evictAssignedServicesCache(String tenantCode, String staffId);
}
