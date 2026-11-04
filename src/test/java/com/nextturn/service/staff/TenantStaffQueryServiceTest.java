package com.nextturn.service.staff;

import com.nextturn.dto.staff.StaffResponse;
import com.nextturn.entity.TenantService;
import com.nextturn.entity.TenantStaff;
import com.nextturn.entity.TenantStaffServiceAssignment;
import com.nextturn.enums.StaffRole;
import com.nextturn.enums.StaffStatus;
import com.nextturn.exception.staff.StaffErrorCode;
import com.nextturn.exception.staff.StaffException;
import com.nextturn.infrastructure.multitenancy.TenantContextHolder;
import com.nextturn.repository.staff.TenantStaffRepository;
import com.nextturn.repository.staff.TenantStaffServiceRepository;
import com.nextturn.service.staff.impl.TenantStaffQueryServiceImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TenantStaffQueryServiceTest {

    private static final String TENANT_CODE = "tenant-alpha";
    private static final String STAFF_ID = "staff-uuid-1";

    @Mock
    private TenantStaffRepository staffRepository;

    @Mock
    private TenantStaffServiceRepository staffServiceRepository;

    @InjectMocks
    private TenantStaffQueryServiceImpl queryService;

    private TenantStaff sampleStaff;

    @BeforeEach
    void setUp() {
        TenantContextHolder.setTenantCode(TENANT_CODE);
        sampleStaff = TenantStaff.builder()
                .id(STAFF_ID)
                .staffCode("STAFF-0001")
                .fullName("Nguyễn Văn A")
                .email("staff.a@tenant.com")
                .phoneNumber("0912345678")
                .role(StaffRole.COUNTER)
                .status(StaffStatus.ACTIVE)
                .isDeleted(false)
                .build();
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
    }

    @Test
    @DisplayName("getStaffById should return staff details with assigned services")
    void getStaffById_shouldReturnDetails() {
        TenantService svc = TenantService.builder()
                .id("svc-1")
                .serviceCode("SVC-0001")
                .serviceName("Tư Vấn")
                .build();

        TenantStaffServiceAssignment assignment = TenantStaffServiceAssignment.builder()
                .id("assign-1")
                .staff(sampleStaff)
                .service(svc)
                .build();

        when(staffRepository.findByIdAndIsDeletedFalse(STAFF_ID)).thenReturn(Optional.of(sampleStaff));
        when(staffServiceRepository.findByStaffIdWithService(STAFF_ID)).thenReturn(List.of(assignment));

        StaffResponse response = queryService.getStaffById(STAFF_ID);

        assertThat(response).isNotNull();
        assertThat(response.getStaffCode()).isEqualTo("STAFF-0001");
        assertThat(response.getAssignedServices()).hasSize(1);
        assertThat(response.getAssignedServices().get(0).getServiceCode()).isEqualTo("SVC-0001");
    }

    @Test
    @DisplayName("getStaffById should throw 404 when staff not found")
    void getStaffById_shouldThrowNotFound_whenMissing() {
        when(staffRepository.findByIdAndIsDeletedFalse("missing-id")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> queryService.getStaffById("missing-id"))
                .isInstanceOf(StaffException.class)
                .satisfies(ex -> {
                    StaffException sex = (StaffException) ex;
                    assertThat(sex.getCode()).isEqualTo(StaffErrorCode.STAFF_NOT_FOUND.code());
                });
    }

    @Test
    @DisplayName("searchStaff should query repository with parameters and map responses")
    void searchStaff_shouldFilterAndMap() {
        PageRequest pageRequest = PageRequest.of(0, 10);
        when(staffRepository.searchStaff("Nguyễn", StaffRole.COUNTER, StaffStatus.ACTIVE, pageRequest))
                .thenReturn(new PageImpl<>(List.of(sampleStaff)));
        when(staffServiceRepository.findByStaffIdWithService(STAFF_ID)).thenReturn(Collections.emptyList());

        Page<StaffResponse> page = queryService.searchStaff("Nguyễn", StaffRole.COUNTER, StaffStatus.ACTIVE, pageRequest);

        assertThat(page.getContent()).hasSize(1);
        assertThat(page.getContent().get(0).getFullName()).isEqualTo("Nguyễn Văn A");
    }

    @Test
    @DisplayName("getStaffList should delegate to searchStaff with null filters")
    void getStaffList_shouldReturnPage() {
        PageRequest pageRequest = PageRequest.of(0, 10);
        when(staffRepository.searchStaff(null, null, null, pageRequest))
                .thenReturn(new PageImpl<>(List.of(sampleStaff)));
        when(staffServiceRepository.findByStaffIdWithService(STAFF_ID)).thenReturn(Collections.emptyList());

        Page<StaffResponse> page = queryService.getStaffList(pageRequest);

        assertThat(page.getContent()).hasSize(1);
        assertThat(page.getContent().get(0).getStaffCode()).isEqualTo("STAFF-0001");
    }
}

