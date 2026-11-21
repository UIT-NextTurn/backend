package com.nextturn.helper;

import com.nextturn.entity.CentralTenant;
import com.nextturn.enums.TenantStatus;
import com.nextturn.infrastructure.security.keycloak.KeycloakProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class KeycloakAuthHelperTest {

    private KeycloakAuthHelper helper;

    @BeforeEach
    void setUp() {
        KeycloakProperties properties = new KeycloakProperties(
                "nextturn-backend",
                "",
                "nextturn",
                "http://localhost:8180",
                "http://localhost:8180/realms/nextturn",
                "http://localhost:8180/realms/nextturn/protocol/openid-connect/certs"
        );
        helper = new KeycloakAuthHelper(properties, "http://localhost:3000/auth/callback");
    }

    @Test
    @DisplayName("Should build complete login URL with organization parameter and tenant name when present")
    void shouldBuildLoginUrlWithOrganization() {
        CentralTenant tenant = CentralTenant.builder()
                .tenantCode("clinic_sai_gon")
                .tenantName("Phòng khám Sài Gòn")
                .organisationId("kc-org-999")
                .status(TenantStatus.ACTIVE)
                .build();

        String url = helper.buildStaffLoginUrl(tenant);

        assertThat(url)
                .startsWith("http://localhost:8180/realms/nextturn/protocol/openid-connect/auth?")
                .contains("client_id=nextturn-backend")
                .contains("response_type=code")
                .contains("scope=openid%20organization")
                .contains("redirect_uri=http://localhost:3000/auth/callback")
                .contains("tenant_code=clinic_sai_gon")
                .contains("organization=kc-org-999")
                .contains("tenant_name=Ph%C3%B2ng%20kh%C3%A1m%20S%C3%A0i%20G%C3%B2n");
    }

    @Test
    @DisplayName("Should build login URL with custom logo URL when present (Method 2)")
    void shouldBuildLoginUrlWithLogoUrl() {
        CentralTenant tenant = CentralTenant.builder()
                .tenantCode("dental_care")
                .tenantName("Nha Khoa Nụ Cười")
                .logoUrl("https://cdn.example.com/branding/dental_care/logo.png")
                .status(TenantStatus.ACTIVE)
                .build();

        String url = helper.buildStaffLoginUrl(tenant);

        assertThat(url)
                .contains("tenant_code=dental_care")
                .contains("tenant_name=Nha%20Khoa%20N%E1%BB%A5%20C%C6%B0%E1%BB%9Di")
                .contains("logo_url=https://cdn.example.com/branding/dental_care/logo.png");
    }

    @Test
    @DisplayName("Should build login URL without organization parameter when absent")
    void shouldBuildLoginUrlWithoutOrganization() {
        String url = helper.buildStaffLoginUrl("hospital_hn", null);

        assertThat(url)
                .contains("client_id=nextturn-backend")
                .contains("tenant_code=hospital_hn")
                .doesNotContain("organization=");
    }

    @Test
    @DisplayName("Should return null when tenant is null")
    void shouldReturnNullWhenTenantIsNull() {
        String url = helper.buildStaffLoginUrl(null);
        assertThat(url).isNull();
    }

    @Test
    @DisplayName("Should build login URL with custom web domain redirect URI from configuration")
    void shouldBuildLoginUrlWithCustomWebDomainRedirectUri() {
        KeycloakProperties properties = new KeycloakProperties(
                "nextturn-backend",
                "",
                "nextturn",
                "https://auth.example.com",
                "https://auth.example.com/realms/nextturn",
                "https://auth.example.com/realms/nextturn/protocol/openid-connect/certs"
        );
        KeycloakAuthHelper customHelper = new KeycloakAuthHelper(properties, "https://myclinic.example.com/auth/callback");
        String url = customHelper.buildStaffLoginUrl("clinic_01", null);

        assertThat(url)
                .startsWith("https://auth.example.com/realms/nextturn/protocol/openid-connect/auth?")
                .contains("redirect_uri=https://myclinic.example.com/auth/callback");
    }

    @Test
    @DisplayName("Should build Super Admin login URL with portal=superadmin parameter and without tenant context")
    void shouldBuildSuperAdminLoginUrl() {
        String url = helper.buildSuperAdminLoginUrl();

        assertThat(url)
                .startsWith("http://localhost:8180/realms/nextturn/protocol/openid-connect/auth?")
                .contains("client_id=nextturn-backend")
                .contains("response_type=code")
                .contains("scope=openid")
                .contains("redirect_uri=http://localhost:3000/auth/callback")
                .contains("portal=superadmin")
                .doesNotContain("tenant_code=")
                .doesNotContain("organization=");
    }
}
