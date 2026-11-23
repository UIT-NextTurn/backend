package com.nextturn.service.service.impl;

import com.nextturn.dto.service.CreateServiceRequest;
import com.nextturn.dto.service.ServiceResponse;
import com.nextturn.dto.service.UpdateServiceRequest;
import com.nextturn.entity.TenantService;
import com.nextturn.enums.ServiceStatus;
import com.nextturn.exception.service.ServiceErrorCode;
import com.nextturn.exception.service.ServiceException;
import com.nextturn.repository.service.TenantServiceRepository;
import com.nextturn.service.BaseService;
import com.nextturn.service.service.TenantServiceCodeGenerator;
import com.nextturn.service.service.TenantServiceQueryService;
import com.nextturn.service.service.TenantServiceService;
import com.nextturn.service.tenant.TenantCacheService;
import com.nextturn.infrastructure.logging.LogContract;
import com.nextturn.infrastructure.logging.LogDomain;
import com.nextturn.infrastructure.logging.LogOutcome;
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
 * Default implementation of {@link TenantServiceService} managing tenant service mutations,
 * duration configuration, relational integrity protection, and coordinating service cache updates.
 */
@Service
public class TenantServiceServiceImpl extends BaseService implements TenantServiceService {

    private static final Logger log = LoggerFactory.getLogger(TenantServiceServiceImpl.class);

    private final TenantServiceRepository serviceRepository;
    private final TenantCacheService tenantCacheService;
    private final TenantServiceCodeGenerator codeGenerator;
    private final TenantServiceQueryService queryService;

    @Autowired
    public TenantServiceServiceImpl(
            TenantServiceRepository serviceRepository,
            TenantCacheService tenantCacheService,
            @Autowired(required = false) TenantServiceCodeGenerator codeGenerator,
            @Autowired(required = false) TenantServiceQueryService queryService,
            @Autowired(required = false) TransactionTemplate transactionTemplate
    ) {
        super(transactionTemplate);
        this.serviceRepository = serviceRepository;
        this.tenantCacheService = tenantCacheService;
        this.codeGenerator = codeGenerator != null ? codeGenerator : new TenantServiceCodeGeneratorImpl(serviceRepository);
        this.queryService = queryService != null ? queryService : new TenantServiceQueryServiceImpl(serviceRepository, tenantCacheService);
    }

    public TenantServiceServiceImpl(
            TenantServiceRepository serviceRepository,
            TenantCacheService tenantCacheService,
            @Autowired(required = false) TransactionTemplate transactionTemplate
    ) {
        this(
                serviceRepository,
                tenantCacheService,
                new TenantServiceCodeGeneratorImpl(serviceRepository),
                new TenantServiceQueryServiceImpl(serviceRepository, tenantCacheService),
                transactionTemplate
        );
    }

    public TenantServiceServiceImpl(
            TenantServiceRepository serviceRepository,
            TenantCacheService tenantCacheService
    ) {
        this(serviceRepository, tenantCacheService, (TransactionTemplate) null);
    }

    private void evictAndRefreshCache(String tenantCode) {
        tenantCacheService.evictTenantServiceCache(tenantCode);
        List<ServiceResponse> activeServices = serviceRepository.findAllByStatusAndIsDeletedFalse(ServiceStatus.ACTIVE)
                .stream()
                .map(ServiceResponse::fromEntity)
                .collect(Collectors.toList());
        tenantCacheService.cacheTenantServices(tenantCode, activeServices);
    }

    @Override
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

        LogContract.info(log, LogDomain.SERVICE, "service_created")
                .outcome(LogOutcome.SUCCESS)
                .tenant(tenantCode)
                .entity("service", saved.getServiceCode())
                .field("name", saved.getServiceName())
                .emit();
        return ServiceResponse.fromEntity(saved);
    }

    @Override
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
            LogContract.info(log, LogDomain.SERVICE, "service_duration_changed")
                    .outcome(LogOutcome.SUCCESS)
                    .tenant(tenantCode)
                    .entity("service", service.getServiceCode())
                    .field("oldMinutes", service.getStandardDuration())
                    .field("newMinutes", request.getStandardDuration())
                    .emit();
        }

        service.setServiceName(trimmedName);
        service.setStandardDuration(request.getStandardDuration());
        service.setPrice(request.getPrice());

        if (request.getStatus() != null) {
            service.setStatus(request.getStatus());
        }

        TenantService saved = executeInTransaction(status -> serviceRepository.save(service));

        evictAndRefreshCache(tenantCode);

        LogContract.info(log, LogDomain.SERVICE, "service_updated")
                .outcome(LogOutcome.SUCCESS)
                .tenant(tenantCode)
                .entity("service", saved.getServiceCode())
                .field("status", saved.getStatus())
                .emit();
        return ServiceResponse.fromEntity(saved);
    }

    @Override
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
            LogContract.warn(log, LogDomain.SERVICE, "service_delete_blocked")
                    .outcome(LogOutcome.REJECTED)
                    .tenant(tenantCode)
                    .entity("service", service.getServiceCode())
                    .field("pendingAppointments", pendingAppointments)
                    .field("pendingTickets", pendingTickets)
                    .emit();
            throw new ServiceException(
                    ServiceErrorCode.SERVICE_CANNOT_DELETE_ACTIVE_BOOKINGS,
                    "Dịch vụ này hiện đang có lịch hẹn hoặc vé chờ chưa hoàn tất. Hệ thống không thể xóa dịch vụ. Quản trị viên vui lòng chuyển sang trạng thái “Tạm ngưng hoạt động” (Inactive) để dừng tiếp nhận lượt mới."
            );
        }

        service.setIsDeleted(true);
        executeInTransaction(status -> serviceRepository.save(service));

        evictAndRefreshCache(tenantCode);

        LogContract.info(log, LogDomain.SERVICE, "service_deleted")
                .outcome(LogOutcome.SUCCESS)
                .tenant(tenantCode)
                .entity("service", service.getServiceCode())
                .emit();
    }

    @Override
    public Page<ServiceResponse> searchServices(String keyword, ServiceStatus status, Pageable pageable) {
        return queryService.searchServices(keyword, status, pageable);
    }

    @Override
    public Page<ServiceResponse> searchPublicServices(String tenantCode, String keyword, ServiceStatus status, Pageable pageable) {
        return queryService.searchPublicServices(tenantCode, keyword, status, pageable);
    }

    @Override
    public ServiceResponse getServiceById(String serviceId) {
        return queryService.getServiceById(serviceId);
    }

    @Override
    public Page<ServiceResponse> getServiceList(Pageable pageable) {
        return queryService.getServiceList(pageable);
    }

    @Override
    public List<ServiceResponse> getActiveServices() {
        return queryService.getActiveServices();
    }

    @Override
    public String generateNextServiceCode() {
        return codeGenerator.generateNextServiceCode();
    }
}

