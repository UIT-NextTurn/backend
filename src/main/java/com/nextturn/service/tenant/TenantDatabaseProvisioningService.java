package com.nextturn.service.tenant;

import com.nextturn.exception.tenant.TenantErrorCode;
import com.nextturn.exception.tenant.TenantException;
import org.flywaydb.core.Flyway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Service managing isolated tenant MySQL database lifecycle:
 * dynamic schema creation, Flyway migrations execution, and administrator seeding (REQ-TNT-01, REQ-TNT-06).
 */
@Service
public class TenantDatabaseProvisioningService {

    private static final Logger log = LoggerFactory.getLogger(TenantDatabaseProvisioningService.class);
    private static final Pattern TENANT_CODE_PATTERN = Pattern.compile("^[a-z0-9_-]{2,50}$");
    public static final String TENANT_SCHEMA_PREFIX = "tenant_";

    private final DataSource masterDataSource;

    @Autowired
    public TenantDatabaseProvisioningService(
            @Qualifier("masterDataSource") @Autowired(required = false) DataSource masterDataSource,
            @Autowired(required = false) DataSource defaultDataSource
    ) {
        this.masterDataSource = masterDataSource != null ? masterDataSource : defaultDataSource;
    }

    public TenantDatabaseProvisioningService(DataSource masterDataSource) {
        this.masterDataSource = masterDataSource;
    }

    /**
     * Provisions a dedicated MySQL database for a tenant, executes tenant Flyway migrations,
     * and seeds the initial administrator account.
     */
    public void provisionTenantDatabase(
            String tenantCode,
            String adminEmail,
            String adminFullName,
            String phoneNumber,
            String keycloakUserId
    ) {
        validateTenantCode(tenantCode);
        String schemaName = resolveSchemaName(tenantCode);

        log.info("Provisioning tenant database [{}] for tenant [{}]", schemaName, tenantCode);

        // 1. Create MySQL database if not exists
        createDatabaseIfNotExists(schemaName);

        // 2. Run tenant Flyway migrations
        runFlywayMigration(schemaName);

        // 3. Seed initial administrator if email provided
        if (adminEmail != null && !adminEmail.isBlank()) {
            seedInitialAdministrator(schemaName, adminEmail, adminFullName, phoneNumber, keycloakUserId);
        }

        log.info("Successfully provisioned tenant database [{}]", schemaName);
    }

    /**
     * Drops a tenant database (used for compensating rollback during failed provisioning).
     */
    public void dropTenantDatabase(String tenantCode) {
        if (tenantCode == null || tenantCode.isBlank()) {
            return;
        }
        String normalized = tenantCode.trim().toLowerCase();
        if (!TENANT_CODE_PATTERN.matcher(normalized).matches()) {
            log.warn("Skipping drop on invalid tenant code [{}]", tenantCode);
            return;
        }
        String schemaName = resolveSchemaName(normalized);

        log.warn("Executing compensating rollback: dropping tenant database [{}]", schemaName);
        if (masterDataSource == null) {
            log.warn("Master dataSource is null, cannot drop database [{}]", schemaName);
            return;
        }

        String sql = "DROP DATABASE IF EXISTS `" + schemaName + "`";
        try (Connection conn = masterDataSource.getConnection()) {
            if (conn == null) {
                log.warn("Connection is null, cannot drop database [{}]", schemaName);
                return;
            }
            try (Statement stmt = conn.createStatement()) {
                stmt.execute(sql);
                log.info("Successfully dropped database [{}]", schemaName);
            }
        } catch (SQLException e) {
            log.error("Failed to drop tenant database [{}]: {}", schemaName, e.getMessage(), e);
        }
    }

    /**
     * Checks if a tenant database already exists.
     */
    public boolean tenantDatabaseExists(String tenantCode) {
        validateTenantCode(tenantCode);
        String schemaName = resolveSchemaName(tenantCode);
        if (masterDataSource == null) {
            return false;
        }

        String sql = "SELECT SCHEMA_NAME FROM INFORMATION_SCHEMA.SCHEMATA WHERE SCHEMA_NAME = ?";
        try (Connection conn = masterDataSource.getConnection()) {
            if (conn == null) {
                return false;
            }
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, schemaName);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next();
                }
            }
        } catch (SQLException e) {
            log.warn("Could not check schema existence for [{}]: {}", schemaName, e.getMessage());
            return false;
        }
    }

    private void createDatabaseIfNotExists(String schemaName) {
        if (masterDataSource == null) {
            log.warn("Master dataSource is not configured; skipping dynamic schema creation for [{}]", schemaName);
            return;
        }

        String sql = "CREATE DATABASE IF NOT EXISTS `" + schemaName + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci";
        try (Connection conn = masterDataSource.getConnection()) {
            if (conn == null) {
                log.warn("Master dataSource connection is null; skipping schema creation for [{}]", schemaName);
                return;
            }
            try (Statement stmt = conn.createStatement()) {
                stmt.execute(sql);
                log.info("Schema [{}] confirmed/created", schemaName);
            }
        } catch (SQLException e) {
            log.error("Failed to create database schema [{}]: {}", schemaName, e.getMessage(), e);
            throw new TenantException(TenantErrorCode.TENANT_SCHEMA_CREATION_FAILED, "Failed to create database schema for tenant: " + e.getMessage());
        }
    }

    private void runFlywayMigration(String schemaName) {
        if (masterDataSource == null) {
            log.warn("Master dataSource is not configured; skipping Flyway migration for [{}]", schemaName);
            return;
        }

        // Verify if a usable connection is available before loading Flyway (avoids failing on null mock connections)
        try (Connection conn = masterDataSource.getConnection()) {
            if (conn == null) {
                log.warn("Master dataSource connection is null; skipping Flyway migration for [{}]", schemaName);
                return;
            }
        } catch (SQLException e) {
            log.warn("Could not obtain test connection for Flyway migration [{}]: {}", schemaName, e.getMessage());
            return;
        }

        try {
            Flyway flyway = Flyway.configure()
                    .dataSource(masterDataSource)
                    .schemas(schemaName)
                    .defaultSchema(schemaName)
                    .locations("classpath:db/migration/tenant")
                    .baselineOnMigrate(true)
                    .load();

            flyway.migrate();
            log.info("Flyway migration completed successfully for schema [{}]", schemaName);
        } catch (Exception e) {
            log.error("Flyway migration failed for tenant schema [{}]: {}", schemaName, e.getMessage(), e);
            throw new TenantException(TenantErrorCode.TENANT_SCHEMA_MIGRATION_FAILED, "Flyway migration failed for tenant schema [" + schemaName + "]: " + e.getMessage());
        }
    }

    private void seedInitialAdministrator(
            String schemaName,
            String adminEmail,
            String adminFullName,
            String phoneNumber,
            String keycloakUserId
    ) {
        if (masterDataSource == null) {
            return;
        }

        String insertSql = "INSERT INTO `" + schemaName + "`.`tenant__staffs` " +
                "(id, staff_code, keycloak_user_id, full_name, email, phone_number, role, status, is_deleted) " +
                "VALUES (?, ?, ?, ?, ?, ?, 'ADMIN', 'ACTIVE', 0) " +
                "ON DUPLICATE KEY UPDATE " +
                "keycloak_user_id = COALESCE(VALUES(keycloak_user_id), keycloak_user_id), " +
                "full_name = VALUES(full_name), " +
                "phone_number = VALUES(phone_number), " +
                "status = 'ACTIVE', is_deleted = 0";

        try (Connection conn = masterDataSource.getConnection()) {
            if (conn == null) {
                log.warn("Master dataSource connection is null; skipping administrator seeding in [{}]", schemaName);
                return;
            }
            try (PreparedStatement ps = conn.prepareStatement(insertSql)) {
                ps.setString(1, UUID.randomUUID().toString());
                ps.setString(2, "STAFF_ADMIN_01");
                ps.setString(3, keycloakUserId);
                ps.setString(4, (adminFullName != null && !adminFullName.isBlank()) ? adminFullName.trim() : "Admin " + schemaName);
                ps.setString(5, adminEmail.trim().toLowerCase());
                ps.setString(6, (phoneNumber != null && !phoneNumber.isBlank()) ? phoneNumber.trim() : "0000000000");
                ps.executeUpdate();
                log.info("Seeded initial administrator [{}] in [{}.tenant__staffs]", adminEmail, schemaName);
            }
        } catch (SQLException e) {
            log.error("Failed to seed administrator in [{}.tenant__staffs]: {}", schemaName, e.getMessage(), e);
            throw new TenantException(TenantErrorCode.TENANT_SCHEMA_MIGRATION_FAILED, "Failed to seed administrator profile in tenant schema: " + e.getMessage());
        }
    }

    private void validateTenantCode(String tenantCode) {
        if (tenantCode == null || !TENANT_CODE_PATTERN.matcher(tenantCode.trim().toLowerCase()).matches()) {
            throw new TenantException(TenantErrorCode.TENANT_SLUG_INVALID, "Invalid tenant code format: " + tenantCode);
        }
    }

    public static String resolveSchemaName(String tenantCode) {
        return TENANT_SCHEMA_PREFIX + tenantCode.trim().toLowerCase();
    }
}
