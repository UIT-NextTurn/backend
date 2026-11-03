package com.nextturn.service.catalog;

import com.nextturn.constant.ServiceConstant;
import com.nextturn.dto.service.CreateServiceRequest;
import com.nextturn.dto.service.ServiceResponse;
import com.nextturn.dto.service.UpdateServiceRequest;
import com.nextturn.entity.TenantService;
import com.nextturn.enums.ServiceStatus;
import com.nextturn.exception.service.ServiceErrorCode;
import com.nextturn.exception.service.ServiceException;
import com.nextturn.exception.tenant.TenantErrorCode;
import com.nextturn.exception.tenant.TenantException;
import com.nextturn.infrastructure.multitenancy.TenantContextHolder;
import com.nextturn.repository.service.TenantServiceRepository;
import com.nextturn.service.tenant.TenantCacheService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Service managing tenant services catalog, validation, auto-generated service codes,
 * duration configuration, relational integrity protection, status updates, and multi-criteria search.
 */
@Service
public class TenantCatalogService {

    private static final Logger log = LoggerFactory.getLogger(TenantCatalogService.class);

    private final TenantServiceRepository serviceRepository;
    private final TenantCacheService tenantCacheService;
    private final TransactionTemplate transactionTemplate;

    @Autowired
    public TenantCatalogService(
            TenantServiceRepository serviceRepository,
            TenantCacheService tenantCacheService,
            @Autowired(required = false) TransactionTemplate transactionTemplate
    ) {
        this.serviceRepository = serviceRepository;
        this.tenantCacheService = tenantCacheService;
        this.transactionTemplate = transactionTemplate;
    }

    public TenantCatalogService(
            TenantServiceRepository serviceRepository,
            TenantCacheService tenantCacheService
    ) {
        this(serviceRepository, tenantCacheService, null);
    }

    private <T> T executeInTransaction(TransactionCallback<T> action) {
        if (transactionTemplate != null) {
            return transactionTemplate.execute(action);
        }
        return action.doInTransaction(null);
    }

    private String resolveCurrentTenantCode() {
        String tenantCode = TenantContextHolder.getTenantCode();
        if (tenantCode == null || tenantCode.isBlank()) {
            throw new TenantException(
                    TenantErrorCode.TENANT_CONTEXT_MISSING,
                    "Multi-tenant routing context is missing or could not be resolved from request headers/token"
            );
        }
        return tenantCode.trim().toLowerCase();
    }

    private void evictAndRefreshCache(String tenantCode) {
        tenantCacheService.evictTenantServiceCache(tenantCode);
        List<ServiceResponse> activeServices = serviceRepository.findAllByStatusAndIsDeletedFalse(ServiceStatus.ACTIVE)
                .stream()
                .map(ServiceResponse::fromEntity)
                .collect(Collectors.toList());
        tenantCacheService.cacheTenantServices(tenantCode, activeServices);
    }

    /**
     * Creates a new service in the tenant catalog.
     * Generates sequential service code (SVC-xxxx) and sets initial status to ACTIVE.
     *
     * @param request service creation payload
     * @return created {@link ServiceResponse}
     */
    public ServiceResponse createService(CreateServiceRequest request) {
        String tenantCode = resolveCurrentTenantCode();
        String normalizedName = request.getServiceName() != null ? request.getServiceName().trim() : "";

        if (normalizedName.isBlank()) {
            throw new ServiceException(ServiceErrorCode.SERVICE_NOT_FOUND, "Tên dịch vụ là bắt buộc.");
        }

        if (request.getStandardDuration() == null || request.getStandardDuration() < 1) {
            throw new ServiceException(ServiceErrorCode.SERVICE_DURATION_INVALID);
        }

        // Unique check to avoid duplicate service names in this tenant catalog
        if (serviceRepository.existsByServiceNameIgnoreCaseAndIsDeletedFalse(normalizedName)) {
            throw new ServiceException(
                    ServiceErrorCode.SERVICE_NAME_ALREADY_EXISTS,
                    "Tên dịch vụ đã tồn tại trong danh mục này. Vui lòng nhập tên khác."
            );
        }

        String serviceCode = generateNextServiceCode();

        TenantService newService = TenantService.builder()
                .id(UUID.randomUUID().toString())
                .serviceCode(serviceCode)
                .serviceName(normalizedName)
                .standardDuration(request.getStandardDuration())
                .price(request.getPrice())
                .status(ServiceStatus.ACTIVE)
                .isDeleted(false)
                .build();

        TenantService saved = executeInTransaction(status -> serviceRepository.save(newService));

        // Invalidate and refresh Valkey cache for the tenant's active service catalog
        evictAndRefreshCache(tenantCode);

        log.info("Created service [{}] ({}) in tenant [{}]",
                saved.getServiceCode(), saved.getServiceName(), tenantCode);

        return ServiceResponse.fromEntity(saved);
    }

    /**
     * Updates service information, operational status, and configures standard service duration (T_standard).
     * In accordance with business rules, duration adjustment only affects future slot allocation and
     * preserves existing confirmed appointments without altering their time bounds.
     *
     * @param serviceId service unique ID
     * @param request service update payload
     * @return updated {@link ServiceResponse}
     */
    public ServiceResponse updateService(String serviceId, UpdateServiceRequest request) {
        String tenantCode = resolveCurrentTenantCode();

        TenantService service = serviceRepository.findByIdAndIsDeletedFalse(serviceId)
                .orElseThrow(() -> new ServiceException(
                        ServiceErrorCode.SERVICE_NOT_FOUND,
                        "Dịch vụ không tồn tại trong hệ thống hoặc đã bị xóa."
                ));

        String normalizedName = request.getServiceName() != null ? request.getServiceName().trim() : "";
        if (normalizedName.isBlank()) {
            throw new ServiceException(ServiceErrorCode.SERVICE_NOT_FOUND, "Tên dịch vụ là bắt buộc.");
        }

        if (request.getStandardDuration() == null || request.getStandardDuration() < 1) {
            throw new ServiceException(ServiceErrorCode.SERVICE_DURATION_INVALID);
        }

        // Unique check excluding current service ID
        if (serviceRepository.existsByServiceNameIgnoreCaseAndIdNotAndIsDeletedFalse(normalizedName, serviceId)) {
            throw new ServiceException(
                    ServiceErrorCode.SERVICE_NAME_ALREADY_EXISTS,
                    "Tên dịch vụ đã tồn tại trong danh mục này. Vui lòng nhập tên khác."
            );
        }

        // Duration adjustment rule: Changing T_standard takes effect only for future unallocated slots.
        // Existing confirmed appointments and tickets remain strictly intact.
        if (!service.getStandardDuration().equals(request.getStandardDuration())) {
            log.info("Standard duration for service [{}] ({}) changed from {}m to {}m in tenant [{}]. Existing confirmed appointments remain strictly unaffected.",
                    service.getServiceCode(), service.getServiceName(), service.getStandardDuration(), request.getStandardDuration(), tenantCode);
        }

        service.setServiceName(normalizedName);
        service.setStandardDuration(request.getStandardDuration());
        service.setPrice(request.getPrice());
        if (request.getStatus() != null) {
            service.setStatus(request.getStatus());
        }

        TenantService saved = executeInTransaction(status -> serviceRepository.save(service));

        // Invalidate and refresh Valkey cache for the tenant's active service catalog
        evictAndRefreshCache(tenantCode);

        log.info("Updated service [{}] ({}) with status [{}] in tenant [{}]",
                saved.getServiceCode(), saved.getServiceName(), saved.getStatus(), tenantCode);

        return ServiceResponse.fromEntity(saved);
    }

    /**
     * Safely soft-deletes a service from the catalog after relational integrity verification.
     * Prevents deletion if any active appointments (PENDING, CONFIRMED, CHECKED_IN)
     * or active queue tickets (WAITING, CALLING, SERVING) remain incomplete.
     *
     * @param serviceId service unique ID
     */
    public void deleteService(String serviceId) {
        String tenantCode = resolveCurrentTenantCode();
        TenantService service = serviceRepository.findByIdAndIsDeletedFalse(serviceId)
                .orElseThrow(() -> new ServiceException(
                        ServiceErrorCode.SERVICE_NOT_FOUND,
                        "Dịch vụ không tồn tại trong hệ thống hoặc đã bị xóa."
                ));

        // Relational integrity check on appointments and tickets
        long activeAppointments = serviceRepository.countPendingAppointmentsByServiceId(serviceId);
        long activeTickets = serviceRepository.countPendingQueueTicketsByServiceId(serviceId);

        if (activeAppointments > 0 || activeTickets > 0) {
            log.warn("Rejected deletion of service [{}] ({}) in tenant [{}]. Found {} pending/confirmed appointments and {} waiting tickets.",
                    service.getServiceCode(), service.getServiceName(), tenantCode, activeAppointments, activeTickets);
            throw new ServiceException(ServiceErrorCode.SERVICE_CANNOT_DELETE_ACTIVE_BOOKINGS);
        }

        service.setIsDeleted(true);
        service.setStatus(ServiceStatus.INACTIVE);
        executeInTransaction(status -> serviceRepository.save(service));

        // Invalidate and refresh Valkey cache for the tenant's active service catalog
        evictAndRefreshCache(tenantCode);

        log.info("Soft-deleted service [{}] ({}) in tenant [{}]",
                service.getServiceCode(), service.getServiceName(), tenantCode);
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
     * Public and Customer-facing service catalog retrieval for online appointment booking.
     * Defaults to ACTIVE services, supporting keyword filtering and pagination.
     *
     * @param tenantCode facility code
     * @param keyword search keyword
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

    /**
     * Generates an auto-incremented service code in the format SVC-xxxx (e.g. SVC-0001, SVC-0002).
     *
     * @return unique service code
     */
    public String generateNextServiceCode() {
        Optional<String> maxCodeOpt = serviceRepository.findMaxServiceCode();
        int nextSequence = 1;

        if (maxCodeOpt.isPresent() && maxCodeOpt.get() != null) {
            String maxCode = maxCodeOpt.get().trim();
            if (maxCode.startsWith(ServiceConstant.SERVICE_CODE_PREFIX)) {
                String numPart = maxCode.substring(ServiceConstant.SERVICE_CODE_PREFIX.length());
                try {
                    nextSequence = Integer.parseInt(numPart) + 1;
                } catch (NumberFormatException ignored) {
                    nextSequence = (int) serviceRepository.countByIsDeletedFalse() + 1;
                }
            }
        }

        for (int i = 0; i < 20; i++) {
            String candidate = String.format("%s%04d", ServiceConstant.SERVICE_CODE_PREFIX, nextSequence + i);
            if (!serviceRepository.existsByServiceCodeAndIsDeletedFalse(candidate)) {
                return candidate;
            }
        }

        return ServiceConstant.SERVICE_CODE_PREFIX + UUID.randomUUID().toString().substring(0, 4).toUpperCase();
    }
}
