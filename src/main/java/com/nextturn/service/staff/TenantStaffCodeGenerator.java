package com.nextturn.service.staff;

/**
 * Contract for generating unique sequential badge identification codes (STAFF-xxxx) for tenant staff.
 */
public interface TenantStaffCodeGenerator {

    /**
     * Generates sequential staff code in format STAFF-xxxx (e.g. STAFF-0001, STAFF-0002).
     *
     * @return unique sequential badge code
     */
    String generateNextStaffCode();
}
