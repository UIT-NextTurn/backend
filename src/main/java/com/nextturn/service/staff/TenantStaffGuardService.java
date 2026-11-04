package com.nextturn.service.staff;

/**
 * Service contract enforcing relational integrity and operational guards for staff status deactivation and physical deletion.
 */
public interface TenantStaffGuardService {

    /**
     * Verifies that the staff member has no incomplete future appointments or scheduled shifts before deactivation or soft-delete.
     *
     * @param staffId staff UUID
     * @param staffCode badge identifier
     * @param fullName staff full name
     * @param tenantCode active facility code
     * @param action descriptive action name for logging
     */
    void validateNoActiveCommitmentsForDeactivation(String staffId, String staffCode, String fullName, String tenantCode, String action);

    /**
     * Strictly verifies that the staff member has no historical records (queue tickets, customer ratings, completed shifts)
     * before physical (hard) deletion from the database.
     *
     * @param staffId staff UUID
     * @param staffCode badge identifier
     * @param fullName staff full name
     * @param tenantCode active facility code
     */
    void validateSafeForHardDelete(String staffId, String staffCode, String fullName, String tenantCode);
}
