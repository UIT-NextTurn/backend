package com.nextturn.infrastructure.multitenancy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Collections;
import java.util.Map;

/**
 * Dynamic DataSource router for NextTurn's schema-per-tenant architecture.
 * Determines the schema lookup key dynamically:
 * <ul>
 *     <li>When tenant context exists: {@code tenant_{tenant_code}}</li>
 *     <li>When no tenant context exists: defaults to {@code nextturn_master}</li>
 * </ul>
 */
public class TenantRoutingDataSource extends AbstractRoutingDataSource {

    private static final Logger log = LoggerFactory.getLogger(TenantRoutingDataSource.class);

    public static final String CENTRAL_SCHEMA = "nextturn_master";
    public static final String TENANT_SCHEMA_PREFIX = "tenant_";

    public TenantRoutingDataSource() {
        setLenientFallback(true);
    }

    public TenantRoutingDataSource(DataSource defaultDataSource) {
        this();
        setDefaultTargetDataSource(defaultDataSource);
        setTargetDataSources(Collections.singletonMap(CENTRAL_SCHEMA, defaultDataSource));
        afterPropertiesSet();
    }

    @Override
    public Object determineCurrentLookupKey() {
        String tenantCode = TenantContextHolder.getTenantCode();
        if (tenantCode == null || tenantCode.isBlank()) {
            return CENTRAL_SCHEMA;
        }
        return TENANT_SCHEMA_PREFIX + tenantCode.trim().toLowerCase();
    }

    @Override
    public Connection getConnection() throws SQLException {
        Connection connection = determineTargetDataSource().getConnection();
        applySchema(connection);
        return connection;
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        Connection connection = determineTargetDataSource().getConnection(username, password);
        applySchema(connection);
        return connection;
    }

    private void applySchema(Connection connection) {
        String schema = (String) determineCurrentLookupKey();
        try {
            if (schema != null && !schema.isBlank()) {
                connection.setCatalog(schema);
                log.trace("Switched database catalog/schema to: [{}]", schema);
            }
        } catch (SQLException e) {
            log.debug("Database driver does not support dynamic catalog switching for [{}]: {}", schema, e.getMessage());
        }
    }
}
