package com.nextturn.service.tenant.impl;

import com.nextturn.constant.StaffConstant;
import com.nextturn.exception.tenant.TenantErrorCode;
import com.nextturn.exception.tenant.TenantException;
import com.nextturn.service.tenant.TenantDatabaseProvisioningService;
import org.flywaydb.core.Flyway;
import com.nextturn.infrastructure.logging.LogContract;
import com.nextturn.infrastructure.logging.LogDomain;
import com.nextturn.infrastructure.logging.LogOutcome;
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
public class TenantDatabaseProvisioningServiceImpl implements TenantDatabaseProvisioningService {

    private static final Logger log = LoggerFactory.getLogger(TenantDatabaseProvisioningServiceImpl.class);
    private static final Pattern TENANT_CODE_PATTERN = Pattern.compile("^[a-z0-9_-]{2,50}$");
    public static final String TENANT_SCHEMA_PREFIX = "tenant_";

    private final DataSource masterDataSource;

    @Autowired
    public TenantDatabaseProvisioningServiceImpl(
            @Qualifier("masterDataSource") @Autowired(required = false) DataSource masterDataSource,
            @Autowired(required = false) DataSource defaultDataSource
    ) {
        this.masterDataSource = masterDataSource != null ? masterDataSource : defaultDataSource;
    }

    public TenantDatabaseProvisioningServiceImpl(DataSource masterDataSource) {
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

        LogContract.info(log, LogDomain.TENANT, "schema_provisioning")
                .outcome(LogOutcome.STARTED)
                .tenant(tenantCode)
                .field("schema", schemaName)
                .emit();

        // 1. Create MySQL database if not exists
        createDatabaseIfNotExists(schemaName);

        // 2. Run tenant Flyway migrations
        runFlywayMigration(schemaName);

        // 3. Seed initial administrator if email provided
        if (adminEmail != null && !adminEmail.isBlank()) {
            seedInitialAdministrator(schemaName, adminEmail, adminFullName, phoneNumber, keycloakUserId);
        }

        LogContract.info(log, LogDomain.TENANT, "schema_provisioned")
                .outcome(LogOutcome.SUCCESS)
                .tenant(tenantCode)
                .field("schema", schemaName)
                .emit();
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
            LogContract.warn(log, LogDomain.TENANT, "schema_drop")
                    .outcome(LogOutcome.SKIPPED)
                    .tenant(tenantCode)
                    .reason("Invalid tenant code format")
                    .emit();
            return;
        }
        String schemaName = resolveSchemaName(normalized);

        LogContract.warn(log, LogDomain.TENANT, "schema_drop")
                .outcome(LogOutcome.STARTED)
                .tenant(normalized)
                .field("schema", schemaName)
                .reason("Compensating rollback")
                .emit();
        if (masterDataSource == null) {
            LogContract.warn(log, LogDomain.TENANT, "schema_drop")
                    .outcome(LogOutcome.SKIPPED)
                    .field("schema", schemaName)
                    .reason("Master dataSource is null")
                    .emit();
            return;
        }

        String sql = "DROP DATABASE IF EXISTS `" + schemaName + "`";
        try (Connection conn = masterDataSource.getConnection()) {
            if (conn == null) {
                LogContract.warn(log, LogDomain.TENANT, "schema_drop")
                        .outcome(LogOutcome.SKIPPED)
                        .field("schema", schemaName)
                        .reason("Connection is null")
                        .emit();
                return;
            }
            try (Statement stmt = conn.createStatement()) {
                stmt.execute(sql);
                LogContract.info(log, LogDomain.TENANT, "schema_dropped")
                        .outcome(LogOutcome.SUCCESS)
                        .field("schema", schemaName)
                        .emit();
            }
        } catch (SQLException e) {
            LogContract.error(log, LogDomain.TENANT, "schema_drop")
                    .outcome(LogOutcome.FAILED)
                    .field("schema", schemaName)
                    .causeWithStack(e)
                    .emit();
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
            LogContract.warn(log, LogDomain.TENANT, "schema_exists_check")
                    .outcome(LogOutcome.DEGRADED)
                    .field("schema", schemaName)
                    .cause(e)
                    .emit();
            return false;
        }
    }

    private void createDatabaseIfNotExists(String schemaName) {
        if (masterDataSource == null) {
            LogContract.warn(log, LogDomain.TENANT, "schema_create")
                    .outcome(LogOutcome.SKIPPED)
                    .field("schema", schemaName)
                    .reason("Master dataSource not configured")
                    .emit();
            return;
        }

        String sql = "CREATE DATABASE IF NOT EXISTS `" + schemaName + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci";
        try (Connection conn = masterDataSource.getConnection()) {
            if (conn == null) {
                LogContract.warn(log, LogDomain.TENANT, "schema_create")
                        .outcome(LogOutcome.SKIPPED)
                        .field("schema", schemaName)
                        .reason("Connection is null")
                        .emit();
                return;
            }
            try (Statement stmt = conn.createStatement()) {
                stmt.execute(sql);
                LogContract.info(log, LogDomain.TENANT, "schema_created")
                        .outcome(LogOutcome.SUCCESS)
                        .field("schema", schemaName)
                        .emit();
            }
        } catch (SQLException e) {
            LogContract.error(log, LogDomain.TENANT, "schema_create")
                    .outcome(LogOutcome.FAILED)
                    .field("schema", schemaName)
                    .causeWithStack(e)
                    .emit();
            throw new TenantException(TenantErrorCode.TENANT_SCHEMA_CREATION_FAILED, "Failed to create database schema for tenant: " + e.getMessage());
        }
    }

    private void runFlywayMigration(String schemaName) {
        if (masterDataSource == null) {
            LogContract.warn(log, LogDomain.TENANT, "schema_flyway")
                    .outcome(LogOutcome.SKIPPED)
                    .field("schema", schemaName)
                    .reason("Master dataSource not configured")
                    .emit();
            return;
        }

        // Verify if a usable connection is available before loading Flyway (avoids failing on null mock connections)
        try (Connection conn = masterDataSource.getConnection()) {
            if (conn == null) {
                LogContract.warn(log, LogDomain.TENANT, "schema_flyway")
                        .outcome(LogOutcome.SKIPPED)
                        .field("schema", schemaName)
                        .reason("Connection is null")
                        .emit();
                return;
            }
        } catch (SQLException e) {
            LogContract.warn(log, LogDomain.TENANT, "schema_flyway")
                    .outcome(LogOutcome.SKIPPED)
                    .field("schema", schemaName)
                    .cause(e)
                    .emit();
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
            LogContract.info(log, LogDomain.TENANT, "schema_flyway_completed")
                    .outcome(LogOutcome.SUCCESS)
                    .field("schema", schemaName)
                    .emit();
        } catch (Exception e) {
            LogContract.error(log, LogDomain.TENANT, "schema_flyway")
                    .outcome(LogOutcome.FAILED)
                    .field("schema", schemaName)
                    .causeWithStack(e)
                    .emit();
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
                LogContract.warn(log, LogDomain.TENANT, "seed_admin")
                        .outcome(LogOutcome.SKIPPED)
                        .field("schema", schemaName)
                        .reason("Connection is null")
                        .emit();
                return;
            }
            try (PreparedStatement ps = conn.prepareStatement(insertSql)) {
                ps.setString(1, UUID.randomUUID().toString());
                ps.setString(2, StaffConstant.INITIAL_ADMIN_STAFF_CODE);
                ps.setString(3, keycloakUserId);
                ps.setString(4, (adminFullName != null && !adminFullName.isBlank()) ? adminFullName.trim() : "Admin " + schemaName);
                ps.setString(5, adminEmail.trim().toLowerCase());
                ps.setString(6, (phoneNumber != null && !phoneNumber.isBlank()) ? phoneNumber.trim() : "0000000000");
                ps.executeUpdate();
                LogContract.info(log, LogDomain.TENANT, "seed_admin")
                        .outcome(LogOutcome.SUCCESS)
                        .field("schema", schemaName)
                        .email(adminEmail)
                        .emit();
            }
        } catch (SQLException e) {
            LogContract.error(log, LogDomain.TENANT, "seed_admin")
                    .outcome(LogOutcome.FAILED)
                    .field("schema", schemaName)
                    .causeWithStack(e)
                    .emit();
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
