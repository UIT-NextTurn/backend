package com.nextturn.repository.ticket;

import com.nextturn.entity.TenantQueueTicket;
import com.nextturn.enums.TicketStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Spring Data JPA repository for {@link TenantQueueTicket} in the tenant-isolated schema.
 */
@Repository
public interface TenantQueueTicketRepository extends JpaRepository<TenantQueueTicket, String> {

    @Query("""
        SELECT COALESCE(MAX(t.sequenceNumber), 0)
        FROM TenantQueueTicket t
        WHERE t.service.id = :serviceId
          AND t.workDate = :workDate
    """)
    Integer findMaxSequenceNumberByServiceAndWorkDate(
            @Param("serviceId") String serviceId,
            @Param("workDate") LocalDate workDate
    );

    @Query("""
        SELECT t FROM TenantQueueTicket t
        LEFT JOIN FETCH t.service
        LEFT JOIN FETCH t.appointment
        LEFT JOIN FETCH t.receptionPoint
        LEFT JOIN FETCH t.counterStaff
        LEFT JOIN FETCH t.provider
        WHERE t.id = :id
    """)
    Optional<TenantQueueTicket> findByIdWithDetails(@Param("id") String id);

    @Query("""
        SELECT t FROM TenantQueueTicket t
        LEFT JOIN FETCH t.service
        LEFT JOIN FETCH t.appointment
        LEFT JOIN FETCH t.receptionPoint
        LEFT JOIN FETCH t.counterStaff
        LEFT JOIN FETCH t.provider
        WHERE LOWER(t.ticketCode) = LOWER(:ticketCode)
          AND t.workDate = :workDate
    """)
    Optional<TenantQueueTicket> findByTicketCodeIgnoreCaseAndWorkDateWithDetails(
            @Param("ticketCode") String ticketCode,
            @Param("workDate") LocalDate workDate
    );

    @Query("""
        SELECT t FROM TenantQueueTicket t
        LEFT JOIN FETCH t.service
        LEFT JOIN FETCH t.appointment
        LEFT JOIN FETCH t.receptionPoint
        LEFT JOIN FETCH t.counterStaff
        LEFT JOIN FETCH t.provider
        WHERE t.appointment.id = :appointmentId
    """)
    Optional<TenantQueueTicket> findByAppointmentIdWithDetails(@Param("appointmentId") String appointmentId);

    @Query("""
        SELECT t FROM TenantQueueTicket t
        LEFT JOIN FETCH t.service
        LEFT JOIN FETCH t.appointment
        LEFT JOIN FETCH t.receptionPoint
        LEFT JOIN FETCH t.counterStaff
        LEFT JOIN FETCH t.provider
        WHERE t.customerPhone = :customerPhone
          AND t.workDate = :workDate
          AND t.status IN :statuses
        ORDER BY t.issuedAt DESC
    """)
    List<TenantQueueTicket> findActiveByCustomerPhoneAndWorkDate(
            @Param("customerPhone") String customerPhone,
            @Param("workDate") LocalDate workDate,
            @Param("statuses") List<TicketStatus> statuses
    );

    @Query("""
        SELECT t FROM TenantQueueTicket t
        LEFT JOIN FETCH t.service
        LEFT JOIN FETCH t.appointment
        LEFT JOIN FETCH t.receptionPoint
        LEFT JOIN FETCH t.counterStaff
        LEFT JOIN FETCH t.provider
        WHERE t.workDate = :workDate
          AND (:serviceId IS NULL OR t.service.id = :serviceId)
        ORDER BY t.issuedAt ASC, t.sequenceNumber ASC
    """)
    List<TenantQueueTicket> findByWorkDateAndOptionalServiceWithDetails(
            @Param("workDate") LocalDate workDate,
            @Param("serviceId") String serviceId
    );

    /**
     * Counts tickets ahead of a specific waiting ticket for the same service today (REQ-TICKET-02).
     */
    @Query("""
        SELECT COUNT(t) FROM TenantQueueTicket t
        WHERE t.service.id = :serviceId
          AND t.workDate = :workDate
          AND t.status = 'WAITING'
          AND (t.issuedAt < :issuedAt OR (t.issuedAt = :issuedAt AND t.sequenceNumber < :sequenceNumber))
    """)
    long countWaitingTicketsAhead(
            @Param("serviceId") String serviceId,
            @Param("workDate") LocalDate workDate,
            @Param("issuedAt") LocalDateTime issuedAt,
            @Param("sequenceNumber") Integer sequenceNumber
    );

    /**
     * Finds the ticket most recently called or currently being served for a service on a given date.
     */
    @Query("""
        SELECT t FROM TenantQueueTicket t
        WHERE t.service.id = :serviceId
          AND t.workDate = :workDate
          AND t.status IN ('SERVING', 'CALLING')
        ORDER BY COALESCE(t.servedAt, t.calledAt, t.issuedAt) DESC
    """)
    List<TenantQueueTicket> findActiveServingOrCallingByServiceAndWorkDate(
            @Param("serviceId") String serviceId,
            @Param("workDate") LocalDate workDate
    );

    long countByWorkDate(LocalDate workDate);

    long countByServiceIdAndWorkDate(String serviceId, LocalDate workDate);

    boolean existsByServiceIdAndCustomerPhoneAndWorkDateAndStatusIn(
            String serviceId,
            String customerPhone,
            LocalDate workDate,
            List<TicketStatus> statuses
    );

    /**
     * Checks whether a counter staff member currently has a ticket in the given state today (e.g. CALLING).
     */
    boolean existsByCounterStaffIdAndWorkDateAndStatus(
            String counterStaffId,
            LocalDate workDate,
            TicketStatus status
    );

    /**
     * Extracts the single highest-priority WAITING ticket using SRS REQ-QC-01 dynamic scoring
     * (W_base: APPOINTMENT=100, WALK_IN=0 + 5 pts/min waiting) with FOR UPDATE SKIP LOCKED
     * to prevent concurrent counter staff from picking the same ticket.
     */
    @Query(value = """
        SELECT * FROM tenant__queue_tickets
        WHERE work_date = :workDate
          AND service_id = :serviceId
          AND status = 'WAITING'
        ORDER BY
          ((CASE WHEN customer_type = 'APPOINTMENT' THEN 100 ELSE 0 END)
           + (GREATEST(0, TIMESTAMPDIFF(MINUTE, issued_at, :now)) * 5)) DESC,
          issued_at ASC,
          sequence_number ASC
        LIMIT 1
        FOR UPDATE SKIP LOCKED
    """, nativeQuery = true)
    Optional<TenantQueueTicket> findNextPriorityWaitingTicketForUpdate(
            @Param("serviceId") String serviceId,
            @Param("workDate") LocalDate workDate,
            @Param("now") LocalDateTime now
    );

    /**
     * Counts currently SERVING tickets assigned to each specialist on the given date.
     */
    @Query("""
        SELECT t.provider.id, COUNT(t) FROM TenantQueueTicket t
        WHERE t.workDate = :workDate
          AND t.status = 'SERVING'
          AND t.provider IS NOT NULL
        GROUP BY t.provider.id
    """)
    List<Object[]> countServingTicketsGroupedByProvider(@Param("workDate") LocalDate workDate);

    /**
     * Counts daily handled tickets (SERVING or COMPLETED) per specialist for least-loaded balancing (REQ-QC-02).
     */
    @Query("""
        SELECT t.provider.id, COUNT(t) FROM TenantQueueTicket t
        WHERE t.workDate = :workDate
          AND t.status IN ('SERVING', 'COMPLETED')
          AND t.provider IS NOT NULL
        GROUP BY t.provider.id
    """)
    List<Object[]> countHandledTicketsGroupedByProvider(@Param("workDate") LocalDate workDate);

    /**
     * Fetches all active SERVING tickets with service duration metadata for auto-complete safety net (REQ-QC-03).
     */
    @Query("""
        SELECT t FROM TenantQueueTicket t
        LEFT JOIN FETCH t.service
        LEFT JOIN FETCH t.counterStaff
        LEFT JOIN FETCH t.provider
        WHERE t.status = 'SERVING'
          AND t.servedAt IS NOT NULL
    """)
    List<TenantQueueTicket> findAllServingWithDetails();

    /**
     * Fetches currently active CALLING and SERVING tickets for Lobby TV Display (REQ-QC-01, REQ-QC-04).
     */
    @Query("""
        SELECT t FROM TenantQueueTicket t
        LEFT JOIN FETCH t.service
        LEFT JOIN FETCH t.counterStaff
        LEFT JOIN FETCH t.provider
        WHERE t.workDate = :workDate
          AND t.status IN ('CALLING', 'SERVING')
        ORDER BY COALESCE(t.calledAt, t.servedAt, t.issuedAt) DESC
    """)
    List<TenantQueueTicket> findActiveLobbyTicketsByWorkDate(@Param("workDate") LocalDate workDate);

    /**
     * Fetches all WAITING tickets for a service on a given date for approaching-turn alert evaluation (REQ-NOTI-02).
     */
    @Query("""
        SELECT t FROM TenantQueueTicket t
        LEFT JOIN FETCH t.service
        WHERE t.service.id = :serviceId
          AND t.workDate = :workDate
          AND t.status = 'WAITING'
        ORDER BY t.issuedAt ASC, t.sequenceNumber ASC
    """)
    List<TenantQueueTicket> findWaitingByServiceAndWorkDateWithService(
            @Param("serviceId") String serviceId,
            @Param("workDate") LocalDate workDate
    );
}
