package com.nextturn.service.catalog;

import com.nextturn.dto.service.CreateServiceRequest;
import com.nextturn.dto.service.ServiceResponse;
import com.nextturn.dto.service.UpdateServiceRequest;
import com.nextturn.entity.TenantService;
import com.nextturn.enums.ServiceStatus;
import com.nextturn.exception.service.ServiceErrorCode;
import com.nextturn.exception.service.ServiceException;
import com.nextturn.infrastructure.multitenancy.TenantContextHolder;
import com.nextturn.repository.service.TenantServiceRepository;
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
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TenantCatalogServiceTest {

    private static final String TENANT_CODE = "tenant-alpha";

    @Mock
    private TenantServiceRepository serviceRepository;

    @Mock
    private TenantCacheService tenantCacheService;

    @InjectMocks
    private TenantCatalogService catalogService;

    @BeforeEach
    void setUp() {
        TenantContextHolder.setTenantCode(TENANT_CODE);
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
    }

    @Test
    @DisplayName("Create service should succeed, generate SVC-0001, and refresh cache")
    void createService_shouldSucceedWithInitialCode() {
        CreateServiceRequest request = CreateServiceRequest.builder()
                .serviceName("Tư Vấn Khách Hàng")
                .standardDuration(15)
                .price(new BigDecimal("150000.00"))
                .build();

        when(serviceRepository.existsByServiceNameIgnoreCaseAndIsDeletedFalse("Tư Vấn Khách Hàng")).thenReturn(false);
        when(serviceRepository.findMaxServiceCode()).thenReturn(Optional.empty());
        when(serviceRepository.existsByServiceCodeAndIsDeletedFalse("SVC-0001")).thenReturn(false);
        when(serviceRepository.save(any(TenantService.class))).thenAnswer(invocation -> {
            TenantService entity = invocation.getArgument(0);
            entity.setCreatedAt(LocalDateTime.now());
            entity.setUpdatedAt(LocalDateTime.now());
            return entity;
        });
        when(serviceRepository.findAllByStatusAndIsDeletedFalse(ServiceStatus.ACTIVE)).thenReturn(Collections.emptyList());

        ServiceResponse response = catalogService.createService(request);

        assertThat(response).isNotNull();
        assertThat(response.getServiceCode()).isEqualTo("SVC-0001");
        assertThat(response.getServiceName()).isEqualTo("Tư Vấn Khách Hàng");
        assertThat(response.getStandardDuration()).isEqualTo(15);
        assertThat(response.getPrice()).isEqualByComparingTo("150000.00");
        assertThat(response.getStatus()).isEqualTo(ServiceStatus.ACTIVE);

        verify(tenantCacheService).evictTenantServiceCache(TENANT_CODE);
        verify(tenantCacheService).cacheTenantServices(eq(TENANT_CODE), anyList());
        verify(serviceRepository).save(any(TenantService.class));
    }

    @Test
    @DisplayName("Create service should increment code sequence to SVC-0003 when max is SVC-0002")
    void createService_shouldIncrementSequentialCode() {
        CreateServiceRequest request = CreateServiceRequest.builder()
                .serviceName("Tiếp Nhận Hồ Sơ")
                .standardDuration(20)
                .price(new BigDecimal("200000.00"))
                .build();

        when(serviceRepository.existsByServiceNameIgnoreCaseAndIsDeletedFalse("Tiếp Nhận Hồ Sơ")).thenReturn(false);
        when(serviceRepository.findMaxServiceCode()).thenReturn(Optional.of("SVC-0002"));
        when(serviceRepository.existsByServiceCodeAndIsDeletedFalse("SVC-0003")).thenReturn(false);
        when(serviceRepository.save(any(TenantService.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(serviceRepository.findAllByStatusAndIsDeletedFalse(ServiceStatus.ACTIVE)).thenReturn(Collections.emptyList());

        ServiceResponse response = catalogService.createService(request);

        assertThat(response.getServiceCode()).isEqualTo("SVC-0003");
        verify(tenantCacheService).evictTenantServiceCache(TENANT_CODE);
        verify(tenantCacheService).cacheTenantServices(eq(TENANT_CODE), anyList());
    }

    @Test
    @DisplayName("Create service should throw 409 Conflict when service name already exists")
    void createService_shouldThrowConflict_whenNameAlreadyExists() {
        CreateServiceRequest request = CreateServiceRequest.builder()
                .serviceName("Tư Vấn Khách Hàng")
                .standardDuration(15)
                .price(new BigDecimal("150000.00"))
                .build();

        when(serviceRepository.existsByServiceNameIgnoreCaseAndIsDeletedFalse("Tư Vấn Khách Hàng")).thenReturn(true);

        assertThatThrownBy(() -> catalogService.createService(request))
                .isInstanceOf(ServiceException.class)
                .satisfies(ex -> {
                    ServiceException sex = (ServiceException) ex;
                    assertThat(sex.getCode()).isEqualTo(ServiceErrorCode.SERVICE_NAME_ALREADY_EXISTS.code());
                    assertThat(sex.getMessage()).isEqualTo("Tên dịch vụ đã tồn tại trong danh mục này. Vui lòng nhập tên khác.");
                });

        verify(serviceRepository, never()).save(any());
        verify(tenantCacheService, never()).evictTenantServiceCache(anyString());
        verify(tenantCacheService, never()).cacheTenantServices(anyString(), anyList());
    }

    @Test
    @DisplayName("Create service should throw 400 Bad Request when standard duration is invalid")
    void createService_shouldThrowBadRequest_whenDurationInvalid() {
        CreateServiceRequest request = CreateServiceRequest.builder()
                .serviceName("Hỗ Trợ Kỹ Thuật")
                .standardDuration(0)
                .price(new BigDecimal("100000.00"))
                .build();

        assertThatThrownBy(() -> catalogService.createService(request))
                .isInstanceOf(ServiceException.class)
                .satisfies(ex -> {
                    ServiceException sex = (ServiceException) ex;
                    assertThat(sex.getCode()).isEqualTo(ServiceErrorCode.SERVICE_DURATION_INVALID.code());
                });

        verify(serviceRepository, never()).save(any());
    }

    @Test
    @DisplayName("Update service details and status should succeed and refresh Valkey cache")
    void updateService_shouldSucceedAndRefreshCache() {
        String serviceId = "svc-uuid-001";
        TenantService existing = TenantService.builder()
                .id(serviceId)
                .serviceCode("SVC-0001")
                .serviceName("Tư Vấn Ban Đầu")
                .standardDuration(15)
                .price(new BigDecimal("100000.00"))
                .status(ServiceStatus.ACTIVE)
                .isDeleted(false)
                .build();

        UpdateServiceRequest request = UpdateServiceRequest.builder()
                .serviceName("Tư Vấn Nâng Cao")
                .standardDuration(25)
                .price(new BigDecimal("250000.00"))
                .status(ServiceStatus.INACTIVE)
                .build();

        when(serviceRepository.findByIdAndIsDeletedFalse(serviceId)).thenReturn(Optional.of(existing));
        when(serviceRepository.existsByServiceNameIgnoreCaseAndIdNotAndIsDeletedFalse("Tư Vấn Nâng Cao", serviceId)).thenReturn(false);
        when(serviceRepository.save(any(TenantService.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(serviceRepository.findAllByStatusAndIsDeletedFalse(ServiceStatus.ACTIVE)).thenReturn(Collections.emptyList());

        ServiceResponse response = catalogService.updateService(serviceId, request);

        assertThat(response.getServiceName()).isEqualTo("Tư Vấn Nâng Cao");
        assertThat(response.getStandardDuration()).isEqualTo(25);
        assertThat(response.getPrice()).isEqualByComparingTo("250000.00");
        assertThat(response.getStatus()).isEqualTo(ServiceStatus.INACTIVE);

        verify(tenantCacheService).evictTenantServiceCache(TENANT_CODE);
        verify(tenantCacheService).cacheTenantServices(eq(TENANT_CODE), anyList());
        verify(serviceRepository).save(existing);
    }

    @Test
    @DisplayName("Update service should throw 409 Conflict when name is used by another service")
    void updateService_shouldThrowConflict_whenNameUsedByAnotherService() {
        String serviceId = "svc-uuid-001";
        TenantService existing = TenantService.builder()
                .id(serviceId)
                .serviceCode("SVC-0001")
                .serviceName("Dịch Vụ A")
                .standardDuration(15)
                .price(new BigDecimal("100000.00"))
                .status(ServiceStatus.ACTIVE)
                .isDeleted(false)
                .build();

        UpdateServiceRequest request = UpdateServiceRequest.builder()
                .serviceName("Dịch Vụ B")
                .standardDuration(20)
                .price(new BigDecimal("150000.00"))
                .build();

        when(serviceRepository.findByIdAndIsDeletedFalse(serviceId)).thenReturn(Optional.of(existing));
        when(serviceRepository.existsByServiceNameIgnoreCaseAndIdNotAndIsDeletedFalse("Dịch Vụ B", serviceId)).thenReturn(true);

        assertThatThrownBy(() -> catalogService.updateService(serviceId, request))
                .isInstanceOf(ServiceException.class)
                .satisfies(ex -> {
                    ServiceException sex = (ServiceException) ex;
                    assertThat(sex.getCode()).isEqualTo(ServiceErrorCode.SERVICE_NAME_ALREADY_EXISTS.code());
                });

        verify(serviceRepository, never()).save(any());
        verify(tenantCacheService, never()).evictTenantServiceCache(anyString());
        verify(tenantCacheService, never()).cacheTenantServices(anyString(), anyList());
    }

    @Test
    @DisplayName("Update service should throw 404 Not Found when service ID does not exist")
    void updateService_shouldThrowNotFound_whenServiceDoesNotExist() {
        String serviceId = "non-existent-id";
        UpdateServiceRequest request = UpdateServiceRequest.builder()
                .serviceName("Dịch Vụ Mới")
                .standardDuration(20)
                .price(new BigDecimal("150000.00"))
                .build();

        when(serviceRepository.findByIdAndIsDeletedFalse(serviceId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> catalogService.updateService(serviceId, request))
                .isInstanceOf(ServiceException.class)
                .satisfies(ex -> {
                    ServiceException sex = (ServiceException) ex;
                    assertThat(sex.getCode()).isEqualTo(ServiceErrorCode.SERVICE_NOT_FOUND.code());
                });
    }

    @Test
    @DisplayName("Delete service should be blocked and throw 409 Conflict when active appointments exist")
    void deleteService_shouldThrowConflict_whenPendingAppointmentsExist() {
        String serviceId = "svc-uuid-001";
        TenantService existing = TenantService.builder()
                .id(serviceId)
                .serviceCode("SVC-0001")
                .serviceName("Tư Vấn Khách Hàng")
                .standardDuration(15)
                .price(new BigDecimal("100000.00"))
                .status(ServiceStatus.ACTIVE)
                .isDeleted(false)
                .build();

        when(serviceRepository.findByIdAndIsDeletedFalse(serviceId)).thenReturn(Optional.of(existing));
        when(serviceRepository.countPendingAppointmentsByServiceId(serviceId)).thenReturn(3L);

        assertThatThrownBy(() -> catalogService.deleteService(serviceId))
                .isInstanceOf(ServiceException.class)
                .satisfies(ex -> {
                    ServiceException sex = (ServiceException) ex;
                    assertThat(sex.getCode()).isEqualTo(ServiceErrorCode.SERVICE_CANNOT_DELETE_ACTIVE_BOOKINGS.code());
                    assertThat(sex.getMessage()).isEqualTo("Dịch vụ này hiện đang có lịch hẹn hoặc vé chờ chưa hoàn tất. Hệ thống không thể xóa dịch vụ. Quản trị viên vui lòng chuyển sang trạng thái “Tạm ngưng hoạt động” (Inactive) để dừng tiếp nhận lượt mới.");
                });

        verify(serviceRepository, never()).save(any());
        verify(tenantCacheService, never()).evictTenantServiceCache(anyString());
        verify(tenantCacheService, never()).cacheTenantServices(anyString(), anyList());
    }

    @Test
    @DisplayName("Delete service should be blocked and throw 409 Conflict when waiting queue tickets exist")
    void deleteService_shouldThrowConflict_whenWaitingTicketsExist() {
        String serviceId = "svc-uuid-001";
        TenantService existing = TenantService.builder()
                .id(serviceId)
                .serviceCode("SVC-0001")
                .serviceName("Tư Vấn Khách Hàng")
                .standardDuration(15)
                .price(new BigDecimal("100000.00"))
                .status(ServiceStatus.ACTIVE)
                .isDeleted(false)
                .build();

        when(serviceRepository.findByIdAndIsDeletedFalse(serviceId)).thenReturn(Optional.of(existing));
        when(serviceRepository.countPendingAppointmentsByServiceId(serviceId)).thenReturn(0L);
        when(serviceRepository.countPendingQueueTicketsByServiceId(serviceId)).thenReturn(2L);

        assertThatThrownBy(() -> catalogService.deleteService(serviceId))
                .isInstanceOf(ServiceException.class)
                .satisfies(ex -> {
                    ServiceException sex = (ServiceException) ex;
                    assertThat(sex.getCode()).isEqualTo(ServiceErrorCode.SERVICE_CANNOT_DELETE_ACTIVE_BOOKINGS.code());
                });

        verify(serviceRepository, never()).save(any());
        verify(tenantCacheService, never()).evictTenantServiceCache(anyString());
        verify(tenantCacheService, never()).cacheTenantServices(anyString(), anyList());
    }

    @Test
    @DisplayName("Delete service should soft-delete and refresh cache when no active bookings exist")
    void deleteService_shouldSoftDelete_whenNoActiveBookings() {
        String serviceId = "svc-uuid-001";
        TenantService existing = TenantService.builder()
                .id(serviceId)
                .serviceCode("SVC-0001")
                .serviceName("Tư Vấn Khách Hàng")
                .standardDuration(15)
                .price(new BigDecimal("100000.00"))
                .status(ServiceStatus.ACTIVE)
                .isDeleted(false)
                .build();

        when(serviceRepository.findByIdAndIsDeletedFalse(serviceId)).thenReturn(Optional.of(existing));
        when(serviceRepository.countPendingAppointmentsByServiceId(serviceId)).thenReturn(0L);
        when(serviceRepository.countPendingQueueTicketsByServiceId(serviceId)).thenReturn(0L);
        when(serviceRepository.save(any(TenantService.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(serviceRepository.findAllByStatusAndIsDeletedFalse(ServiceStatus.ACTIVE)).thenReturn(Collections.emptyList());

        catalogService.deleteService(serviceId);

        assertThat(existing.getIsDeleted()).isTrue();
        assertThat(existing.getStatus()).isEqualTo(ServiceStatus.INACTIVE);
        verify(tenantCacheService).evictTenantServiceCache(TENANT_CODE);
        verify(tenantCacheService).cacheTenantServices(eq(TENANT_CODE), anyList());
        verify(serviceRepository).save(existing);
    }

    @Test
    @DisplayName("getActiveServices should return cached services on cache hit without repository call")
    void getActiveServices_shouldReturnCachedServicesOnHit() {
        List<ServiceResponse> cachedServices = List.of(
                ServiceResponse.builder()
                        .id("svc-1")
                        .serviceCode("SVC-0001")
                        .serviceName("Tư Vấn Khách Hàng")
                        .build()
        );

        when(tenantCacheService.getCachedTenantServices(TENANT_CODE)).thenReturn(cachedServices);

        List<ServiceResponse> result = catalogService.getActiveServices();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getServiceCode()).isEqualTo("SVC-0001");
        verify(serviceRepository, never()).findAllByStatusAndIsDeletedFalse(any());
    }

    @Test
    @DisplayName("getActiveServices should fetch from database and update cache on cache miss")
    void getActiveServices_shouldFetchFromDbAndCacheOnMiss() {
        TenantService entity = TenantService.builder()
                .id("svc-1")
                .serviceCode("SVC-0001")
                .serviceName("Tư Vấn Khách Hàng")
                .standardDuration(15)
                .price(new BigDecimal("100000.00"))
                .status(ServiceStatus.ACTIVE)
                .build();

        when(tenantCacheService.getCachedTenantServices(TENANT_CODE)).thenReturn(null);
        when(serviceRepository.findAllByStatusAndIsDeletedFalse(ServiceStatus.ACTIVE)).thenReturn(List.of(entity));

        List<ServiceResponse> result = catalogService.getActiveServices();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getServiceCode()).isEqualTo("SVC-0001");
        verify(serviceRepository).findAllByStatusAndIsDeletedFalse(ServiceStatus.ACTIVE);
        verify(tenantCacheService).cacheTenantServices(eq(TENANT_CODE), anyList());
    }

    @Test
    @DisplayName("Search services with keyword and status filter")
    void searchServices_shouldDelegateToRepository() {
        PageRequest pageRequest = PageRequest.of(0, 10);
        TenantService s1 = TenantService.builder()
                .id("svc-1")
                .serviceCode("SVC-0001")
                .serviceName("Tư Vấn Trực Tiếp")
                .standardDuration(15)
                .price(new BigDecimal("100000.00"))
                .status(ServiceStatus.ACTIVE)
                .build();

        when(serviceRepository.searchServices("tư vấn", ServiceStatus.ACTIVE, pageRequest))
                .thenReturn(new PageImpl<>(List.of(s1)));

        Page<ServiceResponse> result = catalogService.searchServices("tư vấn", ServiceStatus.ACTIVE, pageRequest);

        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().get(0).getServiceCode()).isEqualTo("SVC-0001");
        verify(serviceRepository).searchServices("tư vấn", ServiceStatus.ACTIVE, pageRequest);
    }

    @Test
    @DisplayName("Public search services should default to ACTIVE status")
    void searchPublicServices_shouldDefaultToActiveStatus() {
        PageRequest pageRequest = PageRequest.of(0, 10);
        TenantService s1 = TenantService.builder()
                .id("svc-1")
                .serviceCode("SVC-0001")
                .serviceName("Thanh Toán Hóa Đơn")
                .standardDuration(20)
                .price(new BigDecimal("200000.00"))
                .status(ServiceStatus.ACTIVE)
                .build();

        when(serviceRepository.searchServices("thanh toán", ServiceStatus.ACTIVE, pageRequest))
                .thenReturn(new PageImpl<>(List.of(s1)));

        Page<ServiceResponse> result = catalogService.searchPublicServices("tenant-alpha", "thanh toán", null, pageRequest);

        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().get(0).getServiceName()).isEqualTo("Thanh Toán Hóa Đơn");
        verify(serviceRepository).searchServices("thanh toán", ServiceStatus.ACTIVE, pageRequest);
    }
}
