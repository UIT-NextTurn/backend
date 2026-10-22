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
                "nextturn",
                "http://localhost:8180",
                "http://localhost:8180/realms/nextturn",
                "http://localhost:8180/realms/nextturn/protocol/openid-connect/certs"
        );
        helper = new KeycloakAuthHelper(properties, "http://localhost:3000/auth/callback");
    }

    @Test
    @DisplayName("Should build complete login URL with organization parameter when present")
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
                .contains("scope=openid%20profile%20email%20organization")
                .contains("redirect_uri=http://localhost:3000/auth/callback")
                .contains("tenant_code=clinic_sai_gon")
                .contains("organization=kc-org-999");
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
}
