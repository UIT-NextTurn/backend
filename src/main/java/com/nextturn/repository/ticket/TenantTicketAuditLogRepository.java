package com.nextturn.repository.ticket;

import com.nextturn.entity.TenantTicketAuditLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Spring Data JPA repository for {@link TenantTicketAuditLog} tracking ticket state transitions.
 */
@Repository
public interface TenantTicketAuditLogRepository extends JpaRepository<TenantTicketAuditLog, String> {

    List<TenantTicketAuditLog> findByTicketIdOrderByCreatedAtAsc(String ticketId);
}
