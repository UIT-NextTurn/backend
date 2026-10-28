package com.nextturn.controller.auth;

import com.nextturn.config.CorsConfig;
import com.nextturn.config.ScalarConfig;
import com.nextturn.dto.auth.ConfigureIdpRequest;
import com.nextturn.dto.auth.TenantIdpResponse;
import com.nextturn.enums.IdpProviderType;
import com.nextturn.exception.GlobalExceptionHandler;
import com.nextturn.infrastructure.multitenancy.TenantContextFilter;
import com.nextturn.infrastructure.security.TestSecurityConfig;
import com.nextturn.infrastructure.security.config.SecurityConfig;
import com.nextturn.infrastructure.security.keycloak.KeycloakJwtAuthenticationConverter;
import com.nextturn.infrastructure.security.keycloak.KeycloakJwtGrantedAuthoritiesConverter;
import com.nextturn.infrastructure.security.principal.CurrentUserArgumentResolver;
import com.nextturn.infrastructure.security.service.SecurityContextHelper;
import com.nextturn.service.auth.TenantIdpService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(TenantIdpController.class)
@EnableMethodSecurity(prePostEnabled = true)
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
class TenantIdpControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private KeycloakJwtAuthenticationConverter authenticationConverter;

    @MockitoBean
    private TenantIdpService idpService;

    @Test
    @DisplayName("POST /api/v1/auth/idp should configure IdP and return 201 Created for TENANT_ADMIN")
    void shouldConfigureIdpWhenTenantAdmin() throws Exception {
        TenantIdpResponse response = TenantIdpResponse.builder()
                .alias("idp-clinic_sai_gon")
                .providerId("google")
                .displayName("Clinic Google Workspace")
                .clientId("client-id-xyz")
                .emailDomains(List.of("clinic.vn"))
                .enabled(true)
                .build();

        when(idpService.configureIdp(any(ConfigureIdpRequest.class))).thenReturn(response);

        AbstractAuthenticationToken tenantAdminAuth = authenticationConverter.convert(
                createMockJwt("TENANT_ADMIN", "admin@clinic.vn", "clinic_sai_gon")
        );

        String requestJson = """
                {
                    "providerType": "GOOGLE",
                    "displayName": "Clinic Google Workspace",
                    "clientId": "client-id-xyz",
                    "clientSecret": "client-secret-xyz",
                    "emailDomain": "clinic.vn"
                }
                """;

        mockMvc.perform(post("/api/v1/auth/idp")
                        .with(authentication(tenantAdminAuth))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.alias").value("idp-clinic_sai_gon"))
                .andExpect(jsonPath("$.data.providerId").value("google"))
                .andExpect(jsonPath("$.data.emailDomains[0]").value("clinic.vn"));
    }

    @Test
    @DisplayName("POST /api/v1/auth/idp should forbid COUNTER_STAFF role (403)")
    void shouldForbidCounterStaffFromConfiguringIdp() throws Exception {
        AbstractAuthenticationToken counterStaffAuth = authenticationConverter.convert(
                createMockJwt("COUNTER_STAFF", "staff@clinic.vn", "clinic_sai_gon")
        );

        String requestJson = """
                {
                    "providerType": "GOOGLE",
                    "displayName": "Hacked IdP",
                    "clientId": "client-id",
                    "clientSecret": "secret",
                    "emailDomain": "clinic.vn"
                }
                """;

        mockMvc.perform(post("/api/v1/auth/idp")
                        .with(authentication(counterStaffAuth))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("GET /api/v1/auth/idp should return active IdP configuration")
    void shouldGetIdpConfig() throws Exception {
        TenantIdpResponse response = TenantIdpResponse.builder()
                .alias("idp-clinic_sai_gon")
                .providerId("oidc")
                .displayName("Entra ID")
                .emailDomains(List.of("clinic.vn"))
                .enabled(true)
                .build();

        when(idpService.getIdpConfig()).thenReturn(response);

        AbstractAuthenticationToken tenantAdminAuth = authenticationConverter.convert(
                createMockJwt("TENANT_ADMIN", "admin@clinic.vn", "clinic_sai_gon")
        );

        mockMvc.perform(get("/api/v1/auth/idp")
                        .with(authentication(tenantAdminAuth)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.providerId").value("oidc"));
    }

    @Test
    @DisplayName("DELETE /api/v1/auth/idp should remove IdP configuration")
    void shouldRemoveIdp() throws Exception {
        AbstractAuthenticationToken tenantAdminAuth = authenticationConverter.convert(
                createMockJwt("TENANT_ADMIN", "admin@clinic.vn", "clinic_sai_gon")
        );

        mockMvc.perform(delete("/api/v1/auth/idp")
                        .with(authentication(tenantAdminAuth)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    private Jwt createMockJwt(String role, String email, String tenantCode) {
        var jwtBuilder = Jwt.withTokenValue("mock-jwt-token")
                .header("alg", "RS256")
                .issuer("http://ryan-a1:8180/realms/nextturn")
                .subject("user-uuid-xyz")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300))
                .claim("azp", "nextturn-backend")
                .claim("email", email)
                .claim("scope", "openid organization")
                .claim("resource_access", Map.of(
                        "nextturn-backend", Map.of(
                                "roles", List.of(role)
                        )
                ));

        if (tenantCode != null) {
            jwtBuilder.claim("tenant_code", tenantCode);
        }

        return jwtBuilder.build();
    }
}
