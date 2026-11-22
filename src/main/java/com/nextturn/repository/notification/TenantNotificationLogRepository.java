package com.nextturn.repository.notification;

import com.nextturn.entity.TenantNotificationLog;
import com.nextturn.enums.NotificationChannel;
import com.nextturn.enums.NotificationDeliveryStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * Spring Data JPA repository for {@link TenantNotificationLog} supporting admin log audit and
 * customer complaint filtering by phone/contact or ticket code (REQ-NOTI-04).
 */
@Repository
public interface TenantNotificationLogRepository extends JpaRepository<TenantNotificationLog, String> {

    @Query(
            value = """
                SELECT l FROM TenantNotificationLog l
                LEFT JOIN FETCH l.ticket t
                LEFT JOIN FETCH l.appointment a
                WHERE (:recipient IS NULL OR :recipient = ''
                       OR LOWER(l.recipientContact) LIKE LOWER(CONCAT('%', :recipient, '%')))
                  AND (:ticketCode IS NULL OR :ticketCode = ''
                       OR LOWER(t.ticketCode) LIKE LOWER(CONCAT('%', :ticketCode, '%')))
                  AND (:channel IS NULL OR l.channel = :channel)
                  AND (:status IS NULL OR l.status = :status)
                  AND (:templateCode IS NULL OR :templateCode = ''
                       OR UPPER(l.templateCode) = UPPER(:templateCode))
                ORDER BY l.sentAt DESC
            """,
            countQuery = """
                SELECT COUNT(l) FROM TenantNotificationLog l
                LEFT JOIN l.ticket t
                WHERE (:recipient IS NULL OR :recipient = ''
                       OR LOWER(l.recipientContact) LIKE LOWER(CONCAT('%', :recipient, '%')))
                  AND (:ticketCode IS NULL OR :ticketCode = ''
                       OR LOWER(t.ticketCode) LIKE LOWER(CONCAT('%', :ticketCode, '%')))
                  AND (:channel IS NULL OR l.channel = :channel)
                  AND (:status IS NULL OR l.status = :status)
                  AND (:templateCode IS NULL OR :templateCode = ''
                       OR UPPER(l.templateCode) = UPPER(:templateCode))
            """
    )
    Page<TenantNotificationLog> searchLogs(
            @Param("recipient") String recipient,
            @Param("ticketCode") String ticketCode,
            @Param("channel") NotificationChannel channel,
            @Param("status") NotificationDeliveryStatus status,
            @Param("templateCode") String templateCode,
            Pageable pageable
    );
}
