package com.nextturn.service.staff;

import com.nextturn.dto.service.ServiceResponse;
import com.nextturn.dto.staff.StaffResponse;
import com.nextturn.entity.TenantStaff;
import com.nextturn.enums.StaffRole;
import com.nextturn.enums.StaffStatus;
import com.nextturn.exception.staff.StaffErrorCode;
import com.nextturn.exception.staff.StaffException;
import com.nextturn.exception.tenant.TenantErrorCode;
import com.nextturn.exception.tenant.TenantException;
import com.nextturn.infrastructure.multitenancy.TenantContextHolder;
import com.nextturn.repository.staff.TenantStaffRepository;
import com.nextturn.repository.staff.TenantStaffServiceRepository;
import com.nextturn.service.BaseService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Read-only query service for tenant staff members, roster pagination, and profile lookups.
 */
@Service
@Transactional(readOnly = true)
public class TenantStaffQueryService extends BaseService {

    private final TenantStaffRepository staffRepository;
    private final TenantStaffServiceRepository staffServiceRepository;

    @Autowired
    public TenantStaffQueryService(
            TenantStaffRepository staffRepository,
            TenantStaffServiceRepository staffServiceRepository
    ) {
        super();
        this.staffRepository = staffRepository;
        this.staffServiceRepository = staffServiceRepository;
    }

    /**
     * Searches and lists active staff members in the caller's tenant with pagination and optional multi-criteria filters.
     *
     * @param keyword optional search keyword matching full name, email, phone, or badge code
     * @param role optional role filter (ADMIN, COUNTER, SPECIALIST)
     * @param status optional status filter (ACTIVE, INACTIVE)
     * @param pageable pagination parameters
     * @return page of {@link StaffResponse}
     */
    public Page<StaffResponse> searchStaff(String keyword, StaffRole role, StaffStatus status, Pageable pageable) {
        resolveCurrentTenantCode();
        String cleanKeyword = (keyword != null && !keyword.isBlank()) ? keyword.trim() : null;
        return staffRepository.searchStaff(cleanKeyword, role, status, pageable)
                .map(this::mapStaffToResponse);
    }

    /**
     * Lists active staff members in the caller's tenant with pagination.
     *
     * @param pageable pagination parameters
     * @return page of {@link StaffResponse}
     */
    public Page<StaffResponse> getStaffList(Pageable pageable) {
        return searchStaff(null, null, null, pageable);
    }

    /**
     * Retrieves a single staff member by ID within the caller's tenant.
     *
     * @param staffId staff member UUID
     * @return {@link StaffResponse} with assigned services
     */
    public StaffResponse getStaffById(String staffId) {
        resolveCurrentTenantCode();
        TenantStaff staff = staffRepository.findByIdAndIsDeletedFalse(staffId)
                .orElseThrow(() -> new StaffException(
                        StaffErrorCode.STAFF_NOT_FOUND,
                        "Nhân viên không tồn tại trong hệ thống hoặc đã bị xóa."
                ));

        return mapStaffToResponse(staff);
    }

    private StaffResponse mapStaffToResponse(TenantStaff staff) {
        List<ServiceResponse> services = staffServiceRepository.findByStaffIdWithService(staff.getId())
                .stream()
                .map(assignment -> ServiceResponse.fromEntity(assignment.getService()))
                .collect(Collectors.toList());
        return StaffResponse.fromEntity(staff, services);
    }
}

