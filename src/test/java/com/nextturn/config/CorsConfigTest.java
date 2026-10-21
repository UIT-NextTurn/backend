package com.nextturn.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CorsConfigTest {

    @Test
    void shouldConfigureCorsWithInjectedProperties() {
        CorsConfig corsConfig = new CorsConfig();

        ReflectionTestUtils.setField(corsConfig, "allowedOriginPatterns", List.of("https://nextturn.com", " https://admin.nextturn.com "));
        ReflectionTestUtils.setField(corsConfig, "allowedHeaders", List.of("Origin", " Content-Type ", "Authorization"));
        ReflectionTestUtils.setField(corsConfig, "allowedMethods", List.of("GET", " POST ", "DELETE"));
        ReflectionTestUtils.setField(corsConfig, "maxAge", 7200L);
        ReflectionTestUtils.setField(corsConfig, "allowCredentials", true);

        CorsConfigurationSource source = corsConfig.corsConfigurationSource();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/health");
        CorsConfiguration config = source.getCorsConfiguration(request);

        assertThat(config).isNotNull();
        assertThat(config.getAllowCredentials()).isTrue();
        assertThat(config.getAllowedOriginPatterns()).containsExactly("https://nextturn.com", "https://admin.nextturn.com");
        assertThat(config.getAllowedHeaders()).containsExactly("Origin", "Content-Type", "Authorization");
        assertThat(config.getAllowedMethods()).containsExactly("GET", "POST", "DELETE");
        assertThat(config.getMaxAge()).isEqualTo(7200L);
    }

    @Test
    void shouldHandleNullOrEmptyListsGracefully() {
        CorsConfig corsConfig = new CorsConfig();

        ReflectionTestUtils.setField(corsConfig, "allowedOriginPatterns", null);
        ReflectionTestUtils.setField(corsConfig, "allowedHeaders", null);
        ReflectionTestUtils.setField(corsConfig, "allowedMethods", null);
        ReflectionTestUtils.setField(corsConfig, "maxAge", 3600L);
        ReflectionTestUtils.setField(corsConfig, "allowCredentials", false);

        CorsConfigurationSource source = corsConfig.corsConfigurationSource();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/any");
        CorsConfiguration config = source.getCorsConfiguration(request);

        assertThat(config).isNotNull();
        assertThat(config.getAllowCredentials()).isFalse();
        assertThat(config.getAllowedOriginPatterns()).isEmpty();
        assertThat(config.getAllowedHeaders()).isEmpty();
        assertThat(config.getAllowedMethods()).isEmpty();
    }
}
