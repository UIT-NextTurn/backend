package com.nextturn.infrastructure.security.config;

import com.nextturn.infrastructure.multitenancy.TenantContextFilter;
import com.nextturn.infrastructure.security.keycloak.KeycloakJwtAuthenticationConverter;
import com.nextturn.infrastructure.security.keycloak.KeycloakProperties;
import com.nextturn.infrastructure.security.principal.CurrentUserArgumentResolver;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

/**
 * Spring Security configuration for NextTurn OAuth2 Resource Server.
 * Configures SecurityFilterChain, CORS, stateless session management,
 * URL authorization rules, Keycloak JWT authentication converter,
 * TenantContextFilter for schema-per-tenant multi-tenancy,
 * and registers {@link CurrentUserArgumentResolver}.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity(prePostEnabled = true)
@EnableConfigurationProperties(KeycloakProperties.class)
public class SecurityConfig implements WebMvcConfigurer {

    private final KeycloakJwtAuthenticationConverter keycloakJwtAuthenticationConverter;
    private final CurrentUserArgumentResolver currentUserArgumentResolver;
    private final TenantContextFilter tenantContextFilter;

    public SecurityConfig(KeycloakJwtAuthenticationConverter keycloakJwtAuthenticationConverter,
                          CurrentUserArgumentResolver currentUserArgumentResolver,
                          TenantContextFilter tenantContextFilter) {
        this.keycloakJwtAuthenticationConverter = keycloakJwtAuthenticationConverter;
        this.currentUserArgumentResolver = currentUserArgumentResolver;
        this.tenantContextFilter = tenantContextFilter;
    }

    /**
     * Prevents duplicate execution by disabling registration in the standard servlet container filter chain.
     * The filter is wired specifically inside Spring Security's filter chain after BearerTokenAuthenticationFilter.
     */
    @Bean
    public FilterRegistrationBean<TenantContextFilter> tenantContextFilterRegistration(TenantContextFilter filter) {
        FilterRegistrationBean<TenantContextFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .cors(Customizer.withDefaults())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> authorize
                        // 1. Authenticated Auth endpoints for Tenant Admin
                        .requestMatchers("/api/v1/auth/staff", "/api/v1/auth/staff/**").hasRole("TENANT_ADMIN")
                        .requestMatchers("/api/v1/auth/idp", "/api/v1/auth/idp/**").hasRole("TENANT_ADMIN")
                        // 2. Public endpoints
                        .requestMatchers(
                                "/api/v1/public",
                                "/api/v1/public/**",
                                "/api/v1/auth",
                                "/api/v1/auth/**",
                                "/api/v1/health",
                                "/api/v1/health/**",
                                "/actuator",
                                "/actuator/**",
                                "/v3/api-docs",
                                "/v3/api-docs/**",
                                "/swagger-ui",
                                "/swagger-ui/**",
                                "/swagger-ui.html",
                                "/scalar",
                                "/scalar/**",
                                "/scalar.html",
                                "/docs",
                                "/docs/**"
                        ).permitAll()
                        // 3. Central Superadmin endpoints
                        .requestMatchers("/api/v1/central/**").hasRole("SUPERADMIN")
                        // 4. Tenant Admin endpoints
                        .requestMatchers("/api/v1/tenant/admin/**").hasRole("TENANT_ADMIN")
                        // 5. Counter Staff endpoints
                        .requestMatchers("/api/v1/tenant/counter/**").hasRole("COUNTER_STAFF")
                        // 6. End-User customer endpoints
                        .requestMatchers("/api/v1/customer/**").hasRole("END_USER")
                        // 7. Any other request must be authenticated
                        .anyRequest().authenticated()
                )
                .oauth2ResourceServer(oauth2 -> {
                    DefaultBearerTokenResolver defaultResolver = new DefaultBearerTokenResolver();
                    oauth2.bearerTokenResolver(request -> {
                        String uri = request.getRequestURI();
                        if (isPublicUri(uri)) {
                            // Skip bearer token decoding on public endpoints so invalid/placeholder tokens from docs don't trigger 401
                            return null;
                        }
                        return defaultResolver.resolve(request);
                    });
                    oauth2.jwt(jwt -> jwt.jwtAuthenticationConverter(keycloakJwtAuthenticationConverter));
                })
                .addFilterAfter(tenantContextFilter, BearerTokenAuthenticationFilter.class);

        return http.build();
    }

    private boolean isPublicUri(String uri) {
        if (uri == null) {
            return false;
        }
        if (uri.startsWith("/api/v1/auth/staff") || uri.startsWith("/api/v1/auth/idp")) {
            return false;
        }
        return uri.startsWith("/api/v1/public")
                || uri.startsWith("/api/v1/auth")
                || uri.startsWith("/api/v1/health")
                || uri.startsWith("/actuator")
                || uri.startsWith("/v3/api-docs")
                || uri.startsWith("/swagger-ui")
                || uri.startsWith("/scalar")
                || uri.startsWith("/docs")
                || uri.equals("/scalar.html");
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(currentUserArgumentResolver);
    }
}
