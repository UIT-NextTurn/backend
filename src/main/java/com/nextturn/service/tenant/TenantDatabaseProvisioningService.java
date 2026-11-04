package com.nextturn.service.tenant;

import com.nextturn.service.tenant.impl.TenantDatabaseProvisioningServiceImpl;

/**
 * Service contract managing isolated tenant MySQL database lifecycle:
 * dynamic schema creation, Flyway migrations execution, and administrator seeding (REQ-TNT-01, REQ-TNT-06).
 */
public interface TenantDatabaseProvisioningService {

    String TENANT_SCHEMA_PREFIX = TenantDatabaseProvisioningServiceImpl.TENANT_SCHEMA_PREFIX;

    void provisionTenantDatabase(
            String tenantCode,
            String adminEmail,
            String adminFullName,
            String phoneNumber,
            String keycloakUserId
    );

    void dropTenantDatabase(String tenantCode);

    boolean tenantDatabaseExists(String tenantCode);

    static String resolveSchemaName(String tenantCode) {
        return TenantDatabaseProvisioningServiceImpl.resolveSchemaName(tenantCode);
    }
}
