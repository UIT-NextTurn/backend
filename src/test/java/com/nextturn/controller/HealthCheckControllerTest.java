package com.nextturn.controller;

import com.nextturn.config.CorsConfig;
import com.nextturn.security.TestSecurityConfig;
import com.nextturn.security.config.SecurityConfig;
import com.nextturn.security.keycloak.KeycloakJwtAuthenticationConverter;
import com.nextturn.security.keycloak.KeycloakJwtGrantedAuthoritiesConverter;
import com.nextturn.security.keycloak.KeycloakProperties;
import com.nextturn.security.principal.CurrentUserArgumentResolver;
import com.nextturn.security.service.SecurityContextHelper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(HealthCheckController.class)
@EnableMethodSecurity(prePostEnabled = true)
@Import({
        SecurityConfig.class,
        TestSecurityConfig.class,
        CorsConfig.class,
        KeycloakJwtAuthenticationConverter.class,
        KeycloakJwtGrantedAuthoritiesConverter.class,
        CurrentUserArgumentResolver.class,
        SecurityContextHelper.class
})
class HealthCheckControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void healthCheckShouldReturnUpStatus() throws Exception {
        mockMvc.perform(get("/api/v1/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.status").value("UP"))
                .andExpect(jsonPath("$.data.system").value("NextTurn Virtual Queue & Booking Platform"));
    }
}
