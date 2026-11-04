package com.nextturn.service.service;

import com.nextturn.dto.service.ServiceResponse;
import com.nextturn.entity.TenantService;
import com.nextturn.enums.ServiceStatus;
import com.nextturn.exception.service.ServiceErrorCode;
import com.nextturn.exception.service.ServiceException;
import com.nextturn.exception.tenant.TenantErrorCode;
import com.nextturn.exception.tenant.TenantException;
import com.nextturn.infrastructure.multitenancy.TenantContextHolder;
import com.nextturn.repository.service.TenantServiceRepository;
import com.nextturn.service.BaseService;
import com.nextturn.service.tenant.TenantCacheService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Service managing read-only service operations, multi-criteria search,
 * public booking service lookups, and Valkey cache retrieval.
 */
@Service
@Transactional(readOnly = true)
public class TenantServiceQueryService extends BaseService {

    private final TenantServiceRepository serviceRepository;
    private final TenantCacheService tenantCacheService;

    public TenantServiceQueryService(
            TenantServiceRepository serviceRepository,
            TenantCacheService tenantCacheService
    ) {
        super();
        this.serviceRepository = serviceRepository;
        this.tenantCacheService = tenantCacheService;
    }

    /**
     * Multi-criteria search and pagination for tenant administration.
     * Filters by keyword (name or code) and status, achieving response times <= 200ms.
     *
     * @param keyword search term
     * @param status status filter
     * @param pageable pagination parameters
     * @return page of {@link ServiceResponse}
     */
    public Page<ServiceResponse> searchServices(String keyword, ServiceStatus status, Pageable pageable) {
        resolveCurrentTenantCode();
        String cleanKeyword = (keyword != null && !keyword.isBlank()) ? keyword.trim() : null;
        return serviceRepository.searchServices(cleanKeyword, status, pageable)
                .map(ServiceResponse::fromEntity);
    }

    /**
     * Public and Customer-facing service retrieval for online appointment booking.
     * Allows anonymous access filtered strictly to ACTIVE status services.
     * Context switch is performed safely using caller-supplied tenant code.
     *
     * @param tenantCode target tenant facility code
     * @param keyword optional search keyword
     * @param status optional status override (defaults to ACTIVE)
     * @param pageable pagination parameters
     * @return page of {@link ServiceResponse}
     */
    public Page<ServiceResponse> searchPublicServices(String tenantCode, String keyword, ServiceStatus status, Pageable pageable) {
        if (tenantCode == null || tenantCode.isBlank()) {
            tenantCode = TenantContextHolder.getTenantCode();
        }
        if (tenantCode == null || tenantCode.isBlank()) {
            throw new TenantException(
                    TenantErrorCode.TENANT_CONTEXT_MISSING,
                    "Multi-tenant routing context is missing. Please provide X-Tenant-Code header or tenantCode parameter."
            );
        }

        String previousTenant = TenantContextHolder.getTenantCode();
        try {
            TenantContextHolder.setTenantCode(tenantCode.trim().toLowerCase());
            ServiceStatus targetStatus = status != null ? status : ServiceStatus.ACTIVE;
            String cleanKeyword = (keyword != null && !keyword.isBlank()) ? keyword.trim() : null;
            return serviceRepository.searchServices(cleanKeyword, targetStatus, pageable)
                    .map(ServiceResponse::fromEntity);
        } finally {
            if (previousTenant != null) {
                TenantContextHolder.setTenantCode(previousTenant);
            } else {
                TenantContextHolder.clear();
            }
        }
    }

    /**
     * Retrieves service details by unique ID.
     *
     * @param serviceId service unique ID
     * @return {@link ServiceResponse}
     */
    public ServiceResponse getServiceById(String serviceId) {
        resolveCurrentTenantCode();
        TenantService service = serviceRepository.findByIdAndIsDeletedFalse(serviceId)
                .orElseThrow(() -> new ServiceException(
                        ServiceErrorCode.SERVICE_NOT_FOUND,
                        "Dịch vụ không tồn tại trong hệ thống hoặc đã bị xóa."
                ));
        return ServiceResponse.fromEntity(service);
    }

    /**
     * Lists services with pagination.
     *
     * @param pageable pagination parameters
     * @return page of {@link ServiceResponse}
     */
    public Page<ServiceResponse> getServiceList(Pageable pageable) {
        resolveCurrentTenantCode();
        return serviceRepository.findAllByIsDeletedFalse(pageable)
                .map(ServiceResponse::fromEntity);
    }

    /**
     * Lists active services for appointment booking and queue tickets.
     * Retrieves from Valkey cache first, falling back to database query and updating cache.
     *
     * @return list of active {@link ServiceResponse}
     */
    public List<ServiceResponse> getActiveServices() {
        String tenantCode = resolveCurrentTenantCode();
        List<ServiceResponse> cached = tenantCacheService.getCachedTenantServices(tenantCode);
        if (cached != null && !cached.isEmpty()) {
            return cached;
        }

        List<ServiceResponse> services = serviceRepository.findAllByStatusAndIsDeletedFalse(ServiceStatus.ACTIVE)
                .stream()
                .map(ServiceResponse::fromEntity)
                .collect(Collectors.toList());

        tenantCacheService.cacheTenantServices(tenantCode, services);
        return services;
    }
}

