package com.nextturn.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

import java.util.List;

@Configuration
public class CorsConfig {

    @Value("${cors.allowed-origin-patterns:http://localhost:3000,http://localhost:5173,http://127.0.0.1:3000,http://127.0.0.1:5173}")
    private List<String> allowedOriginPatterns;

    @Value("${cors.allowed-headers:Origin,Content-Type,Accept,Authorization,X-Requested-With}")
    private List<String> allowedHeaders;

    @Value("${cors.allowed-methods:GET,POST,PUT,PATCH,DELETE,OPTIONS}")
    private List<String> allowedMethods;

    @Value("${cors.max-age:3600}")
    private Long maxAge;

    @Value("${cors.allow-credentials:true}")
    private Boolean allowCredentials;

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowCredentials(Boolean.TRUE.equals(allowCredentials));

        List<String> trimmedOrigins = allowedOriginPatterns == null ? List.of() :
                allowedOriginPatterns.stream().map(String::trim).filter(s -> !s.isEmpty()).toList();
        List<String> trimmedHeaders = allowedHeaders == null ? List.of() :
                allowedHeaders.stream().map(String::trim).filter(s -> !s.isEmpty()).toList();
        List<String> trimmedMethods = allowedMethods == null ? List.of() :
                allowedMethods.stream().map(String::trim).filter(s -> !s.isEmpty()).toList();

        config.setAllowedOriginPatterns(trimmedOrigins);
        config.setAllowedHeaders(trimmedHeaders);
        config.setAllowedMethods(trimmedMethods);
        config.setMaxAge(maxAge);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }

    @Bean
    public CorsFilter corsFilter(CorsConfigurationSource corsConfigurationSource) {
        return new CorsFilter(corsConfigurationSource);
    }
}
