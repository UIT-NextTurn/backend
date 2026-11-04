package com.nextturn.service.calendar;

import com.nextturn.dto.calendar.SlotHoldingResponse;

/**
 * Service contract for managing temporary time slot holding, race condition prevention,
 * distributed locking, and automatic release of expired slot reservations.
 */
public interface TenantTimeSlotHoldingService {

    /**
     * Temporarily holds an available time slot for customer booking checkout.
     * Prevents race conditions using Valkey distributed lock and database row locking.
     *
     * @param slotId time slot UUID
     * @param holdingMinutes temporary holding duration in minutes (default: 10)
     * @return holding lease response
     */
    SlotHoldingResponse holdSlot(String slotId, int holdingMinutes);

    /**
     * Releases a held time slot back to AVAILABLE status when holding token matches.
     *
     * @param slotId time slot UUID
     * @param holdingToken holding token issued during hold acquisition
     */
    void releaseHeldSlot(String slotId, String holdingToken);

    /**
     * Confirms booking on a held slot, advancing status to BOOKED and incrementing booked count.
     *
     * @param slotId time slot UUID
     * @param holdingToken holding token issued during hold acquisition
     */
    void confirmSlot(String slotId, String holdingToken);

    /**
     * Reconciles and releases all slots whose holding duration has expired across the tenant.
     *
     * @return count of released slots
     */
    int releaseExpiredHoldingSlots();
}

