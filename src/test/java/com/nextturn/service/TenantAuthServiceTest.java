package com.nextturn.service;

import com.nextturn.dto.AuthTokenResponse;
import com.nextturn.dto.RefreshTokenRequest;
import com.nextturn.dto.TenantAuthRequest;
import com.nextturn.dto.TenantAuthResponse;
import com.nextturn.dto.TokenExchangeRequest;
import com.nextturn.entity.CentralTenant;
import com.nextturn.enums.TenantStatus;
import com.nextturn.exception.auth.AuthErrorCode;
import com.nextturn.exception.auth.AuthException;
import com.nextturn.helper.KeycloakAuthHelper;
import com.nextturn.infrastructure.security.keycloak.KeycloakTokenService;
import com.nextturn.repository.CentralTenantRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit test for {@link TenantAuthService} business logic.
 * <p>
 * Tests service orchestration, request validation, tenant status verification,
 * and response mapping in isolation from database repositories and helper internals.
 */
@ExtendWith(MockitoExtension.class)
class TenantAuthServiceTest {

    private static final String MOCK_STAFF_LOGIN_URL = "https://auth.nextturn.com/realms/nextturn/protocol/openid-connect/auth?client_id=nextturn-backend";

    @Mock
    private CentralTenantRepository centralTenantRepository;

    @Mock
    private KeycloakAuthHelper keycloakAuthHelper;

    @Mock
    private KeycloakTokenService keycloakTokenService;

    @InjectMocks
    private TenantAuthService service;

    @Test
    @DisplayName("Should successfully authenticate active tenant, invoke helper for login URL, and map response")
    void shouldAuthenticateActiveTenantSuccessfully() {
        CentralTenant tenant = CentralTenant.builder()
                .id("tenant-uuid-001")
                .tenantCode("clinic_sai_gon")
                .tenantName("Phòng khám Đa khoa Sài Gòn")
                .adminEmail("admin@saigonclinic.vn")
                .status(TenantStatus.ACTIVE)
                .organisationId("kc-org-uuid-123")
                .isDeleted(false)
                .build();

        when(centralTenantRepository.findByTenantCodeIgnoreCaseAndIsDeletedFalse("clinic_sai_gon"))
                .thenReturn(Optional.of(tenant));
        when(keycloakAuthHelper.buildStaffLoginUrl(tenant))
                .thenReturn(MOCK_STAFF_LOGIN_URL);

        TenantAuthRequest request = TenantAuthRequest.builder()
                .tenantCode("clinic_sai_gon")
                .build();

        TenantAuthResponse response = service.authenticateTenant(request);

        assertThat(response).isNotNull();
        assertThat(response.getTenantId()).isEqualTo("tenant-uuid-001");
        assertThat(response.getTenantCode()).isEqualTo("clinic_sai_gon");
        assertThat(response.getTenantName()).isEqualTo("Phòng khám Đa khoa Sài Gòn");
        assertThat(response.getStatus()).isEqualTo(TenantStatus.ACTIVE.name());
        assertThat(response.getOrganisationId()).isEqualTo("kc-org-uuid-123");
        assertThat(response.getLoginUrl()).isEqualTo(MOCK_STAFF_LOGIN_URL);
        assertThat(response.getAllowedRoles()).containsExactly("TENANT_ADMIN", "COUNTER_STAFF");

        verify(keycloakAuthHelper).buildStaffLoginUrl(tenant);
    }

    @Test
    @DisplayName("Should trim whitespace from tenant code before querying repository")
    void shouldTrimWhitespaceBeforeQueryingRepository() {
        CentralTenant tenant = CentralTenant.builder()
                .id("tenant-uuid-002")
                .tenantCode("clinic_sai_gon")
                .tenantName("Phòng khám Đa khoa Sài Gòn")
                .status(TenantStatus.ACTIVE)
                .isDeleted(false)
                .build();

        when(centralTenantRepository.findByTenantCodeIgnoreCaseAndIsDeletedFalse("clinic_sai_gon"))
                .thenReturn(Optional.of(tenant));
        when(keycloakAuthHelper.buildStaffLoginUrl(tenant))
                .thenReturn(MOCK_STAFF_LOGIN_URL);

        TenantAuthRequest request = TenantAuthRequest.builder()
                .tenantCode("   clinic_sai_gon   ")
                .build();

        TenantAuthResponse response = service.authenticateTenant(request);

        assertThat(response).isNotNull();
        assertThat(response.getTenantCode()).isEqualTo("clinic_sai_gon");
        verify(centralTenantRepository).findByTenantCodeIgnoreCaseAndIsDeletedFalse("clinic_sai_gon");
    }

    @Test
    @DisplayName("Should throw AuthException (AUTH_001) when request is null")
    void shouldThrowTenantCodeRequiredWhenRequestIsNull() {
        assertThatThrownBy(() -> service.authenticateTenant(null))
                .isInstanceOf(AuthException.class)
                .satisfies(ex -> {
                    AuthException authEx = (AuthException) ex;
                    assertThat(authEx.getCode()).isEqualTo(AuthErrorCode.TENANT_CODE_REQUIRED.code());
                    assertThat(authEx.getHttpStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(authEx.getMessage()).contains("Tenant code must not be blank");
                });

        verifyNoInteractions(centralTenantRepository, keycloakAuthHelper);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "\t", "\n"})
    @DisplayName("Should throw AuthException (AUTH_001) when tenant code is null or blank")
    void shouldThrowTenantCodeRequiredWhenTenantCodeIsBlank(String blankCode) {
        TenantAuthRequest request = TenantAuthRequest.builder()
                .tenantCode(blankCode)
                .build();

        assertThatThrownBy(() -> service.authenticateTenant(request))
                .isInstanceOf(AuthException.class)
                .satisfies(ex -> {
                    AuthException authEx = (AuthException) ex;
                    assertThat(authEx.getCode()).isEqualTo(AuthErrorCode.TENANT_CODE_REQUIRED.code());
                    assertThat(authEx.getHttpStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                });

        verifyNoInteractions(centralTenantRepository, keycloakAuthHelper);
    }

    @Test
    @DisplayName("Should throw AuthException (AUTH_003) and not call helper when tenant code is not found")
    void shouldThrowTenantNotFoundWhenTenantCodeDoesNotExist() {
        when(centralTenantRepository.findByTenantCodeIgnoreCaseAndIsDeletedFalse("unknown_tenant"))
                .thenReturn(Optional.empty());

        TenantAuthRequest request = TenantAuthRequest.builder()
                .tenantCode("unknown_tenant")
                .build();

        assertThatThrownBy(() -> service.authenticateTenant(request))
                .isInstanceOf(AuthException.class)
                .satisfies(ex -> {
                    AuthException authEx = (AuthException) ex;
                    assertThat(authEx.getCode()).isEqualTo(AuthErrorCode.TENANT_NOT_FOUND.code());
                    assertThat(authEx.getHttpStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(authEx.getMessage()).contains("Tenant not found with code: unknown_tenant");
                });

        verifyNoInteractions(keycloakAuthHelper);
    }

    @ParameterizedTest
    @EnumSource(value = TenantStatus.class, names = {"PENDING", "BLOCKED", "DELETED"})
    @DisplayName("Should throw AuthException (AUTH_004) and not call helper when tenant status is not ACTIVE")
    void shouldThrowTenantInactiveWhenTenantIsNotActive(TenantStatus inactiveStatus) {
        CentralTenant tenant = CentralTenant.builder()
                .id("tenant-uuid-003")
                .tenantCode("inactive_clinic")
                .tenantName("Phòng khám Tạm khóa")
                .status(inactiveStatus)
                .isDeleted(false)
                .build();

        when(centralTenantRepository.findByTenantCodeIgnoreCaseAndIsDeletedFalse("inactive_clinic"))
                .thenReturn(Optional.of(tenant));

        TenantAuthRequest request = TenantAuthRequest.builder()
                .tenantCode("inactive_clinic")
                .build();

        assertThatThrownBy(() -> service.authenticateTenant(request))
                .isInstanceOf(AuthException.class)
                .satisfies(ex -> {
                    AuthException authEx = (AuthException) ex;
                    assertThat(authEx.getCode()).isEqualTo(AuthErrorCode.TENANT_INACTIVE.code());
                    assertThat(authEx.getHttpStatus()).isEqualTo(HttpStatus.FORBIDDEN);
                    assertThat(authEx.getMessage()).contains("Tenant facility is not active. Current status: " + inactiveStatus);
                });

        verifyNoInteractions(keycloakAuthHelper);
    }

    @Test
    @DisplayName("Should delegate staff login URL building to KeycloakAuthHelper")
    void shouldDelegateStaffLoginUrlToHelper() {
        CentralTenant tenant = CentralTenant.builder()
                .id("tenant-uuid-004")
                .tenantCode("hospital_hn")
                .tenantName("Bệnh viện Đa khoa Hà Nội")
                .status(TenantStatus.ACTIVE)
                .build();

        when(keycloakAuthHelper.buildStaffLoginUrl(tenant))
                .thenReturn("https://auth.nextturn.com/login/hospital_hn");

        String loginUrl = service.buildStaffLoginUrl(tenant);

        assertThat(loginUrl).isEqualTo("https://auth.nextturn.com/login/hospital_hn");
        verify(keycloakAuthHelper).buildStaffLoginUrl(tenant);
    }

    @Test
    @DisplayName("Should exchange authorization code and validate tenant affiliation successfully when tenant is active")
    void shouldExchangeCodeAndValidateActiveTenantSuccessfully() {
        CentralTenant tenant = CentralTenant.builder()
                .tenantCode("shop-b")
                .status(TenantStatus.ACTIVE)
                .build();

        AuthTokenResponse mockResponse = AuthTokenResponse.builder()
                .accessToken("mock-access-token")
                .tenantCode("shop-b")
                .build();

        when(keycloakAuthHelper.getDefaultRedirectUri()).thenReturn("http://localhost:3000/auth/callback");
        when(keycloakTokenService.exchangeAuthorizationCode("auth-code-123", "http://localhost:3000/auth/callback"))
                .thenReturn(mockResponse);
        when(centralTenantRepository.findByTenantCodeIgnoreCaseAndIsDeletedFalse("shop-b"))
                .thenReturn(Optional.of(tenant));

        TokenExchangeRequest request = TokenExchangeRequest.builder()
                .code("auth-code-123")
                .build();

        AuthTokenResponse result = service.exchangeAuthorizationCode(request);

        assertThat(result).isNotNull();
        assertThat(result.getAccessToken()).isEqualTo("mock-access-token");
        verify(centralTenantRepository).findByTenantCodeIgnoreCaseAndIsDeletedFalse("shop-b");
    }

    @Test
    @DisplayName("Should throw TENANT_NOT_FOUND when exchanging code for non-existent tenant facility")
    void shouldThrowTenantNotFoundWhenExchangingCodeForUnknownTenant() {
        AuthTokenResponse mockResponse = AuthTokenResponse.builder()
                .accessToken("mock-access-token")
                .tenantCode("ghost-shop")
                .build();

        when(keycloakAuthHelper.getDefaultRedirectUri()).thenReturn("http://localhost:3000/auth/callback");
        when(keycloakTokenService.exchangeAuthorizationCode("auth-code-123", "http://localhost:3000/auth/callback"))
                .thenReturn(mockResponse);
        when(centralTenantRepository.findByTenantCodeIgnoreCaseAndIsDeletedFalse("ghost-shop"))
                .thenReturn(Optional.empty());

        TokenExchangeRequest request = TokenExchangeRequest.builder()
                .code("auth-code-123")
                .build();

        assertThatThrownBy(() -> service.exchangeAuthorizationCode(request))
                .isInstanceOf(AuthException.class)
                .satisfies(ex -> {
                    AuthException authEx = (AuthException) ex;
                    assertThat(authEx.getCode()).isEqualTo(AuthErrorCode.TENANT_NOT_FOUND.code());
                    assertThat(authEx.getMessage()).contains("Tenant facility not found: ghost-shop");
                });
    }

    @Test
    @DisplayName("Should throw TENANT_INACTIVE when exchanging code for inactive tenant facility")
    void shouldThrowTenantInactiveWhenExchangingCodeForInactiveTenant() {
        CentralTenant tenant = CentralTenant.builder()
                .tenantCode("blocked-shop")
                .status(TenantStatus.BLOCKED)
                .build();

        AuthTokenResponse mockResponse = AuthTokenResponse.builder()
                .accessToken("mock-access-token")
                .tenantCode("blocked-shop")
                .build();

        when(keycloakAuthHelper.getDefaultRedirectUri()).thenReturn("http://localhost:3000/auth/callback");
        when(keycloakTokenService.exchangeAuthorizationCode("auth-code-123", "http://localhost:3000/auth/callback"))
                .thenReturn(mockResponse);
        when(centralTenantRepository.findByTenantCodeIgnoreCaseAndIsDeletedFalse("blocked-shop"))
                .thenReturn(Optional.of(tenant));

        TokenExchangeRequest request = TokenExchangeRequest.builder()
                .code("auth-code-123")
                .build();

        assertThatThrownBy(() -> service.exchangeAuthorizationCode(request))
                .isInstanceOf(AuthException.class)
                .satisfies(ex -> {
                    AuthException authEx = (AuthException) ex;
                    assertThat(authEx.getCode()).isEqualTo(AuthErrorCode.TENANT_INACTIVE.code());
                    assertThat(authEx.getMessage()).contains("Tenant facility is not active. Status: BLOCKED");
                });
    }

    @Test
    @DisplayName("Should refresh access token and validate tenant affiliation successfully")
    void shouldRefreshTokenAndValidateActiveTenantSuccessfully() {
        CentralTenant tenant = CentralTenant.builder()
                .tenantCode("shop-b")
                .status(TenantStatus.ACTIVE)
                .build();

        AuthTokenResponse mockResponse = AuthTokenResponse.builder()
                .accessToken("mock-refreshed-token")
                .tenantCode("shop-b")
                .build();

        when(keycloakTokenService.refreshAccessToken("refresh-token-xyz"))
                .thenReturn(mockResponse);
        when(centralTenantRepository.findByTenantCodeIgnoreCaseAndIsDeletedFalse("shop-b"))
                .thenReturn(Optional.of(tenant));

        RefreshTokenRequest request = RefreshTokenRequest.builder()
                .refreshToken("refresh-token-xyz")
                .build();

        AuthTokenResponse result = service.refreshAccessToken(request);

        assertThat(result).isNotNull();
        assertThat(result.getAccessToken()).isEqualTo("mock-refreshed-token");
        verify(centralTenantRepository).findByTenantCodeIgnoreCaseAndIsDeletedFalse("shop-b");
    }

    @Test
    @DisplayName("Should skip tenant lookup when token response does not contain tenantCode (e.g. Super Admin)")
    void shouldSkipTenantLookupWhenResponseHasNoTenantCode() {
        AuthTokenResponse mockResponse = AuthTokenResponse.builder()
                .accessToken("mock-superadmin-token")
                .tenantCode(null)
                .build();

        when(keycloakAuthHelper.getDefaultRedirectUri()).thenReturn("http://localhost:3000/auth/callback");
        when(keycloakTokenService.exchangeAuthorizationCode("code-sa", "http://localhost:3000/auth/callback"))
                .thenReturn(mockResponse);

        TokenExchangeRequest request = TokenExchangeRequest.builder()
                .code("code-sa")
                .build();

        AuthTokenResponse result = service.exchangeAuthorizationCode(request);

        assertThat(result).isNotNull();
        verifyNoInteractions(centralTenantRepository);
    }
}
