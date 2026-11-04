package com.nextturn.service.service;

import com.nextturn.dto.service.ServiceResponse;
import com.nextturn.entity.TenantService;
import com.nextturn.enums.ServiceStatus;
import com.nextturn.exception.service.ServiceErrorCode;
import com.nextturn.exception.service.ServiceException;
import com.nextturn.exception.tenant.TenantErrorCode;
import com.nextturn.exception.tenant.TenantException;
import com.nextturn.infrastructure.multitenancy.TenantContextHolder;
import com.nextturn.repository.service.TenantServiceRepository;
import com.nextturn.service.service.impl.TenantServiceQueryServiceImpl;
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
class TenantServiceQueryServiceTest {

    private static final String TENANT_CODE = "tenant-alpha";

    @Mock
    private TenantServiceRepository serviceRepository;

    @Mock
    private TenantCacheService tenantCacheService;

    @InjectMocks
    private TenantServiceQueryServiceImpl queryService;

    @BeforeEach
    void setUp() {
        TenantContextHolder.setTenantCode(TENANT_CODE);
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
    }

    @Test
    @DisplayName("getServiceById should return service details when found")
    void getServiceById_shouldReturnServiceDetails() {
        TenantService service = TenantService.builder()
                .id("svc-uuid-1")
                .serviceCode("SVC-0001")
                .serviceName("Tư Vấn Khách Hàng")
                .standardDuration(15)
                .price(new BigDecimal("150000.00"))
                .status(ServiceStatus.ACTIVE)
                .isDeleted(false)
                .build();

        when(serviceRepository.findByIdAndIsDeletedFalse("svc-uuid-1")).thenReturn(Optional.of(service));

        ServiceResponse response = queryService.getServiceById("svc-uuid-1");

        assertThat(response).isNotNull();
        assertThat(response.getServiceCode()).isEqualTo("SVC-0001");
        assertThat(response.getServiceName()).isEqualTo("Tư Vấn Khách Hàng");
    }

    @Test
    @DisplayName("getServiceById should throw 404 when service is not found")
    void getServiceById_shouldThrowNotFound_whenMissing() {
        when(serviceRepository.findByIdAndIsDeletedFalse("non-existent")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> queryService.getServiceById("non-existent"))
                .isInstanceOf(ServiceException.class)
                .satisfies(ex -> {
                    ServiceException sex = (ServiceException) ex;
                    assertThat(sex.getCode()).isEqualTo(ServiceErrorCode.SERVICE_NOT_FOUND.code());
                });
    }

    @Test
    @DisplayName("getActiveServices should hit Valkey cache when present")
    void getActiveServices_shouldReturnCachedServicesOnHit() {
        List<ServiceResponse> cached = List.of(
                ServiceResponse.builder()
                        .id("svc-uuid-1")
                        .serviceCode("SVC-0001")
                        .serviceName("Tư Vấn Khách Hàng")
                        .status(ServiceStatus.ACTIVE)
                        .build()
        );

        when(tenantCacheService.getCachedTenantServices(TENANT_CODE)).thenReturn(cached);

        List<ServiceResponse> result = queryService.getActiveServices();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getServiceCode()).isEqualTo("SVC-0001");
        verify(serviceRepository, never()).findAllByStatusAndIsDeletedFalse(any());
    }

    @Test
    @DisplayName("getActiveServices should fetch from database and populate cache on cache miss")
    void getActiveServices_shouldFetchFromDbAndCacheOnMiss() {
        when(tenantCacheService.getCachedTenantServices(TENANT_CODE)).thenReturn(Collections.emptyList());

        TenantService service = TenantService.builder()
                .id("svc-uuid-1")
                .serviceCode("SVC-0001")
                .serviceName("Tư Vấn Khách Hàng")
                .standardDuration(15)
                .price(new BigDecimal("150000.00"))
                .status(ServiceStatus.ACTIVE)
                .isDeleted(false)
                .build();

        when(serviceRepository.findAllByStatusAndIsDeletedFalse(ServiceStatus.ACTIVE))
                .thenReturn(List.of(service));

        List<ServiceResponse> result = queryService.getActiveServices();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getServiceCode()).isEqualTo("SVC-0001");
        verify(tenantCacheService).cacheTenantServices(eq(TENANT_CODE), anyList());
    }

    @Test
    @DisplayName("searchPublicServices should switch tenant context and default to ACTIVE")
    void searchPublicServices_shouldDefaultToActiveStatus() {
        TenantService service = TenantService.builder()
                .id("svc-uuid-1")
                .serviceCode("SVC-0001")
                .serviceName("Tư Vấn Khách Hàng")
                .standardDuration(15)
                .price(new BigDecimal("150000.00"))
                .status(ServiceStatus.ACTIVE)
                .isDeleted(false)
                .build();

        PageRequest pageRequest = PageRequest.of(0, 10);
        when(serviceRepository.searchServices("Tư Vấn", ServiceStatus.ACTIVE, pageRequest))
                .thenReturn(new PageImpl<>(List.of(service)));

        Page<ServiceResponse> page = queryService.searchPublicServices("tenant-beta", "Tư Vấn", null, pageRequest);

        assertThat(page.getContent()).hasSize(1);
        assertThat(page.getContent().get(0).getServiceName()).isEqualTo("Tư Vấn Khách Hàng");
        assertThat(TenantContextHolder.getTenantCode()).isEqualTo(TENANT_CODE);
    }

    @Test
    @DisplayName("searchPublicServices should throw when tenant code is missing")
    void searchPublicServices_shouldThrowWhenTenantCodeMissing() {
        TenantContextHolder.clear();
        PageRequest pageRequest = PageRequest.of(0, 10);

        assertThatThrownBy(() -> queryService.searchPublicServices(null, null, null, pageRequest))
                .isInstanceOf(TenantException.class)
                .satisfies(ex -> {
                    TenantException tex = (TenantException) ex;
                    assertThat(tex.getCode()).isEqualTo(TenantErrorCode.TENANT_CONTEXT_MISSING.code());
                });
    }
}

