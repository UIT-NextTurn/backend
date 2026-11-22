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
        WHERE t.id = :id
    """)
    Optional<TenantQueueTicket> findByIdWithDetails(@Param("id") String id);

    @Query("""
        SELECT t FROM TenantQueueTicket t
        LEFT JOIN FETCH t.service
        LEFT JOIN FETCH t.appointment
        LEFT JOIN FETCH t.receptionPoint
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
        WHERE t.appointment.id = :appointmentId
    """)
    Optional<TenantQueueTicket> findByAppointmentIdWithDetails(@Param("appointmentId") String appointmentId);

    @Query("""
        SELECT t FROM TenantQueueTicket t
        LEFT JOIN FETCH t.service
        LEFT JOIN FETCH t.appointment
        LEFT JOIN FETCH t.receptionPoint
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
        LEFT JOIN FETCH t.receptionPoint
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
}
