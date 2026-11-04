package com.nextturn.service.tenant;

import com.nextturn.dto.tenant.CreateTenantRequest;
import com.nextturn.dto.tenant.RejectTenantRequest;
import com.nextturn.dto.tenant.TenantMonitoringResponse;
import com.nextturn.dto.tenant.TenantQuotaResponse;
import com.nextturn.dto.tenant.TenantRegistrationRequest;
import com.nextturn.dto.tenant.TenantResponse;
import com.nextturn.dto.tenant.UpdateTenantRequest;
import com.nextturn.dto.tenant.UpdateTenantStatusRequest;
import com.nextturn.entity.CentralTenant;
import com.nextturn.enums.TenantStatus;
import org.springframework.data.domain.Page;

/**
 * Service contract for the Tenant management module.
 */
public interface TenantService {

    TenantResponse createTenant(CreateTenantRequest request);

    TenantResponse registerTenant(TenantRegistrationRequest request);

    TenantResponse updateTenant(String tenantIdentifier, UpdateTenantRequest request);

    TenantResponse suspendTenant(String tenantIdentifier);

    TenantResponse activateTenant(String tenantIdentifier);

    TenantResponse updateTenantStatus(String tenantIdentifier, UpdateTenantStatusRequest request);

    void deleteTenant(String tenantIdentifier);

    TenantResponse approveTenantRegistration(String tenantIdentifier);

    TenantResponse rejectTenantRegistration(String tenantIdentifier, RejectTenantRequest request);

    Page<TenantMonitoringResponse> searchTenants(
            String keyword,
            TenantStatus status,
            int page,
            int limit,
            String sortBy,
            String sortDirection
    );

    long countTodayTickets(String tenantCode);

    boolean checkTenantHistoricalData(String tenantCode);

    TenantResponse getTenant(String tenantIdentifier);

    TenantQuotaResponse getTenantQuota(String tenantIdentifier);

    CentralTenant resolveTenant(String identifier);
}
