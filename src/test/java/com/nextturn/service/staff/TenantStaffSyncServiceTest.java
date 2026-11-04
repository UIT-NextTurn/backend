package com.nextturn.service.staff;

import com.nextturn.entity.TenantStaff;
import com.nextturn.enums.StaffRole;
import com.nextturn.enums.StaffStatus;
import com.nextturn.infrastructure.multitenancy.TenantContextHolder;
import com.nextturn.repository.staff.TenantStaffRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TenantStaffSyncServiceTest {

    private static final String TENANT_CODE = "tenant-alpha";

    @Mock
    private TenantStaffRepository staffRepository;

    @Mock
    private TenantStaffCodeGenerator codeGenerator;

    @InjectMocks
    private TenantStaffSyncService syncService;

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
    }

    @Test
    @DisplayName("syncOrProvisionStaffOnLogin should synchronize profile when staff already exists in tenant DB")
    void syncOrProvision_shouldUpdateExistingStaff() {
        TenantStaff existing = TenantStaff.builder()
                .id("staff-uuid-1")
                .staffCode("STAFF-0001")
                .keycloakUserId("kc-user-old")
                .fullName("Old Name")
                .email("sync.user@tenant.com")
                .phoneNumber("0912345678")
                .role(StaffRole.COUNTER)
                .status(StaffStatus.ACTIVE)
                .isDeleted(false)
                .build();

        when(staffRepository.findByKeycloakUserIdAndIsDeletedFalse("kc-user-new")).thenReturn(Optional.empty());
        when(staffRepository.findByEmailAndIsDeletedFalse("sync.user@tenant.com")).thenReturn(Optional.of(existing));

        TenantStaff result = syncService.syncOrProvisionStaffOnLogin(
                TENANT_CODE,
                "kc-user-new",
                "sync.user@tenant.com",
                "New Full Name",
                List.of("TENANT_ADMIN")
        );

        assertThat(result.getKeycloakUserId()).isEqualTo("kc-user-new");
        assertThat(result.getFullName()).isEqualTo("New Full Name");
        assertThat(result.getRole()).isEqualTo(StaffRole.ADMIN);
        verify(staffRepository).save(existing);
    }

    @Test
    @DisplayName("syncOrProvisionStaffOnLogin should JIT provision new staff when authenticated via enterprise SSO")
    void syncOrProvision_shouldJitProvisionNewStaff() {
        when(staffRepository.findByKeycloakUserIdAndIsDeletedFalse("kc-jit-123")).thenReturn(Optional.empty());
        when(staffRepository.findByEmailAndIsDeletedFalse("sso.new@tenant.com")).thenReturn(Optional.empty());
        when(codeGenerator.generateNextStaffCode()).thenReturn("STAFF-0005");
        when(staffRepository.existsByPhoneNumber(any())).thenReturn(false);
        when(staffRepository.save(any(TenantStaff.class))).thenAnswer(invocation -> invocation.getArgument(0));

        TenantStaff provisioned = syncService.syncOrProvisionStaffOnLogin(
                TENANT_CODE,
                "kc-jit-123",
                "sso.new@tenant.com",
                "SSO Provisioned User",
                List.of("ROLE_TENANT_ADMIN")
        );

        assertThat(provisioned).isNotNull();
        assertThat(provisioned.getStaffCode()).isEqualTo("STAFF-0005");
        assertThat(provisioned.getEmail()).isEqualTo("sso.new@tenant.com");
        assertThat(provisioned.getFullName()).isEqualTo("SSO Provisioned User");
        assertThat(provisioned.getRole()).isEqualTo(StaffRole.ADMIN);
        assertThat(provisioned.getStatus()).isEqualTo(StaffStatus.ACTIVE);
        assertThat(provisioned.getPhoneNumber()).startsWith("09");
        verify(staffRepository).save(any(TenantStaff.class));
    }
}

