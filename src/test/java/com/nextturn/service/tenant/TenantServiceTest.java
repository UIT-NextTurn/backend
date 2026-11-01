package com.nextturn.service.tenant;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nextturn.constant.ValkeyKeyConstant;
import com.nextturn.dto.tenant.CreateTenantRequest;
import com.nextturn.dto.tenant.RejectTenantRequest;
import com.nextturn.dto.tenant.TenantQuotaDto;
import com.nextturn.dto.tenant.TenantQuotaResponse;
import com.nextturn.dto.tenant.TenantRegistrationRequest;
import com.nextturn.dto.tenant.TenantResponse;
import com.nextturn.dto.tenant.UpdateTenantRequest;
import com.nextturn.entity.CentralTenant;
import com.nextturn.entity.CentralTenantQuota;
import com.nextturn.enums.StaffRole;
import com.nextturn.enums.StaffStatus;
import com.nextturn.enums.TenantStatus;
import com.nextturn.exception.tenant.TenantErrorCode;
import com.nextturn.exception.tenant.TenantException;
import com.nextturn.infrastructure.security.keycloak.KeycloakProperties;
import com.nextturn.infrastructure.security.keycloak.KeycloakTokenService;
import com.nextturn.repository.auth.CentralTenantRepository;
import com.nextturn.repository.auth.TenantStaffRepository;
import com.nextturn.repository.tenant.CentralTenantQuotaRepository;
import com.nextturn.service.notification.EmailService;
import com.nextturn.dto.tenant.TenantMonitoringResponse;
import com.nextturn.dto.tenant.UpdateTenantStatusRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TenantServiceTest {

    @Mock
    private CentralTenantRepository centralTenantRepository;

    @Mock
    private CentralTenantQuotaRepository quotaRepository;

    @Mock
    private TenantStaffRepository staffRepository;

    @Mock
    private KeycloakTokenService keycloakTokenService;

    @Mock
    private KeycloakProperties keycloakProperties;

    @Mock
    private EmailService emailService;

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Mock
    private DataSource dataSource;

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @InjectMocks
    private TenantService tenantService;

    private CreateTenantRequest validCreateRequest;
    private CentralTenant activeTenant;
    private CentralTenantQuota activeQuota;

    @BeforeEach
    void setUp() {
        validCreateRequest = CreateTenantRequest.builder()
                .tenantName("Bệnh viện Đa khoa Tâm Đức")
                .tenantCode("bv-tam-duc")
                .adminEmail("admin@tamduc.vn")
                .adminFullName("Nguyễn Văn Quản Trị")
                .phoneNumber("0987654321")
                .initialPassword("Admin123!@#")
                .quotas(TenantQuotaDto.builder()
                        .maxStaffs(10)
                        .dailyTicketQuota(300)
                        .maxServices(25)
                        .maxAppointments(600)
                        .build())
                .build();

        activeTenant = CentralTenant.builder()
                .id("tenant-uuid-001")
                .tenantCode("bv-tam-duc")
                .tenantName("Bệnh viện Đa khoa Tâm Đức")
                .adminEmail("admin@tamduc.vn")
                .phoneNumber("0987654321")
                .status(TenantStatus.ACTIVE)
                .isDeleted(false)
                .organisationId("org-tam-duc")
                .widgetApiKey("nt_pub_123456")
                .build();

        activeQuota = CentralTenantQuota.builder()
                .id("quota-uuid-001")
                .tenantId("tenant-uuid-001")
                .maxStaffs(10)
                .dailyTicketQuota(300)
                .maxServices(25)
                .maxAppointments(600)
                .build();
    }

    // =========================================================================
    // REQ-TNT-01: Khởi tạo và cấp phát tổ chức mới
    // =========================================================================

    @Test
    @DisplayName("REQ-TNT-01: Successfully provision new tenant and explicitly create Keycloak Organization")
    void createTenant_success() {
        when(centralTenantRepository.existsByTenantCodeIgnoreCaseAndIsDeletedFalse("bv-tam-duc")).thenReturn(false);
        when(centralTenantRepository.findByAdminEmailIgnoreCaseAndIsDeletedFalse("admin@tamduc.vn")).thenReturn(Optional.empty());
        when(keycloakTokenService.organizationExists("bv-tam-duc")).thenReturn(false);
        when(keycloakTokenService.userExistsByEmail("admin@tamduc.vn")).thenReturn(false);

        when(centralTenantRepository.save(any(CentralTenant.class))).thenAnswer(inv -> inv.getArgument(0));
        when(quotaRepository.save(any(CentralTenantQuota.class))).thenAnswer(inv -> inv.getArgument(0));

        when(keycloakTokenService.createOrganization("bv-tam-duc", "Bệnh viện Đa khoa Tâm Đức"))
                .thenReturn("org-tam-duc");
        when(keycloakTokenService.createStaffUser(eq("bv-tam-duc"), eq("admin@tamduc.vn"), anyString(), anyString(), eq(StaffRole.ADMIN)))
                .thenReturn("kc-user-admin-001");

        TenantResponse response = tenantService.createTenant(validCreateRequest);

        assertThat(response).isNotNull();
        assertThat(response.getTenantCode()).isEqualTo("bv-tam-duc");
        assertThat(response.getTenantName()).isEqualTo("Bệnh viện Đa khoa Tâm Đức");
        assertThat(response.getAdminEmail()).isEqualTo("admin@tamduc.vn");
        assertThat(response.getStatus()).isEqualTo(TenantStatus.ACTIVE);
        assertThat(response.getOrganisationId()).isEqualTo("org-tam-duc");
        assertThat(response.getQuotas()).isNotNull();
        assertThat(response.getQuotas().getMaxStaffs()).isEqualTo(10);
        assertThat(response.getQuotas().getDailyTicketQuota()).isEqualTo(300);

        verify(keycloakTokenService).createOrganization("bv-tam-duc", "Bệnh viện Đa khoa Tâm Đức");
        verify(keycloakTokenService).addMemberToOrganization("org-tam-duc", "kc-user-admin-001");
        verify(emailService).sendTenantAdminActivationEmail(eq("admin@tamduc.vn"), eq("Bệnh viện Đa khoa Tâm Đức"), anyString());
    }

    @Test
    @DisplayName("REQ-TNT-01: Reject creation if tenant_code already exists in database (TENANT_010)")
    void createTenant_duplicateTenantCode_throwsConflict() {
        when(centralTenantRepository.existsByTenantCodeIgnoreCaseAndIsDeletedFalse("bv-tam-duc")).thenReturn(true);

        assertThatThrownBy(() -> tenantService.createTenant(validCreateRequest))
                .isInstanceOf(TenantException.class)
                .hasMessageContaining("Mã định danh tổ chức hoặc email quản trị này đã được đăng ký")
                .extracting("code")
                .isEqualTo(TenantErrorCode.TENANT_OR_EMAIL_ALREADY_EXISTS.code());

        verify(centralTenantRepository, never()).save(any());
        verify(keycloakTokenService, never()).createOrganization(any(), any());
    }

    @Test
    @DisplayName("REQ-TNT-01: Reject creation if organization already exists in Keycloak (TENANT_010)")
    void createTenant_duplicateOrganizationInKeycloak_throwsConflict() {
        when(centralTenantRepository.existsByTenantCodeIgnoreCaseAndIsDeletedFalse("bv-tam-duc")).thenReturn(false);
        when(centralTenantRepository.findByAdminEmailIgnoreCaseAndIsDeletedFalse("admin@tamduc.vn")).thenReturn(Optional.empty());
        when(keycloakTokenService.organizationExists("bv-tam-duc")).thenReturn(true);

        assertThatThrownBy(() -> tenantService.createTenant(validCreateRequest))
                .isInstanceOf(TenantException.class)
                .hasMessageContaining("Mã định danh tổ chức hoặc email quản trị này đã được đăng ký")
                .extracting("code")
                .isEqualTo(TenantErrorCode.TENANT_OR_EMAIL_ALREADY_EXISTS.code());

        verify(centralTenantRepository, never()).save(any());
        verify(keycloakTokenService, never()).createOrganization(any(), any());
    }

    @Test
    @DisplayName("REQ-TNT-01: Reject creation if admin_email already registered in DB (TENANT_010)")
    void createTenant_duplicateAdminEmailInDb_throwsConflict() {
        when(centralTenantRepository.existsByTenantCodeIgnoreCaseAndIsDeletedFalse("bv-tam-duc")).thenReturn(false);
        when(centralTenantRepository.findByAdminEmailIgnoreCaseAndIsDeletedFalse("admin@tamduc.vn"))
                .thenReturn(Optional.of(activeTenant));

        assertThatThrownBy(() -> tenantService.createTenant(validCreateRequest))
                .isInstanceOf(TenantException.class)
                .hasMessageContaining("Mã định danh tổ chức hoặc email quản trị này đã được đăng ký")
                .extracting("code")
                .isEqualTo(TenantErrorCode.TENANT_OR_EMAIL_ALREADY_EXISTS.code());

        verify(centralTenantRepository, never()).save(any());
    }

    @Test
    @DisplayName("REQ-TNT-01: Reject creation if admin_email contains '+' character (TENANT_015)")
    void createTenant_emailWithPlus_throwsException() {
        validCreateRequest.setAdminEmail("admin+alias@tamduc.vn");

        assertThatThrownBy(() -> tenantService.createTenant(validCreateRequest))
                .isInstanceOf(TenantException.class)
                .hasMessageContaining("không được chứa ký tự '+'")
                .extracting("code")
                .isEqualTo(TenantErrorCode.INVALID_ADMIN_EMAIL_FORMAT.code());

        verify(centralTenantRepository, never()).save(any());
    }

    @Test
    @DisplayName("REQ-TNT-01: Reject creation if admin_email has consecutive dots (TENANT_015)")
    void createTenant_emailWithConsecutiveDots_throwsException() {
        validCreateRequest.setAdminEmail("admin..test@tamduc.vn");

        assertThatThrownBy(() -> tenantService.createTenant(validCreateRequest))
                .isInstanceOf(TenantException.class)
                .hasMessageContaining("không chứa dấu chấm liên tiếp '..'")
                .extracting("code")
                .isEqualTo(TenantErrorCode.INVALID_ADMIN_EMAIL_FORMAT.code());

        verify(centralTenantRepository, never()).save(any());
    }

    @Test
    @DisplayName("REQ-TNT-01: Reject creation if admin_email has leading dot (TENANT_015)")
    void createTenant_emailWithLeadingDot_throwsException() {
        validCreateRequest.setAdminEmail(".admin@tamduc.vn");

        assertThatThrownBy(() -> tenantService.createTenant(validCreateRequest))
                .isInstanceOf(TenantException.class)
                .hasMessageContaining("không bắt đầu/kết thúc bằng dấu '.'")
                .extracting("code")
                .isEqualTo(TenantErrorCode.INVALID_ADMIN_EMAIL_FORMAT.code());

        verify(centralTenantRepository, never()).save(any());
    }

    @Test
    @DisplayName("REQ-TNT-01: Reject creation if admin_email lacks valid top-level domain dot (TENANT_015)")
    void createTenant_emailWithoutDomainDot_throwsException() {
        validCreateRequest.setAdminEmail("admin@tamduc");

        assertThatThrownBy(() -> tenantService.createTenant(validCreateRequest))
                .isInstanceOf(TenantException.class)
                .hasMessageContaining("phải có tên miền hợp lệ")
                .extracting("code")
                .isEqualTo(TenantErrorCode.INVALID_ADMIN_EMAIL_FORMAT.code());

        verify(centralTenantRepository, never()).save(any());
    }

    @Test
    @DisplayName("REQ-TNT-01: Reject creation if admin_email already exists in Keycloak (TENANT_010)")
    void createTenant_duplicateAdminEmailInKeycloak_throwsConflict() {
        when(centralTenantRepository.existsByTenantCodeIgnoreCaseAndIsDeletedFalse("bv-tam-duc")).thenReturn(false);
        when(centralTenantRepository.findByAdminEmailIgnoreCaseAndIsDeletedFalse("admin@tamduc.vn")).thenReturn(Optional.empty());
        when(keycloakTokenService.organizationExists("bv-tam-duc")).thenReturn(false);
        when(keycloakTokenService.userExistsByEmail("admin@tamduc.vn")).thenReturn(true);

        assertThatThrownBy(() -> tenantService.createTenant(validCreateRequest))
                .isInstanceOf(TenantException.class)
                .hasMessageContaining("Mã định danh tổ chức hoặc email quản trị này đã được đăng ký")
                .extracting("code")
                .isEqualTo(TenantErrorCode.TENANT_OR_EMAIL_ALREADY_EXISTS.code());

        verify(centralTenantRepository, never()).save(any());
    }

    @Test
    @DisplayName("REQ-TNT-01: Keycloak synchronization failure triggers compensating rollback and returns 500")
    void createTenant_keycloakSyncFails_rollbacksDbAndThrows500() {
        when(centralTenantRepository.existsByTenantCodeIgnoreCaseAndIsDeletedFalse("bv-tam-duc")).thenReturn(false);
        when(centralTenantRepository.findByAdminEmailIgnoreCaseAndIsDeletedFalse("admin@tamduc.vn")).thenReturn(Optional.empty());
        when(keycloakTokenService.organizationExists("bv-tam-duc")).thenReturn(false);
        when(keycloakTokenService.userExistsByEmail("admin@tamduc.vn")).thenReturn(false);

        when(centralTenantRepository.save(any(CentralTenant.class))).thenAnswer(inv -> inv.getArgument(0));
        when(quotaRepository.save(any(CentralTenantQuota.class))).thenAnswer(inv -> inv.getArgument(0));

        when(keycloakTokenService.createOrganization("bv-tam-duc", "Bệnh viện Đa khoa Tâm Đức"))
                .thenReturn("org-tam-duc");
        when(keycloakTokenService.createStaffUser(eq("bv-tam-duc"), eq("admin@tamduc.vn"), anyString(), anyString(), eq(StaffRole.ADMIN)))
                .thenThrow(new RuntimeException("Keycloak connection timeout"));

        assertThatThrownBy(() -> tenantService.createTenant(validCreateRequest))
                .isInstanceOf(TenantException.class)
                .hasMessageContaining("đồng bộ tài khoản sang Keycloak thất bại")
                .extracting("code")
                .isEqualTo(TenantErrorCode.AUTH_SERVICE_SYNC_FAILED.code());

        // Verifies database and Keycloak compensation cleanup
        verify(quotaRepository).deleteByTenantId(anyString());
        verify(centralTenantRepository).deleteById(anyString());
        verify(keycloakTokenService).deleteOrganizationInKeycloak("org-tam-duc");
    }

    // =========================================================================
    // REQ-TNT-02: Cập nhật thông tin và cấu hình hạn ngạch vận hành
    // =========================================================================

    @Test
    @DisplayName("REQ-TNT-02: Successfully update administrative info and operational quotas")
    void updateTenant_success() {
        when(centralTenantRepository.findById("tenant-uuid-001")).thenReturn(Optional.of(activeTenant));
        when(staffRepository.countByStatusAndIsDeletedFalse(StaffStatus.ACTIVE)).thenReturn(3L);
        when(centralTenantRepository.save(any(CentralTenant.class))).thenAnswer(inv -> inv.getArgument(0));
        when(quotaRepository.findByTenantId("tenant-uuid-001")).thenReturn(Optional.of(activeQuota));
        when(quotaRepository.save(any(CentralTenantQuota.class))).thenAnswer(inv -> inv.getArgument(0));

        UpdateTenantRequest updateRequest = UpdateTenantRequest.builder()
                .tenantName("Bệnh viện Đa khoa Tâm Đức (Mới)")
                .phoneNumber("0912345678")
                .maxStaffs(15)
                .dailyTicketQuota(500)
                .maxServices(30)
                .maxAppointments(800)
                .build();

        TenantResponse response = tenantService.updateTenant("tenant-uuid-001", updateRequest);

        assertThat(response).isNotNull();
        assertThat(response.getTenantName()).isEqualTo("Bệnh viện Đa khoa Tâm Đức (Mới)");
        assertThat(response.getPhoneNumber()).isEqualTo("0912345678");
        assertThat(response.getQuotas().getMaxStaffs()).isEqualTo(15);
        assertThat(response.getQuotas().getDailyTicketQuota()).isEqualTo(500);
        assertThat(response.getQuotas().getMaxServices()).isEqualTo(30);
        assertThat(response.getQuotas().getMaxAppointments()).isEqualTo(800);
    }

    @Test
    @DisplayName("REQ-TNT-02: Reject update if new max_staffs is less than current active staff count (TENANT_011)")
    void updateTenant_maxStaffsLessThanCurrent_throwsConflict() {
        when(centralTenantRepository.findById("tenant-uuid-001")).thenReturn(Optional.of(activeTenant));
        // Current active staff is 8
        when(staffRepository.countByStatusAndIsDeletedFalse(StaffStatus.ACTIVE)).thenReturn(8L);

        // Superadmin tries to reduce quota to 5 (< 8)
        UpdateTenantRequest updateRequest = UpdateTenantRequest.builder()
                .maxStaffs(5)
                .build();

        assertThatThrownBy(() -> tenantService.updateTenant("tenant-uuid-001", updateRequest))
                .isInstanceOf(TenantException.class)
                .hasMessageContaining("Hạn ngạch nhân sự mới không được nhỏ hơn số lượng nhân viên hiện có")
                .extracting("code")
                .isEqualTo(TenantErrorCode.STAFF_QUOTA_BELOW_CURRENT.code());

        verify(centralTenantRepository, never()).save(any());
        verify(quotaRepository, never()).save(any());
    }

    @Test
    @DisplayName("REQ-TNT-02: Reject update when tenant is not found (TENANT_001)")
    void updateTenant_notFound_throwsException() {
        when(centralTenantRepository.findById("non-existent-id")).thenReturn(Optional.empty());
        when(centralTenantRepository.findByTenantCodeIgnoreCaseAndIsDeletedFalse("non-existent-id")).thenReturn(Optional.empty());

        UpdateTenantRequest updateRequest = UpdateTenantRequest.builder()
                .maxStaffs(10)
                .build();

        assertThatThrownBy(() -> tenantService.updateTenant("non-existent-id", updateRequest))
                .isInstanceOf(TenantException.class)
                .extracting("code")
                .isEqualTo(TenantErrorCode.TENANT_NOT_FOUND.code());
    }

    @Test
    @DisplayName("REQ-TNT-02: Reject update if tenant is not in ACTIVE status")
    void updateTenant_tenantNotActive_throwsNotActive() {
        CentralTenant blockedTenant = CentralTenant.builder()
                .id("tenant-uuid-002")
                .tenantCode("blocked-clinic")
                .status(TenantStatus.SUSPENDED)
                .isDeleted(false)
                .build();
        when(centralTenantRepository.findById("tenant-uuid-002")).thenReturn(Optional.of(blockedTenant));

        UpdateTenantRequest updateRequest = UpdateTenantRequest.builder()
                .maxStaffs(10)
                .build();

        assertThatThrownBy(() -> tenantService.updateTenant("tenant-uuid-002", updateRequest))
                .isInstanceOf(TenantException.class)
                .hasMessageContaining("không ở trạng thái hoạt động")
                .extracting("code")
                .isEqualTo(TenantErrorCode.TENANT_NOT_ACTIVE.code());
    }

    @Test
    @DisplayName("REQ-TNT-02: Reject invalid quota numerical values")
    void updateTenant_invalidQuotaValues_throwsInvalidQuota() {
        when(centralTenantRepository.findById("tenant-uuid-001")).thenReturn(Optional.of(activeTenant));

        // Invalid: dailyTicketQuota = -5 (neither >= 1 nor -1)
        UpdateTenantRequest updateRequest = UpdateTenantRequest.builder()
                .dailyTicketQuota(-5)
                .build();

        assertThatThrownBy(() -> tenantService.updateTenant("tenant-uuid-001", updateRequest))
                .isInstanceOf(TenantException.class)
                .extracting("code")
                .isEqualTo(TenantErrorCode.INVALID_QUOTA_VALUE.code());
    }

    @Test
    @DisplayName("REQ-TNT-02: Query tenant details and operational quotas")
    void getTenant_and_getTenantQuota_success() {
        when(centralTenantRepository.findById("tenant-uuid-001")).thenReturn(Optional.of(activeTenant));
        when(quotaRepository.findByTenantId("tenant-uuid-001")).thenReturn(Optional.of(activeQuota));
        when(staffRepository.countByStatusAndIsDeletedFalse(StaffStatus.ACTIVE)).thenReturn(3L);

        TenantResponse tenantResponse = tenantService.getTenant("tenant-uuid-001");
        assertThat(tenantResponse).isNotNull();
        assertThat(tenantResponse.getTenantCode()).isEqualTo("bv-tam-duc");
        assertThat(tenantResponse.getQuotas().getCurrentActiveStaff()).isEqualTo(3L);

        TenantQuotaResponse quotaResponse = tenantService.getTenantQuota("tenant-uuid-001");
        assertThat(quotaResponse).isNotNull();
        assertThat(quotaResponse.getMaxStaffs()).isEqualTo(10);
        assertThat(quotaResponse.getCurrentActiveStaff()).isEqualTo(3L);
    }

    @Test
    @DisplayName("Cache: Should return cached TenantResponse when available in Valkey without DB query")
    void getTenant_cacheHit_returnsCachedWithoutQueryingDatabase() throws Exception {
        TenantQuotaResponse quotaResponse = TenantQuotaResponse.builder()
                .tenantId("tenant-uuid-001")
                .maxStaffs(20)
                .currentActiveStaff(5L)
                .build();
        TenantResponse cachedResponse = TenantResponse.builder()
                .id("tenant-uuid-001")
                .tenantCode("bv-tam-duc")
                .tenantName("Bệnh viện Đa khoa Tâm Đức")
                .status(TenantStatus.ACTIVE)
                .quotas(quotaResponse)
                .build();

        String cachedJson = objectMapper.writeValueAsString(cachedResponse);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("nextturn:tenant:config:tenant-uuid-001"))
                .thenReturn(cachedJson);

        TenantResponse response = tenantService.getTenant("tenant-uuid-001");

        assertThat(response).isNotNull();
        assertThat(response.getId()).isEqualTo("tenant-uuid-001");
        assertThat(response.getTenantCode()).isEqualTo("bv-tam-duc");
        assertThat(response.getQuotas().getMaxStaffs()).isEqualTo(20);

        verify(centralTenantRepository, never()).findById(anyString());
        verify(quotaRepository, never()).findByTenantId(anyString());
    }

    // =========================================================================
    // REQ-TNT-03: Quản lý vòng đời và khóa/kích hoạt tổ chức
    // =========================================================================

    @Test
    @DisplayName("REQ-TNT-03: Suspend active tenant, revoke Keycloak sessions and staff login rights")
    void suspendTenant_success() {
        when(centralTenantRepository.findById("tenant-uuid-001")).thenReturn(Optional.of(activeTenant));
        when(centralTenantRepository.save(any(CentralTenant.class))).thenAnswer(inv -> inv.getArgument(0));
        when(quotaRepository.findByTenantId("tenant-uuid-001")).thenReturn(Optional.of(activeQuota));
        when(staffRepository.countByStatusAndIsDeletedFalse(StaffStatus.ACTIVE)).thenReturn(4L);

        TenantResponse response = tenantService.suspendTenant("tenant-uuid-001");

        assertThat(response).isNotNull();
        assertThat(response.getStatus()).isEqualTo(TenantStatus.SUSPENDED);
        verify(keycloakTokenService).setOrganizationAndMembersActive("org-tam-duc", false);
    }

    @Test
    @DisplayName("REQ-TNT-03: Reject suspension if tenant is already SUSPENDED (TENANT_017)")
    void suspendTenant_alreadySuspended_throwsException() {
        activeTenant.setStatus(TenantStatus.SUSPENDED);
        when(centralTenantRepository.findById("tenant-uuid-001")).thenReturn(Optional.of(activeTenant));

        assertThatThrownBy(() -> tenantService.suspendTenant("tenant-uuid-001"))
                .isInstanceOf(TenantException.class)
                .hasMessageContaining("đã ở trạng thái tạm ngừng (SUSPENDED)")
                .extracting("code")
                .isEqualTo(TenantErrorCode.TENANT_ALREADY_SUSPENDED.code());

        verify(centralTenantRepository, never()).save(any());
        verify(keycloakTokenService, never()).setOrganizationAndMembersActive(anyString(), any(Boolean.class));
    }

    @Test
    @DisplayName("REQ-TNT-03: Reactivate suspended tenant, restore Keycloak login rights")
    void activateTenant_success() {
        activeTenant.setStatus(TenantStatus.SUSPENDED);
        when(centralTenantRepository.findById("tenant-uuid-001")).thenReturn(Optional.of(activeTenant));
        when(centralTenantRepository.save(any(CentralTenant.class))).thenAnswer(inv -> inv.getArgument(0));
        when(quotaRepository.findByTenantId("tenant-uuid-001")).thenReturn(Optional.of(activeQuota));
        when(staffRepository.countByStatusAndIsDeletedFalse(StaffStatus.ACTIVE)).thenReturn(4L);

        TenantResponse response = tenantService.activateTenant("tenant-uuid-001");

        assertThat(response).isNotNull();
        assertThat(response.getStatus()).isEqualTo(TenantStatus.ACTIVE);
        verify(keycloakTokenService).setOrganizationAndMembersActive("org-tam-duc", true);
    }

    @Test
    @DisplayName("REQ-TNT-03: Reject activation if tenant is already ACTIVE (TENANT_018)")
    void activateTenant_alreadyActive_throwsException() {
        activeTenant.setStatus(TenantStatus.ACTIVE);
        when(centralTenantRepository.findById("tenant-uuid-001")).thenReturn(Optional.of(activeTenant));

        assertThatThrownBy(() -> tenantService.activateTenant("tenant-uuid-001"))
                .isInstanceOf(TenantException.class)
                .hasMessageContaining("đã ở trạng thái hoạt động (ACTIVE)")
                .extracting("code")
                .isEqualTo(TenantErrorCode.TENANT_ALREADY_ACTIVE.code());

        verify(centralTenantRepository, never()).save(any());
        verify(keycloakTokenService, never()).setOrganizationAndMembersActive(anyString(), any(Boolean.class));
    }

    @Test
    @DisplayName("REQ-TNT-03: updateTenantStatus dispatches to suspend or activate")
    void updateTenantStatus_dispatchesCorrectly() {
        when(centralTenantRepository.findById("tenant-uuid-001")).thenReturn(Optional.of(activeTenant));
        when(centralTenantRepository.save(any(CentralTenant.class))).thenAnswer(inv -> inv.getArgument(0));
        when(quotaRepository.findByTenantId("tenant-uuid-001")).thenReturn(Optional.of(activeQuota));

        TenantResponse response = tenantService.updateTenantStatus(
                "tenant-uuid-001",
                new UpdateTenantStatusRequest(TenantStatus.SUSPENDED)
        );

        assertThat(response.getStatus()).isEqualTo(TenantStatus.SUSPENDED);
        verify(keycloakTokenService).setOrganizationAndMembersActive("org-tam-duc", false);
    }

    @Test
    @DisplayName("REQ-TNT-03: Soft delete uninitialized tenant without operational data")
    void deleteTenant_noHistoricalData_softDeletesSuccessfully() {
        when(centralTenantRepository.findById("tenant-uuid-001")).thenReturn(Optional.of(activeTenant));
        when(centralTenantRepository.save(any(CentralTenant.class))).thenAnswer(inv -> inv.getArgument(0));

        tenantService.deleteTenant("tenant-uuid-001");

        assertThat(activeTenant.getIsDeleted()).isTrue();
        assertThat(activeTenant.getStatus()).isEqualTo(TenantStatus.SUSPENDED);
        verify(centralTenantRepository).save(activeTenant);
        verify(centralTenantRepository, never()).delete(any());
        verify(quotaRepository, never()).deleteByTenantId(anyString());
        verify(keycloakTokenService).setOrganizationAndMembersActive("org-tam-duc", false);
    }

    @Test
    @DisplayName("REQ-TNT-03: Rollback DB status and isDeleted when Keycloak fails during deleteTenant")
    void deleteTenant_whenKeycloakFails_rollsBackDatabaseStatus() {
        when(centralTenantRepository.findById("tenant-uuid-001")).thenReturn(Optional.of(activeTenant));
        when(centralTenantRepository.save(any(CentralTenant.class))).thenAnswer(inv -> inv.getArgument(0));
        doThrow(new RuntimeException("Keycloak connection timeout"))
                .when(keycloakTokenService).setOrganizationAndMembersActive("org-tam-duc", false);

        assertThatThrownBy(() -> tenantService.deleteTenant("tenant-uuid-001"))
                .isInstanceOf(TenantException.class)
                .extracting("code")
                .isEqualTo(TenantErrorCode.AUTH_SERVICE_SYNC_FAILED.code());

        assertThat(activeTenant.getIsDeleted()).isFalse();
        assertThat(activeTenant.getStatus()).isEqualTo(TenantStatus.ACTIVE);
    }

    // =========================================================================
    // REQ-TNT-04: Tra cứu, phân trang và giám sát toàn cục
    // =========================================================================

    @Test
    @DisplayName("REQ-TNT-04: Search tenants with keyword, status, and compute staff/ticket metrics")
    void searchTenants_success() {
        Page<CentralTenant> tenantPage = new PageImpl<>(List.of(activeTenant));
        when(centralTenantRepository.searchTenants(eq("tam-duc"), eq(TenantStatus.ACTIVE), any(Pageable.class)))
                .thenReturn(tenantPage);
        when(quotaRepository.findByTenantId("tenant-uuid-001")).thenReturn(Optional.of(activeQuota));
        when(staffRepository.countByStatusAndIsDeletedFalse(StaffStatus.ACTIVE)).thenReturn(5L);

        Page<TenantMonitoringResponse> result = tenantService.searchTenants(
                "tam-duc", TenantStatus.ACTIVE, 0, 10, "createdAt", "DESC"
        );

        assertThat(result).isNotNull();
        assertThat(result.getTotalElements()).isEqualTo(1L);
        assertThat(result.getContent()).hasSize(1);

        TenantMonitoringResponse item = result.getContent().get(0);
        assertThat(item.getTenantCode()).isEqualTo("bv-tam-duc");
        assertThat(item.getActiveStaffCount()).isEqualTo(5L);
        assertThat(item.getTodayTicketsCount()).isEqualTo(0L);
        assertThat(item.getQuotas()).isNotNull();
        assertThat(item.getQuotas().getMaxStaffs()).isEqualTo(10);
    }

    @Test
    @DisplayName("REQ-TNT-03: Rollback DB status when Keycloak fails during suspend")
    void suspendTenant_whenKeycloakFails_rollsBackDatabaseStatus() {
        when(centralTenantRepository.findById("tenant-uuid-001")).thenReturn(Optional.of(activeTenant));
        when(centralTenantRepository.save(any(CentralTenant.class))).thenAnswer(inv -> inv.getArgument(0));
        doThrow(new RuntimeException("Keycloak connection timeout"))
                .when(keycloakTokenService).setOrganizationAndMembersActive("org-tam-duc", false);

        assertThatThrownBy(() -> tenantService.suspendTenant("tenant-uuid-001"))
                .isInstanceOf(TenantException.class)
                .extracting("code")
                .isEqualTo(TenantErrorCode.AUTH_SERVICE_SYNC_FAILED.code());

        assertThat(activeTenant.getStatus()).isEqualTo(TenantStatus.ACTIVE);
    }

    @Test
    @DisplayName("REQ-TNT-03: Rollback DB status when Keycloak fails during activate")
    void activateTenant_whenKeycloakFails_rollsBackDatabaseStatus() {
        activeTenant.setStatus(TenantStatus.SUSPENDED);
        when(centralTenantRepository.findById("tenant-uuid-001")).thenReturn(Optional.of(activeTenant));
        when(centralTenantRepository.save(any(CentralTenant.class))).thenAnswer(inv -> inv.getArgument(0));
        doThrow(new RuntimeException("Keycloak connection timeout"))
                .when(keycloakTokenService).setOrganizationAndMembersActive("org-tam-duc", true);

        assertThatThrownBy(() -> tenantService.activateTenant("tenant-uuid-001"))
                .isInstanceOf(TenantException.class)
                .extracting("code")
                .isEqualTo(TenantErrorCode.AUTH_SERVICE_SYNC_FAILED.code());

        assertThat(activeTenant.getStatus()).isEqualTo(TenantStatus.SUSPENDED);
    }

    @Test
    @DisplayName("REQ-TNT-04: countActiveStaff and countTodayTickets use Valkey cache when available")
    void countingMetrics_useCacheWhenAvailable() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(ValkeyKeyConstant.tenantActiveStaffKey("bv-tam-duc"))).thenReturn("7");
        when(valueOperations.get(ValkeyKeyConstant.tenantTodayTicketsKey("bv-tam-duc"))).thenReturn("150");

        Page<CentralTenant> tenantPage = new PageImpl<>(List.of(activeTenant));
        when(centralTenantRepository.searchTenants(eq("tam-duc"), eq(TenantStatus.ACTIVE), any(Pageable.class)))
                .thenReturn(tenantPage);
        when(quotaRepository.findByTenantId("tenant-uuid-001")).thenReturn(Optional.of(activeQuota));

        Page<TenantMonitoringResponse> result = tenantService.searchTenants(
                "tam-duc", TenantStatus.ACTIVE, 0, 10, "createdAt", "DESC"
        );

        assertThat(result.getContent().get(0).getActiveStaffCount()).isEqualTo(7L);
        assertThat(result.getContent().get(0).getTodayTicketsCount()).isEqualTo(150L);
        verify(staffRepository, never()).countByStatusAndIsDeletedFalse(any());
    }

    // =========================================================================
    // REQ-TNT-05: Đăng ký tổ chức mới từ phía người dùng
    // =========================================================================

    @Test
    @DisplayName("REQ-TNT-05: Successfully register tenant with PENDING_APPROVAL status and disabled Keycloak org/user")
    void registerTenant_success() {
        TenantRegistrationRequest regRequest = TenantRegistrationRequest.builder()
                .tenantName("Phòng khám Đa khoa Sài Gòn")
                .tenantCode("pk-sai-gon")
                .adminEmail("admin@saigonclinic.vn")
                .adminFullName("Trần Thị Mai")
                .phoneNumber("0912345678")
                .initialPassword("ClinicPass123!@#")
                .build();

        when(centralTenantRepository.existsByTenantCodeIgnoreCaseAndIsDeletedFalse("pk-sai-gon")).thenReturn(false);
        when(centralTenantRepository.findByAdminEmailIgnoreCaseAndIsDeletedFalse("admin@saigonclinic.vn")).thenReturn(Optional.empty());
        when(keycloakTokenService.organizationExists("pk-sai-gon")).thenReturn(false);
        when(keycloakTokenService.userExistsByEmail("admin@saigonclinic.vn")).thenReturn(false);

        when(centralTenantRepository.save(any(CentralTenant.class))).thenAnswer(inv -> inv.getArgument(0));
        when(quotaRepository.save(any(CentralTenantQuota.class))).thenAnswer(inv -> inv.getArgument(0));

        when(keycloakTokenService.createOrganization(eq("pk-sai-gon"), eq("Phòng khám Đa khoa Sài Gòn"), eq(false)))
                .thenReturn("org-pk-sai-gon");
        when(keycloakTokenService.createStaffUser(eq("pk-sai-gon"), eq("admin@saigonclinic.vn"), anyString(), anyString(), eq(StaffRole.ADMIN), eq(false)))
                .thenReturn("kc-user-staging-001");

        TenantResponse response = tenantService.registerTenant(regRequest);

        assertThat(response).isNotNull();
        assertThat(response.getTenantCode()).isEqualTo("pk-sai-gon");
        assertThat(response.getStatus()).isEqualTo(TenantStatus.PENDING_APPROVAL);
        assertThat(response.getOrganisationId()).isEqualTo("org-pk-sai-gon");

        verify(keycloakTokenService).addMemberToOrganization("org-pk-sai-gon", "kc-user-staging-001");
        verify(emailService).sendTenantRegistrationReceivedEmail(eq("admin@saigonclinic.vn"), eq("Phòng khám Đa khoa Sài Gòn"), eq("pk-sai-gon"));
        verify(emailService).sendTenantRegistrationInternalNotification(anyString(), eq("Phòng khám Đa khoa Sài Gòn"), eq("pk-sai-gon"), eq("admin@saigonclinic.vn"));
    }

    @Test
    @DisplayName("REQ-TNT-05: Rollback database and clean Keycloak when Keycloak staging fails")
    void registerTenant_whenKeycloakFails_rollsBackDatabase() {
        TenantRegistrationRequest regRequest = TenantRegistrationRequest.builder()
                .tenantName("Phòng khám Đa khoa Sài Gòn")
                .tenantCode("pk-sai-gon")
                .adminEmail("admin@saigonclinic.vn")
                .phoneNumber("0912345678")
                .build();

        when(centralTenantRepository.existsByTenantCodeIgnoreCaseAndIsDeletedFalse("pk-sai-gon")).thenReturn(false);
        when(centralTenantRepository.findByAdminEmailIgnoreCaseAndIsDeletedFalse("admin@saigonclinic.vn")).thenReturn(Optional.empty());
        when(centralTenantRepository.save(any(CentralTenant.class))).thenAnswer(inv -> inv.getArgument(0));
        when(quotaRepository.save(any(CentralTenantQuota.class))).thenAnswer(inv -> inv.getArgument(0));

        when(keycloakTokenService.createOrganization(eq("pk-sai-gon"), anyString(), eq(false)))
                .thenThrow(new RuntimeException("Keycloak connection timeout"));

        assertThatThrownBy(() -> tenantService.registerTenant(regRequest))
                .isInstanceOf(TenantException.class)
                .extracting("code")
                .isEqualTo(TenantErrorCode.AUTH_SERVICE_SYNC_FAILED.code());

        verify(centralTenantRepository).deleteById(anyString());
        verify(quotaRepository).deleteByTenantId(anyString());
    }

    // =========================================================================
    // REQ-TNT-06: Phê duyệt hoặc từ chối yêu cầu đăng ký tổ chức
    // =========================================================================

    @Test
    @DisplayName("REQ-TNT-06: Superadmin approves pending tenant registration, activating Keycloak org & sending approval email")
    void approveTenantRegistration_success() {
        CentralTenant pendingTenant = CentralTenant.builder()
                .id("tenant-pending-001")
                .tenantCode("pk-sai-gon")
                .tenantName("Phòng khám Đa khoa Sài Gòn")
                .adminEmail("admin@saigonclinic.vn")
                .status(TenantStatus.PENDING_APPROVAL)
                .organisationId("org-pk-sai-gon")
                .isDeleted(false)
                .build();

        when(centralTenantRepository.findById("tenant-pending-001")).thenReturn(Optional.of(pendingTenant));
        when(centralTenantRepository.save(any(CentralTenant.class))).thenAnswer(inv -> inv.getArgument(0));
        when(quotaRepository.findByTenantId("tenant-pending-001")).thenReturn(Optional.of(activeQuota));

        TenantResponse response = tenantService.approveTenantRegistration("tenant-pending-001");

        assertThat(response).isNotNull();
        assertThat(response.getStatus()).isEqualTo(TenantStatus.ACTIVE);
        verify(keycloakTokenService).setOrganizationAndMembersActive("org-pk-sai-gon", true);
        verify(emailService).sendTenantRegistrationApprovedEmail(
                eq("admin@saigonclinic.vn"),
                eq("Phòng khám Đa khoa Sài Gòn"),
                eq("pk-sai-gon"),
                anyString()
        );
    }

    @Test
    @DisplayName("REQ-TNT-06: Reject approval if tenant is not in PENDING_APPROVAL status (TENANT_019)")
    void approveTenantRegistration_notPending_throwsException() {
        when(centralTenantRepository.findById("tenant-uuid-001")).thenReturn(Optional.of(activeTenant));

        assertThatThrownBy(() -> tenantService.approveTenantRegistration("tenant-uuid-001"))
                .isInstanceOf(TenantException.class)
                .extracting("code")
                .isEqualTo(TenantErrorCode.TENANT_NOT_PENDING_APPROVAL.code());

        verify(keycloakTokenService, never()).setOrganizationAndMembersActive(anyString(), any(Boolean.class));
    }

    @Test
    @DisplayName("REQ-TNT-06: Rollback DB status to PENDING_APPROVAL when Keycloak fails during approval")
    void approveTenantRegistration_whenKeycloakFails_rollsBackStatus() {
        CentralTenant pendingTenant = CentralTenant.builder()
                .id("tenant-pending-001")
                .tenantCode("pk-sai-gon")
                .tenantName("Phòng khám Đa khoa Sài Gòn")
                .adminEmail("admin@saigonclinic.vn")
                .status(TenantStatus.PENDING_APPROVAL)
                .organisationId("org-pk-sai-gon")
                .isDeleted(false)
                .build();

        when(centralTenantRepository.findById("tenant-pending-001")).thenReturn(Optional.of(pendingTenant));
        when(centralTenantRepository.save(any(CentralTenant.class))).thenAnswer(inv -> inv.getArgument(0));
        doThrow(new RuntimeException("Keycloak connection error"))
                .when(keycloakTokenService).setOrganizationAndMembersActive("org-pk-sai-gon", true);

        assertThatThrownBy(() -> tenantService.approveTenantRegistration("tenant-pending-001"))
                .isInstanceOf(TenantException.class)
                .extracting("code")
                .isEqualTo(TenantErrorCode.AUTH_SERVICE_SYNC_FAILED.code());

        assertThat(pendingTenant.getStatus()).isEqualTo(TenantStatus.PENDING_APPROVAL);
    }

    @Test
    @DisplayName("REQ-TNT-06: Superadmin rejects pending tenant registration with mandatory reason")
    void rejectTenantRegistration_success() {
        CentralTenant pendingTenant = CentralTenant.builder()
                .id("tenant-pending-001")
                .tenantCode("pk-sai-gon")
                .tenantName("Phòng khám Đa khoa Sài Gòn")
                .adminEmail("admin@saigonclinic.vn")
                .status(TenantStatus.PENDING_APPROVAL)
                .organisationId("org-pk-sai-gon")
                .isDeleted(false)
                .build();

        when(centralTenantRepository.findById("tenant-pending-001")).thenReturn(Optional.of(pendingTenant));
        when(centralTenantRepository.save(any(CentralTenant.class))).thenAnswer(inv -> inv.getArgument(0));

        RejectTenantRequest rejectRequest = new RejectTenantRequest("Giấy phép kinh doanh không khớp thông tin.");
        TenantResponse response = tenantService.rejectTenantRegistration("tenant-pending-001", rejectRequest);

        assertThat(response).isNotNull();
        assertThat(response.getStatus()).isEqualTo(TenantStatus.REJECTED);
        assertThat(response.getRejectionReason()).isEqualTo("Giấy phép kinh doanh không khớp thông tin.");

        verify(keycloakTokenService).deleteOrganizationInKeycloak("org-pk-sai-gon");
        verify(emailService).sendTenantRegistrationRejectedEmail(
                eq("admin@saigonclinic.vn"),
                eq("Phòng khám Đa khoa Sài Gòn"),
                eq("pk-sai-gon"),
                eq("Giấy phép kinh doanh không khớp thông tin.")
        );
    }

    @Test
    @DisplayName("REQ-TNT-06: Rejecting registration requires a non-blank reason (TENANT_020)")
    void rejectTenantRegistration_blankReason_throwsException() {
        RejectTenantRequest blankRequest = new RejectTenantRequest("   ");

        assertThatThrownBy(() -> tenantService.rejectTenantRegistration("tenant-pending-001", blankRequest))
                .isInstanceOf(TenantException.class)
                .extracting("code")
                .isEqualTo(TenantErrorCode.REJECTION_REASON_REQUIRED.code());

        verify(centralTenantRepository, never()).save(any());
    }
}
