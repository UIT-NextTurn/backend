package com.nextturn.repository.calendar;

import com.nextturn.entity.TenantTimeSlot;
import com.nextturn.enums.TimeSlotStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

/**
 * Spring Data JPA repository for {@link TenantTimeSlot} managing capacity booking windows.
 */
@Repository
public interface TenantTimeSlotRepository extends JpaRepository<TenantTimeSlot, String> {

    @Query("SELECT ts FROM TenantTimeSlot ts JOIN FETCH ts.service s WHERE ts.service.id = :serviceId AND ts.workDate = :workDate ORDER BY ts.slotStart ASC")
    List<TenantTimeSlot> findByServiceIdAndWorkDateOrderBySlotStartAsc(
            @Param("serviceId") String serviceId,
            @Param("workDate") LocalDate workDate
    );

    @Query("SELECT ts FROM TenantTimeSlot ts JOIN FETCH ts.service s WHERE ts.service.id = :serviceId AND ts.workDate = :workDate AND ts.status = :status ORDER BY ts.slotStart ASC")
    List<TenantTimeSlot> findByServiceIdAndWorkDateAndStatusOrderBySlotStartAsc(
            @Param("serviceId") String serviceId,
            @Param("workDate") LocalDate workDate,
            @Param("status") TimeSlotStatus status
    );

    @Query("SELECT ts FROM TenantTimeSlot ts WHERE ts.service.id = :serviceId AND ts.workDate = :workDate AND ts.slotStart = :slotStart AND ts.slotEnd = :slotEnd")
    Optional<TenantTimeSlot> findByServiceIdAndWorkDateAndSlotStartAndSlotEnd(
            @Param("serviceId") String serviceId,
            @Param("workDate") LocalDate workDate,
            @Param("slotStart") LocalTime slotStart,
            @Param("slotEnd") LocalTime slotEnd
    );

    @Query("SELECT ts FROM TenantTimeSlot ts WHERE ts.service.id = :serviceId AND ts.workDate = :workDate AND ts.slotStart >= :windowStart AND ts.slotEnd <= :windowEnd")
    List<TenantTimeSlot> findSlotsInWindow(
            @Param("serviceId") String serviceId,
            @Param("workDate") LocalDate workDate,
            @Param("windowStart") LocalTime windowStart,
            @Param("windowEnd") LocalTime windowEnd
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT ts FROM TenantTimeSlot ts WHERE ts.id = :id")
    Optional<TenantTimeSlot> findByIdForUpdate(@Param("id") String id);

    Optional<TenantTimeSlot> findByHoldingToken(String holdingToken);

    @Query("SELECT ts FROM TenantTimeSlot ts WHERE ts.status = 'HOLDING' AND ts.holdingExpiresAt < :now")
    List<TenantTimeSlot> findExpiredHoldingSlots(@Param("now") LocalDateTime now);

    @Query("""
        SELECT COALESCE(SUM(ts.bookedCount), 0) FROM TenantTimeSlot ts
        WHERE ts.service.id = :serviceId
          AND ts.workDate = :workDate
          AND ts.slotStart >= :start
          AND ts.slotEnd <= :end
    """)
    long sumBookedCountInWindow(
            @Param("serviceId") String serviceId,
            @Param("workDate") LocalDate workDate,
            @Param("start") LocalTime start,
            @Param("end") LocalTime end
    );

    @Query("""
        SELECT COUNT(ts) FROM TenantTimeSlot ts
        WHERE ts.service.id = :serviceId
          AND ts.workDate = :workDate
          AND ts.slotStart >= :start
          AND ts.slotEnd <= :end
          AND (ts.status = 'BOOKED' OR ts.bookedCount > 0)
    """)
    long countBookedSlotsInWindow(
            @Param("serviceId") String serviceId,
            @Param("workDate") LocalDate workDate,
            @Param("start") LocalTime start,
            @Param("end") LocalTime end
    );
}

