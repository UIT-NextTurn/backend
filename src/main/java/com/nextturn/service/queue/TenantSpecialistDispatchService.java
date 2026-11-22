package com.nextturn.service.queue;

import com.nextturn.dto.queue.SpecialistLoadResponse;
import com.nextturn.entity.TenantService;
import com.nextturn.entity.TenantStaff;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/**
 * Contract for checking in-shift specialist capacity (REQ-QC-01) and performing
 * load-balanced specialist assignment during counter handoff (REQ-QC-02).
 */
public interface TenantSpecialistDispatchService {

    /**
     * Ensures at least one certified {@code SPECIALIST} is actively in-shift and free
     * ({@code Available_Specialists(serviceId) > 0}) before allowing counter staff to call next.
     */
    void assertSpecialistCapacityAvailable(TenantService service, LocalDate workDate, LocalTime currentTime);

    /**
     * Validates an explicitly chosen specialist or automatically selects the least-loaded
     * available in-shift specialist for the given service.
     */
    TenantStaff selectOrValidateSpecialist(String serviceId, String requestedProviderId, LocalDate workDate, LocalTime currentTime);

    /**
     * Lists in-shift certified specialists with live workload metrics for Kanban boards.
     */
    List<SpecialistLoadResponse> getSpecialistWorkloads(String serviceId, LocalDate workDate, LocalTime currentTime);
}
