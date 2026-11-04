package com.nextturn.service.staff.impl;

import com.nextturn.dto.service.ServiceResponse;
import com.nextturn.dto.staff.StaffResponse;
import com.nextturn.entity.TenantStaff;
import com.nextturn.enums.StaffRole;
import com.nextturn.enums.StaffStatus;
import com.nextturn.exception.staff.StaffErrorCode;
import com.nextturn.exception.staff.StaffException;
import com.nextturn.repository.staff.TenantStaffRepository;
import com.nextturn.repository.staff.TenantStaffServiceRepository;
import com.nextturn.service.BaseService;
import com.nextturn.service.staff.TenantStaffQueryService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Default implementation of {@link TenantStaffQueryService} for read-only staff queries,
 * roster pagination, and profile lookups.
 */
@Service
@Transactional(readOnly = true)
public class TenantStaffQueryServiceImpl extends BaseService implements TenantStaffQueryService {

    private final TenantStaffRepository staffRepository;
    private final TenantStaffServiceRepository staffServiceRepository;

    @Autowired
    public TenantStaffQueryServiceImpl(
            TenantStaffRepository staffRepository,
            TenantStaffServiceRepository staffServiceRepository
    ) {
        super();
        this.staffRepository = staffRepository;
        this.staffServiceRepository = staffServiceRepository;
    }

    @Override
    public Page<StaffResponse> searchStaff(String keyword, StaffRole role, StaffStatus status, Pageable pageable) {
        resolveCurrentTenantCode();
        String cleanKeyword = (keyword != null && !keyword.isBlank()) ? keyword.trim() : null;
        return staffRepository.searchStaff(cleanKeyword, role, status, pageable)
                .map(this::mapStaffToResponse);
    }

    @Override
    public Page<StaffResponse> getStaffList(Pageable pageable) {
        return searchStaff(null, null, null, pageable);
    }

    @Override
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

