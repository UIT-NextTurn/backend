package com.nextturn.service.service;

import com.nextturn.dto.service.CreateServiceRequest;
import com.nextturn.dto.service.ServiceResponse;
import com.nextturn.dto.service.UpdateServiceRequest;
import com.nextturn.entity.TenantService;
import com.nextturn.enums.ServiceStatus;
import com.nextturn.exception.service.ServiceErrorCode;
import com.nextturn.exception.service.ServiceException;
import com.nextturn.repository.service.TenantServiceRepository;
import com.nextturn.service.BaseService;
import com.nextturn.service.tenant.TenantCacheService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Service managing tenant service mutations, duration configuration,
 * relational integrity protection, and coordinating service cache updates.
 */
@Service
public class TenantServiceService extends BaseService {

    private static final Logger log = LoggerFactory.getLogger(TenantServiceService.class);

    private final TenantServiceRepository serviceRepository;
    private final TenantCacheService tenantCacheService;
    private final TenantServiceCodeGenerator codeGenerator;
    private final TenantServiceQueryService queryService;

    @Autowired
    public TenantServiceService(
            TenantServiceRepository serviceRepository,
            TenantCacheService tenantCacheService,
            @Autowired(required = false) TenantServiceCodeGenerator codeGenerator,
            @Autowired(required = false) TenantServiceQueryService queryService,
            @Autowired(required = false) TransactionTemplate transactionTemplate
    ) {
        super(transactionTemplate);
        this.serviceRepository = serviceRepository;
        this.tenantCacheService = tenantCacheService;
        this.codeGenerator = codeGenerator != null ? codeGenerator : new TenantServiceCodeGenerator(serviceRepository);
        this.queryService = queryService != null ? queryService : new TenantServiceQueryService(serviceRepository, tenantCacheService);
    }

    public TenantServiceService(
            TenantServiceRepository serviceRepository,
            TenantCacheService tenantCacheService,
            @Autowired(required = false) TransactionTemplate transactionTemplate
    ) {
        this(
                serviceRepository,
                tenantCacheService,
                new TenantServiceCodeGenerator(serviceRepository),
                new TenantServiceQueryService(serviceRepository, tenantCacheService),
                transactionTemplate
        );
    }

    public TenantServiceService(
            TenantServiceRepository serviceRepository,
            TenantCacheService tenantCacheService
    ) {
        this(serviceRepository, tenantCacheService, null);
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
     * Creates a new service in the active tenant.
     * Enforces unique service name within the facility schema and auto-assigns sequential SVC-xxxx code.
     *
     * @param request service creation payload
     * @return created {@link ServiceResponse}
     */
    public ServiceResponse createService(CreateServiceRequest request) {
        String tenantCode = resolveCurrentTenantCode();

        String trimmedName = request.getServiceName().trim();
        if (serviceRepository.existsByServiceNameIgnoreCaseAndIsDeletedFalse(trimmedName)) {
            throw new ServiceException(
                    ServiceErrorCode.SERVICE_NAME_ALREADY_EXISTS,
                    "Tên dịch vụ [" + trimmedName + "] đã tồn tại trong danh mục này. Vui lòng nhập tên khác."
            );
        }

        String serviceCode = codeGenerator.generateNextServiceCode();

        TenantService newService = TenantService.builder()
                .id(UUID.randomUUID().toString())
                .serviceCode(serviceCode)
                .serviceName(trimmedName)
                .standardDuration(request.getStandardDuration())
                .price(request.getPrice())
                .status(ServiceStatus.ACTIVE)
                .isDeleted(false)
                .build();

        TenantService saved = executeInTransaction(status -> serviceRepository.save(newService));

        evictAndRefreshCache(tenantCode);

        log.info("Created service [{}] ({}) in tenant [{}]", saved.getServiceCode(), saved.getServiceName(), tenantCode);
        return ServiceResponse.fromEntity(saved);
    }

    /**
     * Updates service information, operational status, and configures standard service duration.
     * Duration adjustment takes effect only for future slots and preserves existing confirmed appointments.
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

        String trimmedName = request.getServiceName().trim();
        if (serviceRepository.existsByServiceNameIgnoreCaseAndIdNotAndIsDeletedFalse(trimmedName, serviceId)) {
            throw new ServiceException(
                    ServiceErrorCode.SERVICE_NAME_ALREADY_EXISTS,
                    "Tên dịch vụ [" + trimmedName + "] đã được sử dụng bởi một dịch vụ khác."
            );
        }

        if (!service.getStandardDuration().equals(request.getStandardDuration())) {
            log.info("Standard duration for service [{}] ({}) changed from {}m to {}m in tenant [{}]. Existing confirmed appointments remain strictly unaffected.",
                    service.getServiceCode(), service.getServiceName(), service.getStandardDuration(), request.getStandardDuration(), tenantCode);
        }

        service.setServiceName(trimmedName);
        service.setStandardDuration(request.getStandardDuration());
        service.setPrice(request.getPrice());

        if (request.getStatus() != null) {
            service.setStatus(request.getStatus());
        }

        TenantService saved = executeInTransaction(status -> serviceRepository.save(service));

        evictAndRefreshCache(tenantCode);

        log.info("Updated service [{}] ({}) with status [{}] in tenant [{}]",
                saved.getServiceCode(), saved.getServiceName(), saved.getStatus(), tenantCode);
        return ServiceResponse.fromEntity(saved);
    }

    /**
     * Safely soft-deletes a service after relational integrity verification.
     * Rejects deletion if active appointments (CONFIRMED/IN_PROGRESS) or queue tickets exist.
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

        long pendingAppointments = serviceRepository.countPendingAppointmentsByServiceId(serviceId);
        long pendingTickets = serviceRepository.countPendingQueueTicketsByServiceId(serviceId);
        if (pendingAppointments > 0 || pendingTickets > 0) {
            log.warn("Cannot delete service [{}] ({}) in tenant [{}]: {} pending appointments, {} pending tickets",
                    service.getServiceCode(), service.getServiceName(), tenantCode, pendingAppointments, pendingTickets);
            throw new ServiceException(
                    ServiceErrorCode.SERVICE_CANNOT_DELETE_ACTIVE_BOOKINGS,
                    "Dịch vụ này hiện đang có lịch hẹn hoặc vé chờ chưa hoàn tất. Hệ thống không thể xóa dịch vụ. Quản trị viên vui lòng chuyển sang trạng thái “Tạm ngưng hoạt động” (Inactive) để dừng tiếp nhận lượt mới."
            );
        }

        service.setIsDeleted(true);
        executeInTransaction(status -> serviceRepository.save(service));

        evictAndRefreshCache(tenantCode);

        log.info("Soft-deleted service [{}] ({}) in tenant [{}]", service.getServiceCode(), service.getServiceName(), tenantCode);
    }

    public Page<ServiceResponse> searchServices(String keyword, ServiceStatus status, Pageable pageable) {
        return queryService.searchServices(keyword, status, pageable);
    }

    public Page<ServiceResponse> searchPublicServices(String tenantCode, String keyword, ServiceStatus status, Pageable pageable) {
        return queryService.searchPublicServices(tenantCode, keyword, status, pageable);
    }

    public ServiceResponse getServiceById(String serviceId) {
        return queryService.getServiceById(serviceId);
    }

    public Page<ServiceResponse> getServiceList(Pageable pageable) {
        return queryService.getServiceList(pageable);
    }

    public List<ServiceResponse> getActiveServices() {
        return queryService.getActiveServices();
    }

    public String generateNextServiceCode() {
        return codeGenerator.generateNextServiceCode();
    }
}
