package com.nextturn.service.staff;

import com.nextturn.dto.service.ServiceResponse;
import com.nextturn.dto.staff.AssignStaffServicesRequest;
import com.nextturn.dto.staff.StaffResponse;
import com.nextturn.entity.TenantService;
import com.nextturn.entity.TenantStaff;
import com.nextturn.entity.TenantStaffServiceAssignment;
import com.nextturn.enums.ServiceStatus;
import com.nextturn.enums.StaffRole;
import com.nextturn.enums.StaffStatus;
import com.nextturn.exception.service.ServiceErrorCode;
import com.nextturn.exception.service.ServiceException;
import com.nextturn.exception.staff.StaffErrorCode;
import com.nextturn.exception.staff.StaffException;
import com.nextturn.infrastructure.multitenancy.TenantContextHolder;
import com.nextturn.repository.service.TenantServiceRepository;
import com.nextturn.repository.staff.TenantStaffRepository;
import com.nextturn.repository.staff.TenantStaffServiceRepository;
import com.nextturn.service.tenant.TenantCacheService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TenantStaffAssignmentServiceTest {

    private static final String TENANT_CODE = "tenant-alpha";
    private static final String STAFF_ID = "staff-uuid-1";

    @Mock
    private TenantStaffRepository staffRepository;

    @Mock
    private TenantStaffServiceRepository staffServiceRepository;

    @Mock
    private TenantServiceRepository serviceRepository;

    @Mock
    private TenantCacheService tenantCacheService;

    @InjectMocks
    private TenantStaffAssignmentService assignmentService;

    private TenantStaff activeStaff;

    @BeforeEach
    void setUp() {
        TenantContextHolder.setTenantCode(TENANT_CODE);
        activeStaff = TenantStaff.builder()
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
    @DisplayName("assignServices should save assignments and evict Valkey cache")
    void assignServices_shouldSucceed() {
        TenantService svc1 = TenantService.builder()
                .id("svc-1")
                .serviceCode("SVC-0001")
                .serviceName("Tư Vấn")
                .standardDuration(15)
                .price(new BigDecimal("100000.00"))
                .status(ServiceStatus.ACTIVE)
                .isDeleted(false)
                .build();

        TenantService svc2 = TenantService.builder()
                .id("svc-2")
                .serviceCode("SVC-0002")
                .serviceName("Hỗ Trợ")
                .standardDuration(30)
                .price(new BigDecimal("200000.00"))
                .status(ServiceStatus.ACTIVE)
                .isDeleted(false)
                .build();

        AssignStaffServicesRequest request = AssignStaffServicesRequest.builder()
                .serviceIds(List.of("svc-1", "svc-2"))
                .build();

        when(staffRepository.findByIdAndIsDeletedFalse(STAFF_ID)).thenReturn(Optional.of(activeStaff));
        when(serviceRepository.findByIdAndIsDeletedFalse("svc-1")).thenReturn(Optional.of(svc1));
        when(serviceRepository.findByIdAndIsDeletedFalse("svc-2")).thenReturn(Optional.of(svc2));
        when(staffServiceRepository.findServiceIdsByStaffId(STAFF_ID)).thenReturn(List.of("svc-old"));

        StaffResponse response = assignmentService.assignServices(STAFF_ID, request);

        assertThat(response).isNotNull();
        assertThat(response.getAssignedServices()).hasSize(2);
        verify(staffServiceRepository).deleteByStaffId(STAFF_ID);
        verify(tenantCacheService).evictServiceStaffCache(TENANT_CODE, "svc-old");
        verify(tenantCacheService).evictServiceStaffCache(TENANT_CODE, "svc-1");
        verify(tenantCacheService).evictServiceStaffCache(TENANT_CODE, "svc-2");
    }

    @Test
    @DisplayName("assignServices should throw 400 Bad Request when staff is not ACTIVE")
    void assignServices_shouldThrow_whenStaffInactive() {
        activeStaff.setStatus(StaffStatus.INACTIVE);
        when(staffRepository.findByIdAndIsDeletedFalse(STAFF_ID)).thenReturn(Optional.of(activeStaff));

        AssignStaffServicesRequest request = AssignStaffServicesRequest.builder()
                .serviceIds(List.of("svc-1"))
                .build();

        assertThatThrownBy(() -> assignmentService.assignServices(STAFF_ID, request))
                .isInstanceOf(StaffException.class)
                .satisfies(ex -> {
                    StaffException sex = (StaffException) ex;
                    assertThat(sex.getCode()).isEqualTo(StaffErrorCode.STAFF_NOT_ACTIVE.code());
                });

        verify(staffServiceRepository, never()).deleteByStaffId(any());
    }

    @Test
    @DisplayName("assignServices should throw 404 when requested service does not exist")
    void assignServices_shouldThrow_whenServiceMissing() {
        when(staffRepository.findByIdAndIsDeletedFalse(STAFF_ID)).thenReturn(Optional.of(activeStaff));
        when(serviceRepository.findByIdAndIsDeletedFalse("svc-missing")).thenReturn(Optional.empty());

        AssignStaffServicesRequest request = AssignStaffServicesRequest.builder()
                .serviceIds(List.of("svc-missing"))
                .build();

        assertThatThrownBy(() -> assignmentService.assignServices(STAFF_ID, request))
                .isInstanceOf(ServiceException.class)
                .satisfies(ex -> {
                    ServiceException sex = (ServiceException) ex;
                    assertThat(sex.getCode()).isEqualTo(ServiceErrorCode.SERVICE_NOT_FOUND.code());
                });
    }

    @Test
    @DisplayName("getAssignedServices should return mapped service list")
    void getAssignedServices_shouldReturnList() {
        TenantService svc = TenantService.builder()
                .id("svc-1")
                .serviceCode("SVC-0001")
                .serviceName("Tư Vấn")
                .standardDuration(15)
                .price(new BigDecimal("100000.00"))
                .status(ServiceStatus.ACTIVE)
                .build();

        TenantStaffServiceAssignment assignment = TenantStaffServiceAssignment.builder()
                .id("assign-1")
                .staff(activeStaff)
                .service(svc)
                .build();

        when(staffRepository.findByIdAndIsDeletedFalse(STAFF_ID)).thenReturn(Optional.of(activeStaff));
        when(staffServiceRepository.findByStaffIdWithService(STAFF_ID)).thenReturn(List.of(assignment));

        List<ServiceResponse> result = assignmentService.getAssignedServices(STAFF_ID);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getServiceCode()).isEqualTo("SVC-0001");
    }
}

