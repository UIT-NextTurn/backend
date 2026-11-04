package com.nextturn.controller.catalog;

import com.nextturn.config.CorsConfig;
import com.nextturn.config.ScalarConfig;
import com.nextturn.dto.service.ServiceResponse;
import com.nextturn.enums.ServiceStatus;
import com.nextturn.exception.GlobalExceptionHandler;
import com.nextturn.infrastructure.multitenancy.TenantContextFilter;
import com.nextturn.infrastructure.security.TestSecurityConfig;
import com.nextturn.infrastructure.security.config.SecurityConfig;
import com.nextturn.infrastructure.security.keycloak.KeycloakJwtAuthenticationConverter;
import com.nextturn.infrastructure.security.keycloak.KeycloakJwtGrantedAuthoritiesConverter;
import com.nextturn.infrastructure.security.principal.CurrentUserArgumentResolver;
import com.nextturn.infrastructure.security.service.SecurityContextHelper;
import com.nextturn.service.service.TenantServiceService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(PublicCatalogController.class)
@Import({
        ScalarConfig.class,
        SecurityConfig.class,
        TestSecurityConfig.class,
        CorsConfig.class,
        TenantContextFilter.class,
        KeycloakJwtAuthenticationConverter.class,
        KeycloakJwtGrantedAuthoritiesConverter.class,
        CurrentUserArgumentResolver.class,
        SecurityContextHelper.class,
        GlobalExceptionHandler.class
})
class PublicCatalogControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private TenantServiceService catalogService;

    @Test
    @DisplayName("GET /api/v1/public/services: should allow unauthenticated access for customer pre-booking")
    void shouldAllowUnauthenticatedAccessForCustomerPreBooking() throws Exception {
        ServiceResponse response = ServiceResponse.builder()
                .id("svc-uuid-001")
                .serviceCode("SVC-0001")
                .serviceName("Tư Vấn Khách Hàng")
                .standardDuration(15)
                .price(new BigDecimal("150000.00"))
                .status(ServiceStatus.ACTIVE)
                .createdAt(LocalDateTime.now())
                .build();

        when(catalogService.searchPublicServices(eq("tenant-alpha"), eq("tư vấn"), any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(response)));

        mockMvc.perform(get("/api/v1/public/services")
                        .header("X-Tenant-Code", "tenant-alpha")
                        .param("keyword", "tư vấn"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(200))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data[0].serviceCode").value("SVC-0001"))
                .andExpect(jsonPath("$.data[0].serviceName").value("Tư Vấn Khách Hàng"))
                .andExpect(jsonPath("$.pagination.totalElements").value(1));
    }

    @Test
    @DisplayName("GET /api/v1/public/services: should accept tenantCode query param as fallback")
    void shouldAcceptTenantCodeAsQueryParam() throws Exception {
        ServiceResponse response = ServiceResponse.builder()
                .id("svc-uuid-002")
                .serviceCode("SVC-0002")
                .serviceName("Hỗ Trợ Kỹ Thuật Nâng Cao")
                .standardDuration(30)
                .price(new BigDecimal("300000.00"))
                .status(ServiceStatus.ACTIVE)
                .createdAt(LocalDateTime.now())
                .build();

        when(catalogService.searchPublicServices(eq("tenant-alpha"), any(), any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(response)));

        mockMvc.perform(get("/api/v1/public/services")
                        .param("tenantCode", "tenant-alpha"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(200))
                .andExpect(jsonPath("$.data[0].serviceCode").value("SVC-0002"));
    }
}
