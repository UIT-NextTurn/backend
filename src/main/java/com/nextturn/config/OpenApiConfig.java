package com.nextturn.config;

import com.nextturn.controller.HealthCheckController;
import com.nextturn.controller.auth.CustomerAuthController;
import com.nextturn.controller.auth.TenantAuthController;
import com.nextturn.controller.auth.TenantIdpController;
import com.nextturn.controller.auth.TenantStaffController;
import com.nextturn.controller.tenant.TenantManagementController;
import com.nextturn.infrastructure.security.annotation.RequireTenantAdmin;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import org.springdoc.core.customizers.OperationCustomizer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * OpenAPI 3.0 specification configuration for NextTurn backend API.
 * Dynamically binds server environments and host ports from environment variables (.env).
 */
@Configuration
public class OpenApiConfig {

    @Value("${server.port:8080}")
    private String serverPort;

    @Value("${APP_SERVER_URL:${API_DOCS_SERVER_URL:}}")
    private String configuredServerUrl;

    @Bean
    public OpenAPI nextTurnOpenAPI() {
        List<Server> servers = new ArrayList<>();

        // 1. Current origin (relative path) - guarantees that interactive queries execute against the active host/port in browser
        servers.add(new Server().url("/").description("Current Server Origin (Relative - Default)"));

        // 2. Explicit server URL if configured in .env (APP_SERVER_URL or API_DOCS_SERVER_URL)
        if (StringUtils.hasText(configuredServerUrl)) {
            servers.add(new Server().url(configuredServerUrl.trim()).description("Configured Environment Server (.env)"));
        }

        // 3. Local Development Server using dynamic SERVER_PORT from .env
        servers.add(new Server().url("http://localhost:" + serverPort).description("Local Development Server (Port " + serverPort + ")"));

        // 4. Caddy / Nginx edge reverse proxy
        servers.add(new Server().url("http://localhost").description("Caddy Local Edge Reverse Proxy"));

        final String securitySchemeName = "BearerAuth";

        return new OpenAPI()
                .info(new Info()
                        .title("NextTurn Multi-Tenant Platform REST API")
                        .version("1.0.0")
                        .description("""
                                Core REST API specification for NextTurn: Real-time virtual queue orchestration,
                                appointment booking, and multi-tenant facility management.
                                
                                ### Authentication Spectrum
                                - **Public Endpoints**: `/api/v1/health`, `/api/v1/auth/**`, `/api/v1/public/**` (No authentication required)
                                - **Staff/Admin Portals**: Secured via Keycloak OIDC JWT Bearer tokens
                                - **Tenant Identification (REQ-AUTH-01)**: AWS IAM-style facility code validation
                                """)
                        .contact(new Contact().name("NextTurn Engineering Team").email("engineering@nextturn.internal"))
                        .license(new License().name("Proprietary").url("https://nextturn.vn/terms")))
                .servers(servers)
                .components(new Components()
                        .addSecuritySchemes("BearerAuth", new SecurityScheme()
                                .name("BearerAuth")
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .description("Keycloak OIDC JWT Access Token with `ROLE_*` authority claims"))
                        .addSecuritySchemes("bearerAuth", new SecurityScheme()
                                .name("bearerAuth")
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .description("Keycloak OIDC JWT Access Token with `ROLE_*` authority claims")));
    }

    /**
     * Operation customizer ensuring Swagger/Scalar UI only renders the 'X-Tenant-Code' header
     * on tenant-scoped administration endpoints (e.g. staff management, IdP config),
     * while excluding public verify/lookup endpoints and customer auth.
     */
    @Bean
    public OperationCustomizer customizeTenantHeader() {
        return (operation, handlerMethod) -> {
            Class<?> beanType = handlerMethod.getBeanType();

            // Exclude public endpoints, verify paths, customer auth, healthcheck, and central tenant onboarding
            if (beanType.equals(TenantAuthController.class)
                    || beanType.equals(CustomerAuthController.class)
                    || beanType.equals(HealthCheckController.class)
                    || beanType.equals(TenantManagementController.class)) {
                return operation;
            }

            // Only require X-Tenant-Code on tenant-specific administration endpoints
            boolean isTenantEndpoint = beanType.equals(TenantStaffController.class)
                    || beanType.equals(TenantIdpController.class)
                    || beanType.isAnnotationPresent(RequireTenantAdmin.class)
                    || handlerMethod.hasMethodAnnotation(RequireTenantAdmin.class);

            if (isTenantEndpoint) {
                operation.addParametersItem(new Parameter()
                        .in("header")
                        .name("X-Tenant-Code")
                        .description("Tenant facility code (e.g. shop-b, spa-a). Identifies the target tenant context for administration.")
                        .required(true)
                        .schema(new StringSchema().example("shop-b")));
            }
            return operation;
        };
    }
}
