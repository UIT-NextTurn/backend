package com.nextturn.repository.calendar;

import com.nextturn.entity.TenantShift;
import com.nextturn.enums.ShiftStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

/**
 * Spring Data JPA repository for {@link TenantShift} managing operational staff work schedules.
 */
@Repository
public interface TenantShiftRepository extends JpaRepository<TenantShift, String> {

    @Query("SELECT s FROM TenantShift s LEFT JOIN FETCH s.staff LEFT JOIN FETCH s.service WHERE s.id = :id")
    Optional<TenantShift> findByIdWithDetails(@Param("id") String id);

    /**
     * Checks if staff member already has an overlapping active shift on the given date.
     * Two intervals [S1, E1) and [S2, E2) overlap if S1 < E2 and E1 > S2.
     */
    @Query("""
        SELECT s FROM TenantShift s
        WHERE s.staff.id = :staffId
          AND s.workDate = :workDate
          AND s.status != 'CANCELLED'
          AND s.shiftStart < :shiftEnd
          AND s.shiftEnd > :shiftStart
        ORDER BY s.shiftStart ASC
    """)
    List<TenantShift> findOverlappingShifts(
            @Param("staffId") String staffId,
            @Param("workDate") LocalDate workDate,
            @Param("shiftStart") LocalTime shiftStart,
            @Param("shiftEnd") LocalTime shiftEnd
    );

    /**
     * Checks for overlapping active shifts for the staff member excluding a specific shift ID during adjustment.
     */
    @Query("""
        SELECT s FROM TenantShift s
        WHERE s.staff.id = :staffId
          AND s.workDate = :workDate
          AND s.id != :excludedShiftId
          AND s.status != 'CANCELLED'
          AND s.shiftStart < :shiftEnd
          AND s.shiftEnd > :shiftStart
        ORDER BY s.shiftStart ASC
    """)
    List<TenantShift> findOverlappingShiftsExcludingShift(
            @Param("staffId") String staffId,
            @Param("workDate") LocalDate workDate,
            @Param("shiftStart") LocalTime shiftStart,
            @Param("shiftEnd") LocalTime shiftEnd,
            @Param("excludedShiftId") String excludedShiftId
    );

    /**
     * Counts active, non-deleted staff scheduled to work during a specific service time slot window.
     */
    @Query("""
        SELECT COUNT(DISTINCT s.staff.id) FROM TenantShift s
        JOIN s.staff st
        WHERE s.service.id = :serviceId
          AND s.workDate = :workDate
          AND s.status = 'SCHEDULED'
          AND st.status = 'ACTIVE'
          AND st.isDeleted = false
          AND s.shiftStart <= :slotStart
          AND s.shiftEnd >= :slotEnd
    """)
    int countActiveStaffCoveringSlot(
            @Param("serviceId") String serviceId,
            @Param("workDate") LocalDate workDate,
            @Param("slotStart") LocalTime slotStart,
            @Param("slotEnd") LocalTime slotEnd
    );

    /**
     * Finds active shifts covering a specific time slot window.
     */
    @Query("""
        SELECT s FROM TenantShift s
        JOIN FETCH s.staff st
        WHERE s.service.id = :serviceId
          AND s.workDate = :workDate
          AND s.status = 'SCHEDULED'
          AND st.status = 'ACTIVE'
          AND st.isDeleted = false
          AND s.shiftStart <= :slotStart
          AND s.shiftEnd >= :slotEnd
    """)
    List<TenantShift> findActiveShiftsCoveringSlot(
            @Param("serviceId") String serviceId,
            @Param("workDate") LocalDate workDate,
            @Param("slotStart") LocalTime slotStart,
            @Param("slotEnd") LocalTime slotEnd
    );

    /**
     * Finds all shifts for a staff member within a date range.
     */
    List<TenantShift> findByStaffIdAndWorkDateBetweenOrderByWorkDateAscShiftStartAsc(
            String staffId,
            LocalDate startDate,
            LocalDate endDate
    );

    /**
     * Multi-criteria search for shifts.
     */
    @Query("""
        SELECT s FROM TenantShift s
        LEFT JOIN FETCH s.staff st
        LEFT JOIN FETCH s.service sv
        WHERE (:workDate IS NULL OR s.workDate = :workDate)
          AND (:startDate IS NULL OR s.workDate >= :startDate)
          AND (:endDate IS NULL OR s.workDate <= :endDate)
          AND (:staffId IS NULL OR s.staff.id = :staffId)
          AND (:serviceId IS NULL OR s.service.id = :serviceId)
          AND (:status IS NULL OR s.status = :status)
        ORDER BY s.workDate ASC, s.shiftStart ASC
    """)
    List<TenantShift> searchShifts(
            @Param("workDate") LocalDate workDate,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate,
            @Param("staffId") String staffId,
            @Param("serviceId") String serviceId,
            @Param("status") ShiftStatus status
    );
}

