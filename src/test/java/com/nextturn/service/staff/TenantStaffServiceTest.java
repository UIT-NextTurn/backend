package com.nextturn.service.staff;

import com.nextturn.dto.service.ServiceResponse;
import com.nextturn.dto.staff.AssignStaffServicesRequest;
import com.nextturn.dto.staff.CreateStaffRequest;
import com.nextturn.dto.staff.StaffResponse;
import com.nextturn.dto.staff.UpdateStaffRequest;
import com.nextturn.entity.CentralTenant;
import com.nextturn.entity.TenantService;
import com.nextturn.entity.TenantStaff;
import com.nextturn.entity.TenantStaffServiceAssignment;
import com.nextturn.enums.ServiceStatus;
import com.nextturn.enums.StaffRole;
import com.nextturn.enums.StaffStatus;
import com.nextturn.exception.service.ServiceException;
import com.nextturn.exception.staff.StaffErrorCode;
import com.nextturn.exception.staff.StaffException;
import com.nextturn.infrastructure.multitenancy.TenantContextHolder;
import com.nextturn.infrastructure.security.keycloak.KeycloakTokenService;
import com.nextturn.repository.auth.CentralTenantRepository;
import com.nextturn.repository.service.TenantServiceRepository;
import com.nextturn.repository.staff.TenantStaffRepository;
import com.nextturn.repository.staff.TenantStaffServiceRepository;
import com.nextturn.service.notification.EmailService;
import com.nextturn.service.tenant.TenantCacheService;
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

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TenantStaffServiceTest {

    private static final String TENANT_CODE = "tenant-alpha";
    private static final String ORG_ID = "org-uuid-12345";

    @Mock
    private TenantStaffRepository staffRepository;

    @Mock
    private TenantStaffServiceRepository staffServiceRepository;

    @Mock
    private TenantServiceRepository serviceRepository;

    @Mock
    private CentralTenantRepository centralTenantRepository;

    @Mock
    private KeycloakTokenService keycloakTokenService;

    @Mock
    private TenantCacheService tenantCacheService;

    @Mock
    private EmailService emailService;

    @InjectMocks
    private TenantStaffService staffService;

    private CentralTenant mockTenant;

    @BeforeEach
    void setUp() {
        TenantContextHolder.setTenantCode(TENANT_CODE);
        mockTenant = CentralTenant.builder()
                .id("tenant-id-1")
                .tenantCode(TENANT_CODE)
                .tenantName("Tenant Alpha Facility")
                .organisationId(ORG_ID)
                .build();
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
    }

    @Test
    @DisplayName("Should successfully create staff, link to Keycloak, and send activation email")
    void shouldCreateStaffSuccessfully() {
        CreateStaffRequest request = CreateStaffRequest.builder()
                .fullName("Nguyễn Văn A")
                .email("staff.a@tenant.com")
                .phoneNumber("0912345678")
                .role(StaffRole.COUNTER)
                .initialPassword("P@ssw0rd123!")
                .build();

        when(staffRepository.existsByEmail("staff.a@tenant.com")).thenReturn(false);
        when(staffRepository.existsByPhoneNumber("0912345678")).thenReturn(false);
        when(centralTenantRepository.findByTenantCodeAndIsDeletedFalse(TENANT_CODE)).thenReturn(Optional.of(mockTenant));
        when(keycloakTokenService.createStaffUser(eq(TENANT_CODE), eq("staff.a@tenant.com"), eq("Nguyễn Văn A"), eq("P@ssw0rd123!"), eq(StaffRole.COUNTER)))
                .thenReturn("kc-user-uuid-999");
        when(staffRepository.findMaxStaffCode()).thenReturn(Optional.empty());
        when(staffRepository.existsByStaffCode("STAFF-0001")).thenReturn(false);
        when(staffRepository.save(any(TenantStaff.class))).thenAnswer(invocation -> {
            TenantStaff entity = invocation.getArgument(0);
            entity.setCreatedAt(LocalDateTime.now());
            entity.setUpdatedAt(LocalDateTime.now());
            return entity;
        });

        StaffResponse response = staffService.createStaff(request);

        assertThat(response).isNotNull();
        assertThat(response.getFullName()).isEqualTo("Nguyễn Văn A");
        assertThat(response.getEmail()).isEqualTo("staff.a@tenant.com");
        assertThat(response.getStaffCode()).isEqualTo("STAFF-0001");
        assertThat(response.getStatus()).isEqualTo(StaffStatus.ACTIVE);

        verify(keycloakTokenService).addMemberToOrganization(ORG_ID, "kc-user-uuid-999");
        verify(emailService).sendStaffActivationEmail(eq("staff.a@tenant.com"), eq("Nguyễn Văn A"), eq("Tenant Alpha Facility"), anyString());
        verify(staffRepository).save(any(TenantStaff.class));
    }

    @Test
    @DisplayName("Should throw 409 Conflict when staff email already exists")
    void shouldThrowConflict_whenEmailAlreadyExists() {
        CreateStaffRequest request = CreateStaffRequest.builder()
                .fullName("Nguyễn Văn A")
                .email("duplicate@tenant.com")
                .phoneNumber("0912345678")
                .role(StaffRole.COUNTER)
                .build();

        when(staffRepository.existsByEmail("duplicate@tenant.com")).thenReturn(true);

        assertThatThrownBy(() -> staffService.createStaff(request))
                .isInstanceOf(StaffException.class)
                .satisfies(ex -> {
                    StaffException sex = (StaffException) ex;
                    assertThat(sex.getCode()).isEqualTo(StaffErrorCode.STAFF_ALREADY_EXISTS.code());
                    assertThat(sex.getMessage()).isEqualTo("Email hoặc số điện thoại này đã được đăng ký bởi một nhân viên khác trong hệ thống.");
                });

        verify(keycloakTokenService, never()).createStaffUser(any(), any(), any(), any(), any());
        verify(staffRepository, never()).save(any());
    }

    @Test
    @DisplayName("Should throw 409 Conflict when staff phone number already exists")
    void shouldThrowConflict_whenPhoneAlreadyExists() {
        CreateStaffRequest request = CreateStaffRequest.builder()
                .fullName("Nguyễn Văn B")
                .email("staff.b@tenant.com")
                .phoneNumber("0912345678")
                .role(StaffRole.COUNTER)
                .build();

        when(staffRepository.existsByEmail("staff.b@tenant.com")).thenReturn(false);
        when(staffRepository.existsByPhoneNumber("0912345678")).thenReturn(true);

        assertThatThrownBy(() -> staffService.createStaff(request))
                .isInstanceOf(StaffException.class)
                .satisfies(ex -> {
                    StaffException sex = (StaffException) ex;
                    assertThat(sex.getCode()).isEqualTo(StaffErrorCode.STAFF_ALREADY_EXISTS.code());
                });

        verify(keycloakTokenService, never()).createStaffUser(any(), any(), any(), any(), any());
        verify(staffRepository, never()).save(any());
    }

    @Test
    @DisplayName("Should throw 500 when Keycloak user creation fails")
    void shouldThrowException_whenKeycloakFails() {
        CreateStaffRequest request = CreateStaffRequest.builder()
                .fullName("Nguyễn Văn C")
                .email("staff.c@tenant.com")
                .phoneNumber("0912345678")
                .role(StaffRole.COUNTER)
                .build();

        when(staffRepository.existsByEmail("staff.c@tenant.com")).thenReturn(false);
        when(staffRepository.existsByPhoneNumber("0912345678")).thenReturn(false);
        when(centralTenantRepository.findByTenantCodeAndIsDeletedFalse(TENANT_CODE)).thenReturn(Optional.of(mockTenant));
        when(keycloakTokenService.createStaffUser(any(), any(), any(), any(), any()))
                .thenThrow(new RuntimeException("Keycloak connection timeout"));

        assertThatThrownBy(() -> staffService.createStaff(request))
                .isInstanceOf(StaffException.class)
                .satisfies(ex -> {
                    StaffException sex = (StaffException) ex;
                    assertThat(sex.getCode()).isEqualTo(StaffErrorCode.STAFF_KEYCLOAK_SYNC_FAILED.code());
                });

        verify(staffRepository, never()).save(any());
    }

    @Test
    @DisplayName("Assign services should succeed, update junction table, and evict Valkey cache")
    void assignServices_shouldSucceed() {
        String staffId = "staff-uuid-1";
        TenantStaff staff = TenantStaff.builder()
                .id(staffId)
                .staffCode("STAFF-0001")
                .fullName("Nguyễn Văn A")
                .email("staff.a@tenant.com")
                .phoneNumber("0912345678")
                .role(StaffRole.COUNTER)
                .status(StaffStatus.ACTIVE)
                .isDeleted(false)
                .build();

        TenantService service1 = TenantService.builder()
                .id("svc-1")
                .serviceCode("SVC-0001")
                .serviceName("Tư Vấn Khách Hàng")
                .standardDuration(15)
                .price(new BigDecimal("150000.00"))
                .status(ServiceStatus.ACTIVE)
                .isDeleted(false)
                .build();

        TenantService service2 = TenantService.builder()
                .id("svc-2")
                .serviceCode("SVC-0002")
                .serviceName("Tiếp Nhận Hồ Sơ")
                .standardDuration(20)
                .price(new BigDecimal("200000.00"))
                .status(ServiceStatus.ACTIVE)
                .isDeleted(false)
                .build();

        AssignStaffServicesRequest request = AssignStaffServicesRequest.builder()
                .serviceIds(List.of("svc-1", "svc-2"))
                .build();

        when(staffRepository.findByIdAndIsDeletedFalse(staffId)).thenReturn(Optional.of(staff));
        when(serviceRepository.findByIdAndIsDeletedFalse("svc-1")).thenReturn(Optional.of(service1));
        when(serviceRepository.findByIdAndIsDeletedFalse("svc-2")).thenReturn(Optional.of(service2));
        when(staffServiceRepository.findServiceIdsByStaffId(staffId)).thenReturn(List.of("svc-old"));

        StaffResponse response = staffService.assignServices(staffId, request);

        assertThat(response).isNotNull();
        assertThat(response.getAssignedServices()).hasSize(2);
        assertThat(response.getAssignedServices().get(0).getServiceCode()).isEqualTo("SVC-0001");
        assertThat(response.getAssignedServices().get(1).getServiceCode()).isEqualTo("SVC-0002");

        verify(staffServiceRepository).deleteByStaffId(staffId);
        verify(tenantCacheService).evictServiceStaffCache(TENANT_CODE, "svc-old");
        verify(tenantCacheService).evictServiceStaffCache(TENANT_CODE, "svc-1");
        verify(tenantCacheService).evictServiceStaffCache(TENANT_CODE, "svc-2");
    }

    @Test
    @DisplayName("Assign services should throw 400 Bad Request when staff is not ACTIVE")
    void assignServices_shouldThrowBadRequest_whenStaffNotActive() {
        String staffId = "staff-uuid-1";
        TenantStaff staff = TenantStaff.builder()
                .id(staffId)
                .staffCode("STAFF-0001")
                .fullName("Nguyễn Văn A")
                .email("staff.a@tenant.com")
                .phoneNumber("0912345678")
                .role(StaffRole.COUNTER)
                .status(StaffStatus.INACTIVE)
                .isDeleted(false)
                .build();

        AssignStaffServicesRequest request = AssignStaffServicesRequest.builder()
                .serviceIds(List.of("svc-1"))
                .build();

        when(staffRepository.findByIdAndIsDeletedFalse(staffId)).thenReturn(Optional.of(staff));

        assertThatThrownBy(() -> staffService.assignServices(staffId, request))
                .isInstanceOf(StaffException.class)
                .satisfies(ex -> {
                    StaffException sex = (StaffException) ex;
                    assertThat(sex.getCode()).isEqualTo(StaffErrorCode.STAFF_NOT_ACTIVE.code());
                });

        verify(staffServiceRepository, never()).deleteByStaffId(any());
    }

    @Test
    @DisplayName("Assign services should throw 404 when target service does not exist")
    void assignServices_shouldThrowNotFound_whenServiceDoesNotExist() {
        String staffId = "staff-uuid-1";
        TenantStaff staff = TenantStaff.builder()
                .id(staffId)
                .staffCode("STAFF-0001")
                .fullName("Nguyễn Văn A")
                .email("staff.a@tenant.com")
                .phoneNumber("0912345678")
                .role(StaffRole.COUNTER)
                .status(StaffStatus.ACTIVE)
                .isDeleted(false)
                .build();

        AssignStaffServicesRequest request = AssignStaffServicesRequest.builder()
                .serviceIds(List.of("non-existent-svc"))
                .build();

        when(staffRepository.findByIdAndIsDeletedFalse(staffId)).thenReturn(Optional.of(staff));
        when(serviceRepository.findByIdAndIsDeletedFalse("non-existent-svc")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> staffService.assignServices(staffId, request))
                .isInstanceOf(ServiceException.class);

        verify(staffServiceRepository, never()).deleteByStaffId(any());
    }

    @Test
    @DisplayName("getAssignedServices should return list of ServiceResponses")
    void getAssignedServices_shouldReturnList() {
        String staffId = "staff-uuid-1";
        TenantStaff staff = TenantStaff.builder()
                .id(staffId)
                .staffCode("STAFF-0001")
                .status(StaffStatus.ACTIVE)
                .isDeleted(false)
                .build();

        TenantService service1 = TenantService.builder()
                .id("svc-1")
                .serviceCode("SVC-0001")
                .serviceName("Tư Vấn")
                .standardDuration(15)
                .price(new BigDecimal("100000.00"))
                .status(ServiceStatus.ACTIVE)
                .build();

        TenantStaffServiceAssignment assignment = TenantStaffServiceAssignment.builder()
                .id("assign-1")
                .staff(staff)
                .service(service1)
                .build();

        when(staffRepository.findByIdAndIsDeletedFalse(staffId)).thenReturn(Optional.of(staff));
        when(staffServiceRepository.findByStaffIdWithService(staffId)).thenReturn(List.of(assignment));

        List<ServiceResponse> result = staffService.getAssignedServices(staffId);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getServiceCode()).isEqualTo("SVC-0001");
    }

    @Test
    @DisplayName("updateStaff should update details")
    void updateStaff_shouldUpdateDetails() {
        String staffId = "staff-uuid-1";
        TenantStaff staff = TenantStaff.builder()
                .id(staffId)
                .staffCode("STAFF-0001")
                .fullName("Old Name")
                .phoneNumber("0912345678")
                .role(StaffRole.COUNTER)
                .status(StaffStatus.ACTIVE)
                .isDeleted(false)
                .build();

        UpdateStaffRequest request = UpdateStaffRequest.builder()
                .fullName("New Name")
                .phoneNumber("0987654321")
                .build();

        when(staffRepository.findByIdAndIsDeletedFalse(staffId)).thenReturn(Optional.of(staff));
        when(staffRepository.existsByPhoneNumberAndIsDeletedFalse("0987654321")).thenReturn(false);
        when(staffRepository.save(any(TenantStaff.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(staffServiceRepository.findByStaffIdWithService(staffId)).thenReturn(Collections.emptyList());

        StaffResponse response = staffService.updateStaff(staffId, request);

        assertThat(response.getFullName()).isEqualTo("New Name");
        assertThat(response.getPhoneNumber()).isEqualTo("0987654321");
    }

    @Test
    @DisplayName("deleteStaff should set isDeleted to true and status to INACTIVE")
    void deleteStaff_shouldSoftDelete() {
        String staffId = "staff-uuid-1";
        TenantStaff staff = TenantStaff.builder()
                .id(staffId)
                .staffCode("STAFF-0001")
                .status(StaffStatus.ACTIVE)
                .isDeleted(false)
                .build();

        when(staffRepository.findByIdAndIsDeletedFalse(staffId)).thenReturn(Optional.of(staff));
        when(staffRepository.save(any(TenantStaff.class))).thenAnswer(invocation -> invocation.getArgument(0));

        staffService.deleteStaff(staffId);

        assertThat(staff.getIsDeleted()).isTrue();
        assertThat(staff.getStatus()).isEqualTo(StaffStatus.INACTIVE);
        verify(staffRepository).save(staff);
    }

    @Test
    @DisplayName("updateStaff with status INACTIVE should deactivate and revoke Keycloak session when no active commitments")
    void updateStaff_shouldDeactivateAndRevokeKeycloakSession_whenNoActiveCommitments() {
        String staffId = "staff-uuid-1";
        TenantStaff staff = TenantStaff.builder()
                .id(staffId)
                .staffCode("STAFF-0001")
                .fullName("Nguyễn Văn A")
                .phoneNumber("0912345678")
                .keycloakUserId("kc-user-123")
                .role(StaffRole.COUNTER)
                .status(StaffStatus.ACTIVE)
                .isDeleted(false)
                .build();

        UpdateStaffRequest request = UpdateStaffRequest.builder()
                .status(StaffStatus.INACTIVE)
                .build();

        when(staffRepository.findByIdAndIsDeletedFalse(staffId)).thenReturn(Optional.of(staff));
        when(staffRepository.findUpcomingAppointmentCodesByStaffId(staffId)).thenReturn(Collections.emptyList());
        when(staffRepository.countUpcomingShiftsByStaffId(staffId)).thenReturn(0L);
        when(staffRepository.save(any(TenantStaff.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(staffServiceRepository.findServiceIdsByStaffId(staffId)).thenReturn(List.of("svc-1"));
        when(staffServiceRepository.findByStaffIdWithService(staffId)).thenReturn(Collections.emptyList());

        StaffResponse response = staffService.updateStaff(staffId, request);

        assertThat(response.getStatus()).isEqualTo(StaffStatus.INACTIVE);
        verify(keycloakTokenService).updateUserStatusInKeycloak("kc-user-123", false);
        verify(keycloakTokenService).logoutUserInKeycloak("kc-user-123");
        verify(tenantCacheService).evictServiceStaffCache(TENANT_CODE, "svc-1");
    }

    @Test
    @DisplayName("updateStaff with status INACTIVE should throw 409 Conflict when upcoming appointments exist")
    void updateStaff_shouldThrowConflict_whenDeactivatingWithUpcomingAppointments() {
        String staffId = "staff-uuid-1";
        TenantStaff staff = TenantStaff.builder()
                .id(staffId)
                .staffCode("STAFF-0001")
                .fullName("Nguyễn Văn A")
                .phoneNumber("0912345678")
                .role(StaffRole.COUNTER)
                .status(StaffStatus.ACTIVE)
                .isDeleted(false)
                .build();

        UpdateStaffRequest request = UpdateStaffRequest.builder()
                .status(StaffStatus.INACTIVE)
                .build();

        when(staffRepository.findByIdAndIsDeletedFalse(staffId)).thenReturn(Optional.of(staff));
        when(staffRepository.findUpcomingAppointmentCodesByStaffId(staffId)).thenReturn(List.of("APT-0001", "APT-0002"));

        assertThatThrownBy(() -> staffService.updateStaff(staffId, request))
                .isInstanceOf(StaffException.class)
                .satisfies(ex -> {
                    StaffException sex = (StaffException) ex;
                    assertThat(sex.getCode()).isEqualTo(StaffErrorCode.STAFF_CANNOT_DEACTIVATE_ACTIVE_SCHEDULE.code());
                    assertThat(sex.getMessage()).contains("APT-0001", "APT-0002", "chuyển giao");
                });

        verify(keycloakTokenService, never()).updateUserStatusInKeycloak(any(), any(Boolean.class));
    }

    @Test
    @DisplayName("updateStaff with status INACTIVE should throw 409 Conflict when upcoming shifts exist")
    void updateStaff_shouldThrowConflict_whenDeactivatingWithUpcomingShifts() {
        String staffId = "staff-uuid-1";
        TenantStaff staff = TenantStaff.builder()
                .id(staffId)
                .staffCode("STAFF-0001")
                .fullName("Nguyễn Văn A")
                .phoneNumber("0912345678")
                .role(StaffRole.COUNTER)
                .status(StaffStatus.ACTIVE)
                .isDeleted(false)
                .build();

        UpdateStaffRequest request = UpdateStaffRequest.builder()
                .status(StaffStatus.INACTIVE)
                .build();

        when(staffRepository.findByIdAndIsDeletedFalse(staffId)).thenReturn(Optional.of(staff));
        when(staffRepository.findUpcomingAppointmentCodesByStaffId(staffId)).thenReturn(Collections.emptyList());
        when(staffRepository.countUpcomingShiftsByStaffId(staffId)).thenReturn(2L);

        assertThatThrownBy(() -> staffService.updateStaff(staffId, request))
                .isInstanceOf(StaffException.class)
                .satisfies(ex -> {
                    StaffException sex = (StaffException) ex;
                    assertThat(sex.getCode()).isEqualTo(StaffErrorCode.STAFF_CANNOT_DEACTIVATE_ACTIVE_SCHEDULE.code());
                    assertThat(sex.getMessage()).contains("ca trực trong tương lai");
                });

        verify(keycloakTokenService, never()).updateUserStatusInKeycloak(any(), any(Boolean.class));
    }

    @Test
    @DisplayName("updateStaff with status ACTIVE should reactivate and re-enable Keycloak user")
    void updateStaff_shouldReactivateStaffAndEnableInKeycloak() {
        String staffId = "staff-uuid-1";
        TenantStaff staff = TenantStaff.builder()
                .id(staffId)
                .staffCode("STAFF-0001")
                .fullName("Nguyễn Văn A")
                .phoneNumber("0912345678")
                .keycloakUserId("kc-user-123")
                .role(StaffRole.COUNTER)
                .status(StaffStatus.INACTIVE)
                .isDeleted(false)
                .build();

        UpdateStaffRequest request = UpdateStaffRequest.builder()
                .status(StaffStatus.ACTIVE)
                .build();

        when(staffRepository.findByIdAndIsDeletedFalse(staffId)).thenReturn(Optional.of(staff));
        when(staffRepository.save(any(TenantStaff.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(staffServiceRepository.findServiceIdsByStaffId(staffId)).thenReturn(List.of("svc-1"));
        when(staffServiceRepository.findByStaffIdWithService(staffId)).thenReturn(Collections.emptyList());

        StaffResponse response = staffService.updateStaff(staffId, request);

        assertThat(response.getStatus()).isEqualTo(StaffStatus.ACTIVE);
        verify(keycloakTokenService).updateUserStatusInKeycloak("kc-user-123", true);
        verify(tenantCacheService).evictServiceStaffCache(TENANT_CODE, "svc-1");
    }

    @Test
    @DisplayName("deleteStaff should throw 409 Conflict when upcoming appointments exist")
    void deleteStaff_shouldThrowConflict_whenUpcomingAppointmentsExist() {
        String staffId = "staff-uuid-1";
        TenantStaff staff = TenantStaff.builder()
                .id(staffId)
                .staffCode("STAFF-0001")
                .status(StaffStatus.ACTIVE)
                .isDeleted(false)
                .build();

        when(staffRepository.findByIdAndIsDeletedFalse(staffId)).thenReturn(Optional.of(staff));
        when(staffRepository.findUpcomingAppointmentCodesByStaffId(staffId)).thenReturn(List.of("APT-9999"));

        assertThatThrownBy(() -> staffService.deleteStaff(staffId))
                .isInstanceOf(StaffException.class)
                .satisfies(ex -> {
                    StaffException sex = (StaffException) ex;
                    assertThat(sex.getCode()).isEqualTo(StaffErrorCode.STAFF_CANNOT_DEACTIVATE_ACTIVE_SCHEDULE.code());
                    assertThat(sex.getMessage()).contains("APT-9999");
                });

        verify(staffRepository, never()).save(any());
    }

    @Test
    @DisplayName("hardDeleteStaff should throw 409 Conflict when historical queue tickets exist")
    void hardDeleteStaff_shouldThrowConflict_whenHistoricalQueueTicketsExist() {
        String staffId = "staff-uuid-1";
        TenantStaff staff = TenantStaff.builder()
                .id(staffId)
                .staffCode("STAFF-0001")
                .status(StaffStatus.INACTIVE)
                .isDeleted(true)
                .build();

        when(staffRepository.findById(staffId)).thenReturn(Optional.of(staff));
        when(staffRepository.countHistoricalQueueTicketsByStaffId(staffId)).thenReturn(5L);

        assertThatThrownBy(() -> staffService.deleteStaff(staffId, true))
                .isInstanceOf(StaffException.class)
                .satisfies(ex -> {
                    StaffException sex = (StaffException) ex;
                    assertThat(sex.getCode()).isEqualTo(StaffErrorCode.STAFF_CANNOT_HARD_DELETE_HISTORICAL_DATA.code());
                    assertThat(sex.getMessage()).contains("từ chối thao tác Xóa vật lý");
                });

        verify(staffRepository, never()).delete(any());
    }

    @Test
    @DisplayName("hardDeleteStaff should throw 409 Conflict when historical feedback ratings exist")
    void hardDeleteStaff_shouldThrowConflict_whenHistoricalFeedbacksExist() {
        String staffId = "staff-uuid-1";
        TenantStaff staff = TenantStaff.builder()
                .id(staffId)
                .staffCode("STAFF-0001")
                .status(StaffStatus.INACTIVE)
                .isDeleted(true)
                .build();

        when(staffRepository.findById(staffId)).thenReturn(Optional.of(staff));
        when(staffRepository.countHistoricalQueueTicketsByStaffId(staffId)).thenReturn(0L);
        when(staffRepository.countHistoricalFeedbacksByStaffId(staffId)).thenReturn(2L);

        assertThatThrownBy(() -> staffService.deleteStaff(staffId, true))
                .isInstanceOf(StaffException.class)
                .satisfies(ex -> {
                    StaffException sex = (StaffException) ex;
                    assertThat(sex.getCode()).isEqualTo(StaffErrorCode.STAFF_CANNOT_HARD_DELETE_HISTORICAL_DATA.code());
                });

        verify(staffRepository, never()).delete(any());
    }

    @Test
    @DisplayName("hardDeleteStaff should throw 409 Conflict when historical completed shifts exist")
    void hardDeleteStaff_shouldThrowConflict_whenHistoricalShiftsExist() {
        String staffId = "staff-uuid-1";
        TenantStaff staff = TenantStaff.builder()
                .id(staffId)
                .staffCode("STAFF-0001")
                .status(StaffStatus.INACTIVE)
                .isDeleted(true)
                .build();

        when(staffRepository.findById(staffId)).thenReturn(Optional.of(staff));
        when(staffRepository.countHistoricalQueueTicketsByStaffId(staffId)).thenReturn(0L);
        when(staffRepository.countHistoricalFeedbacksByStaffId(staffId)).thenReturn(0L);
        when(staffRepository.countHistoricalShiftsByStaffId(staffId)).thenReturn(3L);

        assertThatThrownBy(() -> staffService.deleteStaff(staffId, true))
                .isInstanceOf(StaffException.class)
                .satisfies(ex -> {
                    StaffException sex = (StaffException) ex;
                    assertThat(sex.getCode()).isEqualTo(StaffErrorCode.STAFF_CANNOT_HARD_DELETE_HISTORICAL_DATA.code());
                });

        verify(staffRepository, never()).delete(any());
    }

    @Test
    @DisplayName("searchStaff should filter by keyword, role, and status")
    void searchStaff_shouldFilterCorrectly() {
        TenantStaff staff = TenantStaff.builder()
                .id("staff-uuid-1")
                .staffCode("STAFF-0001")
                .fullName("Nguyễn Văn A")
                .email("staff.a@tenant.com")
                .phoneNumber("0912345678")
                .role(StaffRole.COUNTER)
                .status(StaffStatus.ACTIVE)
                .isDeleted(false)
                .build();

        PageRequest pageRequest = PageRequest.of(0, 10);
        when(staffRepository.searchStaff("Nguyễn", StaffRole.COUNTER, StaffStatus.ACTIVE, pageRequest))
                .thenReturn(new PageImpl<>(List.of(staff)));
        when(staffServiceRepository.findByStaffIdWithService("staff-uuid-1"))
                .thenReturn(Collections.emptyList());

        Page<StaffResponse> result = staffService.searchStaff("Nguyễn", StaffRole.COUNTER, StaffStatus.ACTIVE, pageRequest);

        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().get(0).getFullName()).isEqualTo("Nguyễn Văn A");
    }

    @Test
    @DisplayName("hardDeleteStaff should physically delete from MySQL and Keycloak when no historical records and no commitments")
    void hardDeleteStaff_shouldDeletePhysically_whenNoHistoricalDataAndNoCommitments() {
        String staffId = "staff-uuid-1";
        TenantStaff staff = TenantStaff.builder()
                .id(staffId)
                .staffCode("STAFF-0001")
                .keycloakUserId("kc-user-del")
                .status(StaffStatus.INACTIVE)
                .isDeleted(true)
                .build();

        when(staffRepository.findById(staffId)).thenReturn(Optional.of(staff));
        when(staffRepository.countHistoricalQueueTicketsByStaffId(staffId)).thenReturn(0L);
        when(staffRepository.countHistoricalFeedbacksByStaffId(staffId)).thenReturn(0L);
        when(staffRepository.countHistoricalShiftsByStaffId(staffId)).thenReturn(0L);
        when(staffRepository.findUpcomingAppointmentCodesByStaffId(staffId)).thenReturn(Collections.emptyList());
        when(staffRepository.countUpcomingShiftsByStaffId(staffId)).thenReturn(0L);
        when(centralTenantRepository.findByTenantCodeAndIsDeletedFalse(TENANT_CODE)).thenReturn(Optional.of(mockTenant));

        staffService.deleteStaff(staffId, true);

        verify(staffServiceRepository).deleteByStaffId(staffId);
        verify(staffRepository).delete(staff);
        verify(keycloakTokenService).deleteStaffUserInKeycloak(ORG_ID, "kc-user-del");
    }
}

