package com.nextturn.service.ticket;

import com.nextturn.entity.TenantService;

import java.time.LocalDate;

/**
 * Contract for generating strictly monotonic, continuous, daily-resetting queue ticket sequences
 * and formatted ticket codes (e.g., {@code A-001} .. {@code A-999}, expanding seamlessly to {@code A-1000})
 * protected by Valkey distributed locks and atomic Lua scripts (REQ-TICKET-01).
 */
public interface TenantTicketSequenceGenerator {

    /**
     * Value object representing a generated sequence number and formatted ticket code.
     */
    record GeneratedTicketNumber(int sequenceNumber, String ticketCode) {}

    /**
     * Acquires a Valkey distributed mutex lock for the given tenant/service/date queue.
     *
     * @param tenantCode tenant code
     * @param serviceId  service UUID
     * @param workDate   operating work date
     * @return unique lock owner token (UUID), or null if Valkey is unavailable
     */
    String acquireTicketLock(String tenantCode, String serviceId, LocalDate workDate);

    /**
     * Safely releases the Valkey distributed mutex lock using an atomic Lua check-and-delete script.
     *
     * @param tenantCode tenant code
     * @param serviceId  service UUID
     * @param workDate   operating work date
     * @param ownerToken owner token returned by {@link #acquireTicketLock}
     */
    void releaseTicketLock(String tenantCode, String serviceId, LocalDate workDate, String ownerToken);

    /**
     * Generates the next strictly monotonic sequence number and formatted ticket code
     * using an atomic Valkey Lua script (or database MAX+1 fallback if Valkey is offline).
     *
     * @param tenantCode tenant code
     * @param service    target service entity
     * @param workDate   operating work date
     * @return generated {@link GeneratedTicketNumber}
     */
    GeneratedTicketNumber nextTicketNumber(String tenantCode, TenantService service, LocalDate workDate);

    /**
     * Derives the alphabetical queue prefix for a service (e.g., {@code SVC-0001 -> "A"}, {@code SVC-0002 -> "B"}, {@code SVC-0027 -> "AA"}).
     *
     * @param service target service entity
     * @return uppercase alphabetical prefix
     */
    String resolveServicePrefix(TenantService service);

    /**
     * Formats a ticket code from its prefix and monotonic sequence number.
     * Uses minimum 3 zero-padded digits ({@code A-001} .. {@code A-999}) and automatically expands
     * to 4+ digits when sequence reaches 1000+ ({@code A-1000}).
     *
     * @param prefix         service prefix
     * @param sequenceNumber positive monotonic sequence number
     * @return formatted ticket code
     */
    String formatTicketCode(String prefix, int sequenceNumber);
}
