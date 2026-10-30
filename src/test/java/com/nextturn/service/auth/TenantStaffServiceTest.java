package com.nextturn.service.auth;

import com.nextturn.dto.auth.CreateStaffRequest;
import com.nextturn.dto.auth.StaffResponse;
import com.nextturn.dto.auth.UpdateStaffRequest;
import com.nextturn.entity.CentralTenant;
import com.nextturn.entity.TenantStaff;
import com.nextturn.enums.StaffRole;
import com.nextturn.enums.StaffStatus;
import com.nextturn.exception.admin.AdminErrorCode;
import com.nextturn.exception.admin.AdminException;
import com.nextturn.exception.tenant.TenantErrorCode;
import com.nextturn.exception.tenant.TenantException;
import com.nextturn.infrastructure.multitenancy.TenantContextHolder;
import com.nextturn.infrastructure.security.keycloak.KeycloakTokenService;
import com.nextturn.repository.auth.CentralTenantRepository;
import com.nextturn.repository.auth.TenantStaffRepository;
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

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TenantStaffServiceTest {

    private static final String TENANT_CODE = "clinic_sai_gon";
    private static final String ORG_ID = "org-uuid-12345";

    @Mock
    private TenantStaffRepository staffRepository;

    @Mock
    private CentralTenantRepository centralTenantRepository;

    @Mock
    private KeycloakTokenService keycloakTokenService;

    @InjectMocks
    private TenantStaffService staffService;

    private CentralTenant mockTenant;

    @BeforeEach
    void setUp() {
        TenantContextHolder.setTenantCode(TENANT_CODE);
        mockTenant = CentralTenant.builder()
                .id("tenant-id-1")
                .tenantCode(TENANT_CODE)
                .tenantName("Clinic Saigon")
                .organisationId(ORG_ID)
                .build();
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
    }

    @Test
    @DisplayName("Should successfully create encounter staff and link to Keycloak Organization")
    void shouldCreateStaffSuccessfully() {
        CreateStaffRequest request = CreateStaffRequest.builder()
                .fullName("Dr. Nguyen Van A")
                .email("doctor.a@clinic.vn")
                .phoneNumber("+84901234567")
                .role(StaffRole.COUNTER)
                .initialPassword("P@ssw0rd123!")
                .build();

        when(staffRepository.existsByEmailAndIsDeletedFalse("doctor.a@clinic.vn")).thenReturn(false);
        when(staffRepository.existsByPhoneNumberAndIsDeletedFalse("+84901234567")).thenReturn(false);
        when(centralTenantRepository.findByTenantCodeAndIsDeletedFalse(TENANT_CODE)).thenReturn(Optional.of(mockTenant));
        when(keycloakTokenService.createStaffUser(eq(TENANT_CODE), eq("doctor.a@clinic.vn"), eq("Dr. Nguyen Van A"), eq("P@ssw0rd123!"), eq(StaffRole.COUNTER)))
                .thenReturn("kc-user-uuid-999");
        when(staffRepository.save(any(TenantStaff.class))).thenAnswer(invocation -> invocation.getArgument(0));

        StaffResponse response = staffService.createStaff(request);

        assertThat(response).isNotNull();
        assertThat(response.getFullName()).isEqualTo("Dr. Nguyen Van A");
        assertThat(response.getEmail()).isEqualTo("doctor.a@clinic.vn");
        assertThat(response.getPhoneNumber()).isEqualTo("+84901234567");
        assertThat(response.getRole()).isEqualTo(StaffRole.COUNTER);
        assertThat(response.getStatus()).isEqualTo(StaffStatus.ACTIVE);
        assertThat(response.getKeycloakUserId()).isEqualTo("kc-user-uuid-999");
        assertThat(response.getStaffCode()).startsWith("STF-");

        verify(keycloakTokenService).addMemberToOrganization(ORG_ID, "kc-user-uuid-999");
        verify(staffRepository).save(any(TenantStaff.class));
    }

    @Test
    @DisplayName("Should trigger compensating Keycloak deletion when database save fails")
    void shouldCompensateKeycloakWhenDatabaseSaveFails() {
        CreateStaffRequest request = CreateStaffRequest.builder()
                .fullName("Dr. Error Case")
                .email("error@clinic.vn")
                .phoneNumber("+84901234999")
                .role(StaffRole.COUNTER)
                .build();

        when(staffRepository.existsByEmailAndIsDeletedFalse("error@clinic.vn")).thenReturn(false);
        when(staffRepository.existsByPhoneNumberAndIsDeletedFalse("+84901234999")).thenReturn(false);
        when(centralTenantRepository.findByTenantCodeAndIsDeletedFalse(TENANT_CODE)).thenReturn(Optional.of(mockTenant));
        when(keycloakTokenService.createStaffUser(eq(TENANT_CODE), eq("error@clinic.vn"), eq("Dr. Error Case"), any(), eq(StaffRole.COUNTER)))
                .thenReturn("kc-temp-error-uuid");
        when(staffRepository.save(any(TenantStaff.class))).thenThrow(new RuntimeException("Database disk error"));

        assertThatThrownBy(() -> staffService.createStaff(request))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("Database disk error");

        verify(keycloakTokenService).deleteStaffUserInKeycloak(ORG_ID, "kc-temp-error-uuid");
    }

    @Test
    @DisplayName("Should throw STAFF_ALREADY_EXISTS when email is already registered")
    void shouldThrowWhenEmailExists() {
        CreateStaffRequest request = CreateStaffRequest.builder()
                .fullName("Dr. Duplicate")
                .email("existing@clinic.vn")
                .phoneNumber("+84901234567")
                .build();

        when(staffRepository.existsByEmailAndIsDeletedFalse("existing@clinic.vn")).thenReturn(true);

        assertThatThrownBy(() -> staffService.createStaff(request))
                .isInstanceOf(AdminException.class)
                .satisfies(ex -> assertThat(((AdminException) ex).getCode()).isEqualTo(AdminErrorCode.STAFF_ALREADY_EXISTS.code()));
    }

    @Test
    @DisplayName("Should throw TENANT_CONTEXT_MISSING when tenant header is missing")
    void shouldThrowWhenTenantContextMissing() {
        TenantContextHolder.clear();

        CreateStaffRequest request = CreateStaffRequest.builder()
                .fullName("Dr. NoContext")
                .email("nocontext@clinic.vn")
                .phoneNumber("+84901234567")
                .build();

        assertThatThrownBy(() -> staffService.createStaff(request))
                .isInstanceOf(TenantException.class)
                .satisfies(ex -> assertThat(((TenantException) ex).getCode()).isEqualTo(TenantErrorCode.TENANT_CONTEXT_MISSING.code()));
    }

    @Test
    @DisplayName("Should retrieve paginated staff list")
    void shouldGetStaffList() {
        TenantStaff staff = TenantStaff.builder()
                .id("staff-1")
                .staffCode("STF-000001")
                .fullName("Nurse B")
                .email("nurse.b@clinic.vn")
                .phoneNumber("+84901234568")
                .role(StaffRole.COUNTER)
                .status(StaffStatus.ACTIVE)
                .build();

        when(staffRepository.findAllByIsDeletedFalse(any(PageRequest.class)))
                .thenReturn(new PageImpl<>(List.of(staff)));

        Page<StaffResponse> page = staffService.getStaffList(PageRequest.of(0, 10));

        assertThat(page.getTotalElements()).isEqualTo(1);
        assertThat(page.getContent().get(0).getFullName()).isEqualTo("Nurse B");
    }

    @Test
    @DisplayName("Should update staff profile and sync status with Keycloak")
    void shouldUpdateStaffAndSyncKeycloakStatus() {
        TenantStaff staff = TenantStaff.builder()
                .id("staff-1")
                .staffCode("STF-000001")
                .keycloakUserId("kc-user-uuid-1")
                .fullName("Nurse B")
                .email("nurse.b@clinic.vn")
                .phoneNumber("+84901234568")
                .role(StaffRole.COUNTER)
                .status(StaffStatus.ACTIVE)
                .build();

        when(staffRepository.findByIdAndIsDeletedFalse("staff-1")).thenReturn(Optional.of(staff));
        when(staffRepository.save(any(TenantStaff.class))).thenAnswer(invocation -> invocation.getArgument(0));

        UpdateStaffRequest updateRequest = UpdateStaffRequest.builder()
                .fullName("Nurse B (Senior)")
                .status(StaffStatus.INACTIVE)
                .build();

        StaffResponse response = staffService.updateStaff("staff-1", updateRequest);

        assertThat(response.getFullName()).isEqualTo("Nurse B (Senior)");
        assertThat(response.getStatus()).isEqualTo(StaffStatus.INACTIVE);

        verify(keycloakTokenService).updateUserStatusInKeycloak("kc-user-uuid-1", false);
    }

    @Test
    @DisplayName("Should soft-delete staff member and delete from Keycloak")
    void shouldDeleteStaff() {
        TenantStaff staff = TenantStaff.builder()
                .id("staff-1")
                .keycloakUserId("kc-user-uuid-1")
                .email("delete@clinic.vn")
                .build();

        when(staffRepository.findByIdAndIsDeletedFalse("staff-1")).thenReturn(Optional.of(staff));
        when(centralTenantRepository.findByTenantCodeAndIsDeletedFalse(TENANT_CODE)).thenReturn(Optional.of(mockTenant));

        staffService.deleteStaff("staff-1");

        assertThat(staff.getIsDeleted()).isTrue();
        assertThat(staff.getStatus()).isEqualTo(StaffStatus.INACTIVE);
        verify(keycloakTokenService).deleteStaffUserInKeycloak(ORG_ID, "kc-user-uuid-1");
        verify(staffRepository).save(staff);
    }

    @Test
    @DisplayName("Should create specialist staff in roster without creating Keycloak user")
    void shouldCreateSpecialistStaffInRosterWithoutKeycloakUser() {
        CreateStaffRequest request = CreateStaffRequest.builder()
                .fullName("Dr. Specialist Nguyen")
                .email("specialist@clinic.vn")
                .phoneNumber("+84909999888")
                .role(StaffRole.SPECIALIST)
                .build();

        when(staffRepository.existsByEmailAndIsDeletedFalse("specialist@clinic.vn")).thenReturn(false);
        when(staffRepository.existsByPhoneNumberAndIsDeletedFalse("+84909999888")).thenReturn(false);
        when(centralTenantRepository.findByTenantCodeAndIsDeletedFalse(TENANT_CODE)).thenReturn(Optional.of(mockTenant));
        when(staffRepository.save(any(TenantStaff.class))).thenAnswer(invocation -> invocation.getArgument(0));

        StaffResponse response = staffService.createStaff(request);

        assertThat(response).isNotNull();
        assertThat(response.getFullName()).isEqualTo("Dr. Specialist Nguyen");
        assertThat(response.getEmail()).isEqualTo("specialist@clinic.vn");
        assertThat(response.getRole()).isEqualTo(StaffRole.SPECIALIST);
        assertThat(response.getStatus()).isEqualTo(StaffStatus.ACTIVE);
        assertThat(response.getKeycloakUserId()).isNull();

        verify(keycloakTokenService, never()).createStaffUser(any(), any(), any(), any(), any());
        verify(keycloakTokenService, never()).addMemberToOrganization(any(), any());
        verify(staffRepository).save(any(TenantStaff.class));
    }

    @Test
    @DisplayName("Should provision Keycloak user when specialist is promoted to counter staff")
    void shouldProvisionKeycloakWhenSpecialistPromotedToCounter() {
        TenantStaff staff = TenantStaff.builder()
                .id("specialist-1")
                .staffCode("STF-000009")
                .keycloakUserId(null)
                .fullName("Dr. Promotee")
                .email("promotee@clinic.vn")
                .phoneNumber("+84901112233")
                .role(StaffRole.SPECIALIST)
                .status(StaffStatus.ACTIVE)
                .build();

        when(staffRepository.findByIdAndIsDeletedFalse("specialist-1")).thenReturn(Optional.of(staff));
        when(centralTenantRepository.findByTenantCodeAndIsDeletedFalse(TENANT_CODE)).thenReturn(Optional.of(mockTenant));
        when(keycloakTokenService.createStaffUser(eq(TENANT_CODE), eq("promotee@clinic.vn"), eq("Dr. Promotee"), eq(null), eq(StaffRole.COUNTER)))
                .thenReturn("kc-promoted-uuid");
        when(staffRepository.save(any(TenantStaff.class))).thenAnswer(invocation -> invocation.getArgument(0));

        UpdateStaffRequest updateRequest = UpdateStaffRequest.builder()
                .role(StaffRole.COUNTER)
                .build();

        StaffResponse response = staffService.updateStaff("specialist-1", updateRequest);

        assertThat(response.getRole()).isEqualTo(StaffRole.COUNTER);
        assertThat(response.getKeycloakUserId()).isEqualTo("kc-promoted-uuid");
        verify(keycloakTokenService).addMemberToOrganization(ORG_ID, "kc-promoted-uuid");
    }

    @Test
    @DisplayName("Should remove Keycloak user when counter staff is reassigned to specialist roster-only")
    void shouldRemoveKeycloakWhenCounterReassignedToSpecialist() {
        TenantStaff staff = TenantStaff.builder()
                .id("counter-1")
                .staffCode("STF-000008")
                .keycloakUserId("kc-counter-uuid")
                .fullName("Counter Staff A")
                .email("counter.a@clinic.vn")
                .phoneNumber("+84902223344")
                .role(StaffRole.COUNTER)
                .status(StaffStatus.ACTIVE)
                .build();

        when(staffRepository.findByIdAndIsDeletedFalse("counter-1")).thenReturn(Optional.of(staff));
        when(centralTenantRepository.findByTenantCodeAndIsDeletedFalse(TENANT_CODE)).thenReturn(Optional.of(mockTenant));
        when(staffRepository.save(any(TenantStaff.class))).thenAnswer(invocation -> invocation.getArgument(0));

        UpdateStaffRequest updateRequest = UpdateStaffRequest.builder()
                .role(StaffRole.SPECIALIST)
                .build();

        StaffResponse response = staffService.updateStaff("counter-1", updateRequest);

        assertThat(response.getRole()).isEqualTo(StaffRole.SPECIALIST);
        assertThat(response.getKeycloakUserId()).isNull();
        verify(keycloakTokenService).deleteStaffUserInKeycloak(ORG_ID, "kc-counter-uuid");
    }

    @Test
    @DisplayName("Should revert Keycloak status when database update fails during updateStaff")
    void shouldCompensateKeycloakStatusWhenUpdateStaffFails() {
        TenantStaff staff = TenantStaff.builder()
                .id("counter-active-1")
                .staffCode("STF-000007")
                .keycloakUserId("kc-active-user-uuid")
                .fullName("Active Staff")
                .email("activestaff@clinic.vn")
                .phoneNumber("+84903334455")
                .role(StaffRole.COUNTER)
                .status(StaffStatus.ACTIVE)
                .build();

        when(staffRepository.findByIdAndIsDeletedFalse("counter-active-1")).thenReturn(Optional.of(staff));
        when(staffRepository.save(any(TenantStaff.class))).thenThrow(new RuntimeException("DB update constraint failure"));

        UpdateStaffRequest updateRequest = UpdateStaffRequest.builder()
                .status(StaffStatus.INACTIVE)
                .build();

        assertThatThrownBy(() -> staffService.updateStaff("counter-active-1", updateRequest))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("DB update constraint failure");

        // Verify it was set to false, and then compensated/reverted back to true
        verify(keycloakTokenService).updateUserStatusInKeycloak("kc-active-user-uuid", false);
        verify(keycloakTokenService).updateUserStatusInKeycloak("kc-active-user-uuid", true);
    }

    @Test
    @DisplayName("Should delete newly created Keycloak user if database update fails during specialist promotion")
    void shouldCompensateNewKeycloakUserWhenPromotionFailsOnDb() {
        TenantStaff staff = TenantStaff.builder()
                .id("spec-to-promote")
                .staffCode("STF-000006")
                .keycloakUserId(null)
                .fullName("Dr. Promoted Error")
                .email("promoted.error@clinic.vn")
                .phoneNumber("+84905556677")
                .role(StaffRole.SPECIALIST)
                .status(StaffStatus.ACTIVE)
                .build();

        when(staffRepository.findByIdAndIsDeletedFalse("spec-to-promote")).thenReturn(Optional.of(staff));
        when(centralTenantRepository.findByTenantCodeAndIsDeletedFalse(TENANT_CODE)).thenReturn(Optional.of(mockTenant));
        when(keycloakTokenService.createStaffUser(eq(TENANT_CODE), eq("promoted.error@clinic.vn"), eq("Dr. Promoted Error"), eq(null), eq(StaffRole.COUNTER)))
                .thenReturn("kc-new-to-delete-uuid");
        when(staffRepository.save(any(TenantStaff.class))).thenThrow(new RuntimeException("DB failure during promotion"));

        UpdateStaffRequest updateRequest = UpdateStaffRequest.builder()
                .role(StaffRole.COUNTER)
                .build();

        assertThatThrownBy(() -> staffService.updateStaff("spec-to-promote", updateRequest))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("DB failure during promotion");

        verify(keycloakTokenService).deleteStaffUserInKeycloak(ORG_ID, "kc-new-to-delete-uuid");
    }
}
