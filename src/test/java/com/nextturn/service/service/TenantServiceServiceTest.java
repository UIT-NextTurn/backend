package com.nextturn.service.service;

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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TenantServiceServiceTest {

    private static final String TENANT_CODE = "tenant-alpha";

    @Mock
    private TenantServiceRepository serviceRepository;

    @Mock
    private TenantCacheService tenantCacheService;

    @InjectMocks
    private TenantServiceService serviceService;

    @BeforeEach
    void setUp() {
        TenantContextHolder.setTenantCode(TENANT_CODE);
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
    }

    @Test
    @DisplayName("Create service should succeed with initial code SVC-0001 when catalog is empty")
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

        ServiceResponse response = serviceService.createService(request);

        assertThat(response).isNotNull();
        assertThat(response.getServiceName()).isEqualTo("Tư Vấn Khách Hàng");
        assertThat(response.getServiceCode()).isEqualTo("SVC-0001");
        assertThat(response.getStandardDuration()).isEqualTo(15);
        assertThat(response.getPrice()).isEqualByComparingTo("150000.00");
        assertThat(response.getStatus()).isEqualTo(ServiceStatus.ACTIVE);

        verify(serviceRepository).save(any(TenantService.class));
        verify(tenantCacheService).evictTenantServiceCache(TENANT_CODE);
        verify(tenantCacheService).cacheTenantServices(eq(TENANT_CODE), anyList());
    }

    @Test
    @DisplayName("Create service should auto-increment sequence code based on highest existing code")
    void createService_shouldIncrementSequentialCode() {
        CreateServiceRequest request = CreateServiceRequest.builder()
                .serviceName("Khám Chuyên Sâu")
                .standardDuration(30)
                .price(new BigDecimal("300000.00"))
                .build();

        when(serviceRepository.existsByServiceNameIgnoreCaseAndIsDeletedFalse("Khám Chuyên Sâu")).thenReturn(false);
        when(serviceRepository.findMaxServiceCode()).thenReturn(Optional.of("SVC-0002"));
        when(serviceRepository.existsByServiceCodeAndIsDeletedFalse("SVC-0003")).thenReturn(false);
        when(serviceRepository.save(any(TenantService.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(serviceRepository.findAllByStatusAndIsDeletedFalse(ServiceStatus.ACTIVE)).thenReturn(Collections.emptyList());

        ServiceResponse response = serviceService.createService(request);

        assertThat(response.getServiceCode()).isEqualTo("SVC-0003");
        verify(tenantCacheService).evictTenantServiceCache(TENANT_CODE);
    }

    @Test
    @DisplayName("Create service should throw 409 Conflict when service name already exists in tenant")
    void createService_shouldThrowConflict_whenNameAlreadyExists() {
        CreateServiceRequest request = CreateServiceRequest.builder()
                .serviceName("Tư Vấn Khách Hàng")
                .standardDuration(15)
                .build();

        when(serviceRepository.existsByServiceNameIgnoreCaseAndIsDeletedFalse("Tư Vấn Khách Hàng")).thenReturn(true);

        assertThatThrownBy(() -> serviceService.createService(request))
                .isInstanceOf(ServiceException.class)
                .satisfies(ex -> {
                    ServiceException sex = (ServiceException) ex;
                    assertThat(sex.getCode()).isEqualTo(ServiceErrorCode.SERVICE_NAME_ALREADY_EXISTS.code());
                    assertThat(sex.getMessage()).contains("Tư Vấn Khách Hàng");
                });

        verify(serviceRepository, never()).save(any());
        verify(tenantCacheService, never()).evictTenantServiceCache(any());
    }

    @Test
    @DisplayName("Update service should update info, preserve existing appointments, and refresh cache")
    void updateService_shouldUpdateAndEvictCache() {
        String serviceId = "svc-uuid-1";
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
                .price(new BigDecimal("200000.00"))
                .status(ServiceStatus.INACTIVE)
                .build();

        when(serviceRepository.findByIdAndIsDeletedFalse(serviceId)).thenReturn(Optional.of(existing));
        when(serviceRepository.existsByServiceNameIgnoreCaseAndIdNotAndIsDeletedFalse("Tư Vấn Nâng Cao", serviceId)).thenReturn(false);
        when(serviceRepository.save(any(TenantService.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(serviceRepository.findAllByStatusAndIsDeletedFalse(ServiceStatus.ACTIVE)).thenReturn(Collections.emptyList());

        ServiceResponse response = serviceService.updateService(serviceId, request);

        assertThat(response.getServiceName()).isEqualTo("Tư Vấn Nâng Cao");
        assertThat(response.getStandardDuration()).isEqualTo(25);
        assertThat(response.getStatus()).isEqualTo(ServiceStatus.INACTIVE);

        verify(serviceRepository).save(existing);
        verify(tenantCacheService).evictTenantServiceCache(TENANT_CODE);
        verify(tenantCacheService).cacheTenantServices(eq(TENANT_CODE), anyList());
    }

    @Test
    @DisplayName("Update service should throw 409 Conflict when new name is taken by another service")
    void updateService_shouldThrowConflict_whenNameTakenByAnother() {
        String serviceId = "svc-uuid-1";
        TenantService existing = TenantService.builder()
                .id(serviceId)
                .serviceCode("SVC-0001")
                .serviceName("Tư Vấn 1")
                .standardDuration(15)
                .isDeleted(false)
                .build();

        UpdateServiceRequest request = UpdateServiceRequest.builder()
                .serviceName("Tên Đã Trùng")
                .standardDuration(20)
                .build();

        when(serviceRepository.findByIdAndIsDeletedFalse(serviceId)).thenReturn(Optional.of(existing));
        when(serviceRepository.existsByServiceNameIgnoreCaseAndIdNotAndIsDeletedFalse("Tên Đã Trùng", serviceId)).thenReturn(true);

        assertThatThrownBy(() -> serviceService.updateService(serviceId, request))
                .isInstanceOf(ServiceException.class)
                .satisfies(ex -> {
                    ServiceException sex = (ServiceException) ex;
                    assertThat(sex.getCode()).isEqualTo(ServiceErrorCode.SERVICE_NAME_ALREADY_EXISTS.code());
                });

        verify(serviceRepository, never()).save(any());
    }

    @Test
    @DisplayName("Delete service should reject when active appointments exist")
    void deleteService_shouldReject_whenActiveAppointmentsExist() {
        String serviceId = "svc-uuid-1";
        TenantService service = TenantService.builder()
                .id(serviceId)
                .serviceCode("SVC-0001")
                .serviceName("Tư Vấn Khách Hàng")
                .isDeleted(false)
                .build();

        when(serviceRepository.findByIdAndIsDeletedFalse(serviceId)).thenReturn(Optional.of(service));
        when(serviceRepository.countPendingAppointmentsByServiceId(serviceId)).thenReturn(3L);
        when(serviceRepository.countPendingQueueTicketsByServiceId(serviceId)).thenReturn(0L);

        assertThatThrownBy(() -> serviceService.deleteService(serviceId))
                .isInstanceOf(ServiceException.class)
                .satisfies(ex -> {
                    ServiceException sex = (ServiceException) ex;
                    assertThat(sex.getCode()).isEqualTo(ServiceErrorCode.SERVICE_CANNOT_DELETE_ACTIVE_BOOKINGS.code());
                });

        verify(serviceRepository, never()).save(any());
        verify(tenantCacheService, never()).evictTenantServiceCache(any());
    }

    @Test
    @DisplayName("Delete service should reject when active queue tickets exist")
    void deleteService_shouldReject_whenActiveTicketsExist() {
        String serviceId = "svc-uuid-1";
        TenantService service = TenantService.builder()
                .id(serviceId)
                .serviceCode("SVC-0001")
                .serviceName("Tư Vấn Khách Hàng")
                .isDeleted(false)
                .build();

        when(serviceRepository.findByIdAndIsDeletedFalse(serviceId)).thenReturn(Optional.of(service));
        when(serviceRepository.countPendingAppointmentsByServiceId(serviceId)).thenReturn(0L);
        when(serviceRepository.countPendingQueueTicketsByServiceId(serviceId)).thenReturn(5L);

        assertThatThrownBy(() -> serviceService.deleteService(serviceId))
                .isInstanceOf(ServiceException.class)
                .satisfies(ex -> {
                    ServiceException sex = (ServiceException) ex;
                    assertThat(sex.getCode()).isEqualTo(ServiceErrorCode.SERVICE_CANNOT_DELETE_ACTIVE_BOOKINGS.code());
                });

        verify(serviceRepository, never()).save(any());
        verify(tenantCacheService, never()).evictTenantServiceCache(any());
    }

    @Test
    @DisplayName("Delete service should soft delete and refresh cache when no relational blockers")
    void deleteService_shouldSoftDelete_whenNoBlockers() {
        String serviceId = "svc-uuid-1";
        TenantService service = TenantService.builder()
                .id(serviceId)
                .serviceCode("SVC-0001")
                .serviceName("Tư Vấn Khách Hàng")
                .isDeleted(false)
                .build();

        when(serviceRepository.findByIdAndIsDeletedFalse(serviceId)).thenReturn(Optional.of(service));
        when(serviceRepository.countPendingAppointmentsByServiceId(serviceId)).thenReturn(0L);
        when(serviceRepository.countPendingQueueTicketsByServiceId(serviceId)).thenReturn(0L);
        when(serviceRepository.save(any(TenantService.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(serviceRepository.findAllByStatusAndIsDeletedFalse(ServiceStatus.ACTIVE)).thenReturn(Collections.emptyList());

        serviceService.deleteService(serviceId);

        assertThat(service.getIsDeleted()).isTrue();
        verify(serviceRepository).save(service);
        verify(tenantCacheService).evictTenantServiceCache(TENANT_CODE);
        verify(tenantCacheService).cacheTenantServices(eq(TENANT_CODE), anyList());
    }

    @Test
    @DisplayName("searchServices should delegate to queryService and repository")
    void searchServices_shouldDelegateToRepository() {
        PageRequest pageRequest = PageRequest.of(0, 10);
        TenantService svc = TenantService.builder()
                .id("svc-1")
                .serviceCode("SVC-0001")
                .serviceName("Tư Vấn")
                .build();

        when(serviceRepository.searchServices("Tư Vấn", ServiceStatus.ACTIVE, pageRequest))
                .thenReturn(new PageImpl<>(List.of(svc)));

        Page<ServiceResponse> page = serviceService.searchServices("Tư Vấn", ServiceStatus.ACTIVE, pageRequest);

        assertThat(page.getContent()).hasSize(1);
        assertThat(page.getContent().get(0).getServiceCode()).isEqualTo("SVC-0001");
    }
}

