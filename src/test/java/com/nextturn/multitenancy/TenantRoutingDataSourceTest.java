package com.nextturn.multitenancy;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TenantRoutingDataSourceTest {

    private final TenantRoutingDataSource routingDataSource = new TenantRoutingDataSource();

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
    }

    @Test
    @DisplayName("Should default to 'nextturn_master' when no tenant context is present")
    void shouldDefaultToMasterSchemaWhenNoTenantContext() {
        TenantContextHolder.clear();
        Object lookupKey = routingDataSource.determineCurrentLookupKey();
        assertThat(lookupKey).isEqualTo(TenantRoutingDataSource.CENTRAL_SCHEMA);
        assertThat(lookupKey).isEqualTo("nextturn_master");
    }

    @Test
    @DisplayName("Should return 'tenant_{tenant_code}' when tenant context is active")
    void shouldReturnTenantSchemaWhenTenantContextIsActive() {
        TenantContextHolder.setTenantCode("hospital_hcm");
        Object lookupKey = routingDataSource.determineCurrentLookupKey();
        assertThat(lookupKey).isEqualTo("tenant_hospital_hcm");
    }

    @Test
    @DisplayName("Should trim and lowercase tenant code in schema name")
    void shouldNormalizeTenantCode() {
        TenantContextHolder.setTenantCode("  CLINIC_DA_NANG  ");
        Object lookupKey = routingDataSource.determineCurrentLookupKey();
        assertThat(lookupKey).isEqualTo("tenant_clinic_da_nang");
    }

    @Test
    @DisplayName("Should revert to 'nextturn_master' when tenant context is cleared")
    void shouldRevertToMasterAfterClear() {
        TenantContextHolder.setTenantCode("branch_hn");
        assertThat(routingDataSource.determineCurrentLookupKey()).isEqualTo("tenant_branch_hn");

        TenantContextHolder.clear();
        assertThat(routingDataSource.determineCurrentLookupKey()).isEqualTo("nextturn_master");
    }
}
