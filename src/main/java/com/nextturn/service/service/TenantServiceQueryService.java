package com.nextturn.service.service;

import com.nextturn.dto.service.ServiceResponse;
import com.nextturn.enums.ServiceStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;

/**
 * Service contract for read-only service queries, multi-criteria search,
 * public booking service lookups, and Valkey cache retrieval.
 */
public interface TenantServiceQueryService {

    /**
     * Multi-criteria search and pagination for tenant administration.
     *
     * @param keyword search term
     * @param status status filter
     * @param pageable pagination parameters
     * @return page of {@link ServiceResponse}
     */
    Page<ServiceResponse> searchServices(String keyword, ServiceStatus status, Pageable pageable);

    /**
     * Public and customer-facing service retrieval for online appointment booking.
     *
     * @param tenantCode target tenant facility code
     * @param keyword optional search keyword
     * @param status optional status override (defaults to ACTIVE)
     * @param pageable pagination parameters
     * @return page of {@link ServiceResponse}
     */
    Page<ServiceResponse> searchPublicServices(String tenantCode, String keyword, ServiceStatus status, Pageable pageable);

    /**
     * Retrieves service details by unique ID.
     *
     * @param serviceId service unique ID
     * @return {@link ServiceResponse}
     */
    ServiceResponse getServiceById(String serviceId);

    /**
     * Lists services with pagination.
     *
     * @param pageable pagination parameters
     * @return page of {@link ServiceResponse}
     */
    Page<ServiceResponse> getServiceList(Pageable pageable);

    /**
     * Lists active services for appointment booking and queue tickets.
     *
     * @return list of active {@link ServiceResponse}
     */
    List<ServiceResponse> getActiveServices();
}
