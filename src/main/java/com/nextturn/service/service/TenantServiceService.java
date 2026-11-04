package com.nextturn.service.service;

import com.nextturn.dto.service.CreateServiceRequest;
import com.nextturn.dto.service.ServiceResponse;
import com.nextturn.dto.service.UpdateServiceRequest;
import com.nextturn.enums.ServiceStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;

/**
 * Service contract managing tenant service mutations, standard duration configuration,
 * relational integrity protection, and coordinating service cache updates.
 */
public interface TenantServiceService {

    /**
     * Creates a new service in the active tenant.
     * Enforces unique service name within the facility schema and auto-assigns sequential SVC-xxxx code.
     *
     * @param request service creation payload
     * @return created {@link ServiceResponse}
     */
    ServiceResponse createService(CreateServiceRequest request);

    /**
     * Updates service information, operational status, and configures standard service duration.
     *
     * @param serviceId service unique ID
     * @param request service update payload
     * @return updated {@link ServiceResponse}
     */
    ServiceResponse updateService(String serviceId, UpdateServiceRequest request);

    /**
     * Safely soft-deletes a service after relational integrity verification.
     * Rejects deletion if active appointments or queue tickets exist.
     *
     * @param serviceId service unique ID
     */
    void deleteService(String serviceId);

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

    /**
     * Generates an auto-incremented service code in the format SVC-xxxx.
     *
     * @return unique service code
     */
    String generateNextServiceCode();
}
