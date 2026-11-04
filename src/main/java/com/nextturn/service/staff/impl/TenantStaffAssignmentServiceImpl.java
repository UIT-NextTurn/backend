package com.nextturn.service.staff.impl;

import com.nextturn.dto.service.ServiceResponse;
import com.nextturn.dto.staff.AssignStaffServicesRequest;
import com.nextturn.dto.staff.StaffResponse;
import com.nextturn.entity.TenantService;
import com.nextturn.entity.TenantStaff;
import com.nextturn.entity.TenantStaffServiceAssignment;
import com.nextturn.enums.ServiceStatus;
import com.nextturn.enums.StaffStatus;
import com.nextturn.exception.service.ServiceErrorCode;
import com.nextturn.exception.service.ServiceException;
import com.nextturn.exception.staff.StaffErrorCode;
import com.nextturn.exception.staff.StaffException;
import com.nextturn.repository.service.TenantServiceRepository;
import com.nextturn.repository.staff.TenantStaffRepository;
import com.nextturn.repository.staff.TenantStaffServiceRepository;
import com.nextturn.service.BaseService;
import com.nextturn.service.staff.TenantStaffAssignmentService;
import com.nextturn.service.tenant.TenantCacheService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Default implementation of {@link TenantStaffAssignmentService} managing staff-service
 * qualification assignments and associated Valkey cache invalidation.
 */
@Service
public class TenantStaffAssignmentServiceImpl extends BaseService implements TenantStaffAssignmentService {

    private static final Logger log = LoggerFactory.getLogger(TenantStaffAssignmentServiceImpl.class);

    private final TenantStaffRepository staffRepository;
    private final TenantStaffServiceRepository staffServiceRepository;
    private final TenantServiceRepository serviceRepository;
    private final TenantCacheService tenantCacheService;

    @Autowired
    public TenantStaffAssignmentServiceImpl(
            TenantStaffRepository staffRepository,
            TenantStaffServiceRepository staffServiceRepository,
            TenantServiceRepository serviceRepository,
            TenantCacheService tenantCacheService,
            @Autowired(required = false) TransactionTemplate transactionTemplate
    ) {
        super(transactionTemplate);
        this.staffRepository = staffRepository;
        this.staffServiceRepository = staffServiceRepository;
        this.serviceRepository = serviceRepository;
        this.tenantCacheService = tenantCacheService;
    }

    public TenantStaffAssignmentServiceImpl(
            TenantStaffRepository staffRepository,
            TenantStaffServiceRepository staffServiceRepository,
            TenantServiceRepository serviceRepository,
            TenantCacheService tenantCacheService
    ) {
        this(staffRepository, staffServiceRepository, serviceRepository, tenantCacheService, null);
    }

    @Override
    public StaffResponse assignServices(String staffId, AssignStaffServicesRequest request) {
        String tenantCode = resolveCurrentTenantCode();

        TenantStaff staff = staffRepository.findByIdAndIsDeletedFalse(staffId)
                .orElseThrow(() -> new StaffException(
                        StaffErrorCode.STAFF_NOT_FOUND,
                        "Nhân viên không tồn tại trong hệ thống hoặc đã bị xóa."
                ));

        if (staff.getStatus() != StaffStatus.ACTIVE) {
            throw new StaffException(
                    StaffErrorCode.STAFF_NOT_ACTIVE,
                    "Nhân viên không ở trạng thái hoạt động."
            );
        }

        if (request.getServiceIds() == null || request.getServiceIds().isEmpty()) {
            throw new StaffException(
                    StaffErrorCode.STAFF_SERVICE_EMPTY,
                    "Danh sách dịch vụ phân công không được để trống."
            );
        }

        List<TenantService> targetServices = new ArrayList<>();
        for (String svcId : request.getServiceIds()) {
            TenantService service = serviceRepository.findByIdAndIsDeletedFalse(svcId)
                    .orElseThrow(() -> new ServiceException(
                            ServiceErrorCode.SERVICE_NOT_FOUND,
                            "Dịch vụ với ID [" + svcId + "] không tồn tại trong hệ thống hoặc đã bị xóa."
                    ));
            if (service.getStatus() != ServiceStatus.ACTIVE) {
                throw new ServiceException(
                        ServiceErrorCode.SERVICE_NOT_FOUND,
                        "Dịch vụ [" + service.getServiceName() + "] hiện đang tạm ngưng hoạt động."
                );
            }
            targetServices.add(service);
        }

        List<String> oldServiceIds = staffServiceRepository.findServiceIdsByStaffId(staffId);

        executeInTransaction(status -> {
            staffServiceRepository.deleteByStaffId(staffId);
            for (TenantService svc : targetServices) {
                TenantStaffServiceAssignment assignment = TenantStaffServiceAssignment.builder()
                        .id(UUID.randomUUID().toString())
                        .staff(staff)
                        .service(svc)
                        .build();
                staffServiceRepository.save(assignment);
            }
            return null;
        });

        for (String svcId : oldServiceIds) {
            tenantCacheService.evictServiceStaffCache(tenantCode, svcId);
        }
        for (TenantService svc : targetServices) {
            tenantCacheService.evictServiceStaffCache(tenantCode, svc.getId());
        }

        log.info("Assigned {} services to staff [{}] ({}) in tenant [{}]",
                targetServices.size(), staff.getStaffCode(), staff.getFullName(), tenantCode);

        List<ServiceResponse> assignedResponses = targetServices.stream()
                .map(ServiceResponse::fromEntity)
                .collect(Collectors.toList());

        return StaffResponse.fromEntity(staff, assignedResponses);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ServiceResponse> getAssignedServices(String staffId) {
        resolveCurrentTenantCode();
        staffRepository.findByIdAndIsDeletedFalse(staffId)
                .orElseThrow(() -> new StaffException(
                        StaffErrorCode.STAFF_NOT_FOUND,
                        "Nhân viên không tồn tại trong hệ thống hoặc đã bị xóa."
                ));

        return staffServiceRepository.findByStaffIdWithService(staffId)
                .stream()
                .map(assignment -> ServiceResponse.fromEntity(assignment.getService()))
                .collect(Collectors.toList());
    }

    @Override
    public void evictAssignedServicesCache(String tenantCode, String staffId) {
        List<String> assignedServiceIds = staffServiceRepository.findServiceIdsByStaffId(staffId);
        if (assignedServiceIds != null) {
            for (String svcId : assignedServiceIds) {
                tenantCacheService.evictServiceStaffCache(tenantCode, svcId);
            }
        }
    }
}

