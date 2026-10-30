package com.nextturn.infrastructure.multitenancy;

import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import javax.sql.DataSource;
import java.util.HashMap;
import java.util.Map;

/**
 * Spring configuration providing the dynamic {@link TenantRoutingDataSource} bean,
 * routing database queries to the appropriate tenant or central master schema.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(DataSource.class)
public class TenantDataSourceConfig {

    @Bean
    @Primary
    @ConditionalOnMissingBean(name = "dataSource")
    public DataSource dataSource(DataSourceProperties properties) {
        DataSource masterDataSource = properties.initializeDataSourceBuilder().build();

        TenantRoutingDataSource routingDataSource = new TenantRoutingDataSource();
        routingDataSource.setDefaultTargetDataSource(masterDataSource);

        Map<Object, Object> targetDataSources = new HashMap<>();
        targetDataSources.put(TenantRoutingDataSource.CENTRAL_SCHEMA, masterDataSource);
        routingDataSource.setTargetDataSources(targetDataSources);
        routingDataSource.afterPropertiesSet();

        return routingDataSource;
    }
}
