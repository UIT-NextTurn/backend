package com.nextturn.repository.staff;

import com.nextturn.entity.TenantStaff;
import com.nextturn.enums.StaffRole;
import com.nextturn.enums.StaffStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Spring Data JPA repository for {@link TenantStaff} operating within the active tenant schema.
 */
@Repository
public interface TenantStaffRepository extends JpaRepository<TenantStaff, String> {

    Optional<TenantStaff> findByIdAndIsDeletedFalse(String id);

    Optional<TenantStaff> findByKeycloakUserIdAndIsDeletedFalse(String keycloakUserId);

    Optional<TenantStaff> findByEmailAndIsDeletedFalse(String email);

    Optional<TenantStaff> findByStaffCodeAndIsDeletedFalse(String staffCode);

    boolean existsByEmail(String email);

    boolean existsByEmailAndIsDeletedFalse(String email);

    boolean existsByPhoneNumber(String phoneNumber);

    boolean existsByPhoneNumberAndIsDeletedFalse(String phoneNumber);

    boolean existsByStaffCode(String staffCode);

    boolean existsByStaffCodeAndIsDeletedFalse(String staffCode);

    Page<TenantStaff> findAllByIsDeletedFalse(Pageable pageable);

    List<TenantStaff> findAllByRoleAndStatusAndIsDeletedFalse(StaffRole role, StaffStatus status);

    long countByStatusAndIsDeletedFalse(StaffStatus status);

    @Query("SELECT MAX(s.staffCode) FROM TenantStaff s WHERE s.staffCode LIKE 'STAFF-%'")
    Optional<String> findMaxStaffCode();

    @Query("""
        SELECT s FROM TenantStaff s
        JOIN TenantStaffServiceAssignment ssa ON ssa.staff.id = s.id
        WHERE ssa.service.id = :serviceId AND s.isDeleted = false AND s.status = 'ACTIVE'
    """)
    List<TenantStaff> findActiveStaffByServiceId(@Param("serviceId") String serviceId);

    /**
     * Multi-criteria dynamic search across staff name, email, phone, badge code, role, and status.
     */
    @Query("""
        SELECT s FROM TenantStaff s
        WHERE s.isDeleted = false
          AND (:role IS NULL OR s.role = :role)
          AND (:status IS NULL OR s.status = :status)
          AND (
              :keyword IS NULL OR :keyword = ''
              OR LOWER(s.fullName) LIKE LOWER(CONCAT('%', :keyword, '%'))
              OR LOWER(s.email) LIKE LOWER(CONCAT('%', :keyword, '%'))
              OR LOWER(s.phoneNumber) LIKE LOWER(CONCAT('%', :keyword, '%'))
              OR LOWER(s.staffCode) LIKE LOWER(CONCAT('%', :keyword, '%'))
          )
    """)
    Page<TenantStaff> searchStaff(
            @Param("keyword") String keyword,
            @Param("role") StaffRole role,
            @Param("status") StaffStatus status,
            Pageable pageable
    );

    /**
     * Checks if the staff member has upcoming scheduled shifts in the operational work schedule.
     */
    @Query(value = "SELECT COUNT(*) FROM tenant__shifts WHERE staff_id = :staffId AND status = 'SCHEDULED' AND work_date >= CURRENT_DATE", nativeQuery = true)
    long countUpcomingShiftsByStaffId(@Param("staffId") String staffId);

    /**
     * Identifies pending or confirmed future appointments associated with this staff's shifts or active tickets.
     */
    @Query(value = """
        SELECT DISTINCT a.appointment_code
        FROM tenant__appointments a
        JOIN tenant__time_slots ts ON a.slot_id = ts.id
        JOIN tenant__shifts sh ON sh.service_id = a.service_id 
            AND sh.work_date = ts.work_date 
            AND sh.shift_start <= ts.slot_start 
            AND sh.shift_end >= ts.slot_end
        WHERE sh.staff_id = :staffId
          AND sh.status = 'SCHEDULED'
          AND ts.work_date >= CURRENT_DATE
          AND a.status IN ('PENDING', 'CONFIRMED', 'CHECKED_IN')
        UNION
        SELECT DISTINCT a.appointment_code
        FROM tenant__queue_tickets qt
        JOIN tenant__appointments a ON qt.appointment_id = a.id
        WHERE (qt.provider_id = :staffId OR qt.counter_staff_id = :staffId)
          AND qt.status IN ('WAITING', 'CALLING', 'SERVING')
    """, nativeQuery = true)
    List<String> findUpcomingAppointmentCodesByStaffId(@Param("staffId") String staffId);

    /**
     * Checks if the staff member has historical queue ticket servicing or calling interactions.
     */
    @Query(value = "SELECT COUNT(*) FROM tenant__queue_tickets WHERE counter_staff_id = :staffId OR provider_id = :staffId", nativeQuery = true)
    long countHistoricalQueueTicketsByStaffId(@Param("staffId") String staffId);

    /**
     * Checks if the staff member has received customer ratings or feedbacks.
     */
    @Query(value = "SELECT COUNT(*) FROM tenant__feedbacks WHERE provider_id = :staffId", nativeQuery = true)
    long countHistoricalFeedbacksByStaffId(@Param("staffId") String staffId);

    /**
     * Checks if the staff member has completed historical shifts.
     */
    @Query(value = "SELECT COUNT(*) FROM tenant__shifts WHERE staff_id = :staffId AND status = 'COMPLETED'", nativeQuery = true)
    long countHistoricalShiftsByStaffId(@Param("staffId") String staffId);
}
