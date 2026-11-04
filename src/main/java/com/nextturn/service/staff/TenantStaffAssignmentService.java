package com.nextturn.service.staff;

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
import com.nextturn.exception.tenant.TenantErrorCode;
import com.nextturn.exception.tenant.TenantException;
import com.nextturn.infrastructure.multitenancy.TenantContextHolder;
import com.nextturn.repository.service.TenantServiceRepository;
import com.nextturn.repository.staff.TenantStaffRepository;
import com.nextturn.repository.staff.TenantStaffServiceRepository;
import com.nextturn.service.BaseService;
import com.nextturn.service.tenant.TenantCacheService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Service managing staff-service qualification assignments and associated Valkey cache invalidation.
 */
@Service
public class TenantStaffAssignmentService extends BaseService {

    private static final Logger log = LoggerFactory.getLogger(TenantStaffAssignmentService.class);

    private final TenantStaffRepository staffRepository;
    private final TenantStaffServiceRepository staffServiceRepository;
    private final TenantServiceRepository serviceRepository;
    private final TenantCacheService tenantCacheService;

    @Autowired
    public TenantStaffAssignmentService(
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

    /**
     * Assigns a list of services to an encounter staff member:
     * 1. Validates that staff exists and is currently ACTIVE.
     * 2. Validates that all requested services exist in the tenant catalog and are ACTIVE.
     * 3. Updates the many-to-many junction table tenant__staff_services.
     * 4. Invalidates and clears cache on Valkey for affected service staff lists.
     *
     * @param staffId staff member UUID
     * @param request assignment request containing target service UUIDs
     * @return updated {@link StaffResponse} with list of assigned services
     */
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

    /**
     * Retrieves the list of services currently assigned to a staff member.
     *
     * @param staffId staff member UUID
     * @return list of {@link ServiceResponse}
     */
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

    /**
     * Evicts Valkey cache entries for all services currently assigned to a staff member.
     *
     * @param tenantCode active facility code
     * @param staffId staff member UUID
     */
    public void evictAssignedServicesCache(String tenantCode, String staffId) {
        List<String> assignedServiceIds = staffServiceRepository.findServiceIdsByStaffId(staffId);
        if (assignedServiceIds != null) {
            for (String svcId : assignedServiceIds) {
                tenantCacheService.evictServiceStaffCache(tenantCode, svcId);
            }
        }
    }
}

