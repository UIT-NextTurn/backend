package com.nextturn.service.auth;

import com.nextturn.dto.auth.ConfigureIdpRequest;
import com.nextturn.dto.auth.TenantIdpResponse;
import com.nextturn.entity.CentralTenant;
import com.nextturn.enums.IdpProviderType;
import com.nextturn.exception.admin.AdminErrorCode;
import com.nextturn.exception.admin.AdminException;
import com.nextturn.exception.tenant.TenantErrorCode;
import com.nextturn.exception.tenant.TenantException;
import com.nextturn.infrastructure.multitenancy.TenantContextHolder;
import com.nextturn.infrastructure.security.keycloak.KeycloakTokenService;
import com.nextturn.repository.auth.CentralTenantRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TenantIdpServiceTest {

    private static final String TENANT_CODE = "clinic_sai_gon";
    private static final String ORG_ID = "org-uuid-12345";

    @Mock
    private CentralTenantRepository centralTenantRepository;

    @Mock
    private KeycloakTokenService keycloakTokenService;

    @InjectMocks
    private TenantIdpService idpService;

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
    @DisplayName("Should successfully configure external IdP and link to Keycloak Organization")
    void shouldConfigureIdpSuccessfully() {
        ConfigureIdpRequest request = ConfigureIdpRequest.builder()
                .providerType(IdpProviderType.GOOGLE)
                .displayName("Clinic Google Workspace")
                .clientId("google-client-id")
                .clientSecret("google-client-secret")
                .emailDomain("clinic.vn")
                .build();

        TenantIdpResponse expectedResponse = TenantIdpResponse.builder()
                .alias("idp-" + TENANT_CODE)
                .providerId("google")
                .displayName("Clinic Google Workspace")
                .clientId("google-client-id")
                .emailDomains(List.of("clinic.vn"))
                .enabled(true)
                .build();

        when(centralTenantRepository.findByTenantCodeAndIsDeletedFalse(TENANT_CODE)).thenReturn(Optional.of(mockTenant));
        when(keycloakTokenService.configureOrganizationIdp(eq(ORG_ID), eq(TENANT_CODE), any(ConfigureIdpRequest.class)))
                .thenReturn(expectedResponse);

        TenantIdpResponse actualResponse = idpService.configureIdp(request);

        assertThat(actualResponse).isNotNull();
        assertThat(actualResponse.getAlias()).isEqualTo("idp-clinic_sai_gon");
        assertThat(actualResponse.getProviderId()).isEqualTo("google");
        assertThat(actualResponse.getEmailDomains()).containsExactly("clinic.vn");
        assertThat(actualResponse.isEnabled()).isTrue();

        verify(keycloakTokenService).configureOrganizationIdp(ORG_ID, TENANT_CODE, request);
    }

    @Test
    @DisplayName("Should retrieve active external IdP configuration")
    void shouldGetActiveIdpConfig() {
        TenantIdpResponse expectedResponse = TenantIdpResponse.builder()
                .alias("idp-" + TENANT_CODE)
                .providerId("oidc")
                .displayName("Clinic SSO")
                .clientId("oidc-client-id")
                .emailDomains(List.of("clinic.vn"))
                .enabled(true)
                .build();

        when(centralTenantRepository.findByTenantCodeAndIsDeletedFalse(TENANT_CODE)).thenReturn(Optional.of(mockTenant));
        when(keycloakTokenService.getOrganizationIdp(ORG_ID, TENANT_CODE)).thenReturn(expectedResponse);

        TenantIdpResponse actual = idpService.getIdpConfig();

        assertThat(actual).isNotNull();
        assertThat(actual.getProviderId()).isEqualTo("oidc");
    }

    @Test
    @DisplayName("Should throw IDP_NOT_FOUND when no IdP is configured for tenant")
    void shouldThrowWhenNoIdpConfigured() {
        when(centralTenantRepository.findByTenantCodeAndIsDeletedFalse(TENANT_CODE)).thenReturn(Optional.of(mockTenant));
        when(keycloakTokenService.getOrganizationIdp(ORG_ID, TENANT_CODE)).thenReturn(null);

        assertThatThrownBy(() -> idpService.getIdpConfig())
                .isInstanceOf(AdminException.class)
                .satisfies(ex -> assertThat(((AdminException) ex).getCode()).isEqualTo(AdminErrorCode.IDP_NOT_FOUND.code()));
    }

    @Test
    @DisplayName("Should remove external IdP configuration from Keycloak")
    void shouldRemoveIdpSuccessfully() {
        when(centralTenantRepository.findByTenantCodeAndIsDeletedFalse(TENANT_CODE)).thenReturn(Optional.of(mockTenant));

        idpService.removeIdp();

        verify(keycloakTokenService).removeOrganizationIdp(ORG_ID, TENANT_CODE);
    }

    @Test
    @DisplayName("Should throw TENANT_CONTEXT_MISSING when tenant code is not bound to request context")
    void shouldThrowWhenTenantContextMissing() {
        TenantContextHolder.clear();

        ConfigureIdpRequest request = ConfigureIdpRequest.builder()
                .providerType(IdpProviderType.OIDC)
                .displayName("SSO")
                .clientId("client")
                .clientSecret("secret")
                .emailDomain("clinic.vn")
                .build();

        assertThatThrownBy(() -> idpService.configureIdp(request))
                .isInstanceOf(TenantException.class)
                .satisfies(ex -> assertThat(((TenantException) ex).getCode()).isEqualTo(TenantErrorCode.TENANT_CONTEXT_MISSING.code()));
    }
}
