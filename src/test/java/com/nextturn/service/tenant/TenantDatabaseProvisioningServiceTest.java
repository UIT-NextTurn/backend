package com.nextturn.service.tenant;

import com.nextturn.exception.tenant.TenantErrorCode;
import com.nextturn.exception.tenant.TenantException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TenantDatabaseProvisioningServiceTest {

    @Mock
    private DataSource masterDataSource;

    @Mock
    private Connection connection;

    @Mock
    private Statement statement;

    @Mock
    private PreparedStatement preparedStatement;

    @Mock
    private ResultSet resultSet;

    private TenantDatabaseProvisioningService service;

    @BeforeEach
    void setUp() {
        service = new TenantDatabaseProvisioningService(masterDataSource, masterDataSource);
    }

    @Test
    @DisplayName("resolveSchemaName: returns tenant_ prefix with lowercase code")
    void resolveSchemaName_success() {
        String schema = TenantDatabaseProvisioningService.resolveSchemaName("BV-Tam-Duc");
        assertThat(schema).isEqualTo("tenant_bv-tam-duc");
    }

    @Test
    @DisplayName("provisionTenantDatabase: throws TenantException when tenantCode format is invalid")
    void provisionTenantDatabase_invalidFormat() {
        assertThatThrownBy(() -> service.provisionTenantDatabase("a", "admin@test.com", "Admin", "0912345678", "kc-123"))
                .isInstanceOf(TenantException.class)
                .hasMessageContaining("Invalid tenant code format");
    }

    @Test
    @DisplayName("dropTenantDatabase: executes DROP DATABASE statement successfully")
    void dropTenantDatabase_success() throws SQLException {
        when(masterDataSource.getConnection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);

        service.dropTenantDatabase("bv-tam-duc");

        verify(statement).execute("DROP DATABASE IF EXISTS `tenant_bv-tam-duc`");
    }

    @Test
    @DisplayName("dropTenantDatabase: gracefully ignores null or invalid tenant code")
    void dropTenantDatabase_invalidCode_ignored() {
        service.dropTenantDatabase(null);
        service.dropTenantDatabase("   ");
        service.dropTenantDatabase("?");
    }

    @Test
    @DisplayName("tenantDatabaseExists: returns true when schema found in INFORMATION_SCHEMA")
    void tenantDatabaseExists_found() throws SQLException {
        when(masterDataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenReturn(preparedStatement);
        when(preparedStatement.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true);

        boolean exists = service.tenantDatabaseExists("bv-tam-duc");

        assertThat(exists).isTrue();
        verify(preparedStatement).setString(1, "tenant_bv-tam-duc");
    }

    @Test
    @DisplayName("tenantDatabaseExists: returns false when masterDataSource is null")
    void tenantDatabaseExists_nullDataSource() {
        TenantDatabaseProvisioningService serviceWithoutDs = new TenantDatabaseProvisioningService(null, null);
        assertThat(serviceWithoutDs.tenantDatabaseExists("bv-tam-duc")).isFalse();
    }
}

