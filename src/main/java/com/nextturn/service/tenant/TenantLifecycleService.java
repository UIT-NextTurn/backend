package com.nextturn.service.tenant;

import com.nextturn.dto.tenant.RejectTenantRequest;
import com.nextturn.dto.tenant.TenantResponse;
import com.nextturn.dto.tenant.UpdateTenantStatusRequest;
import com.nextturn.entity.CentralTenant;

/**
 * Service contract managing tenant lifecycle state transitions (SUSPENDED, ACTIVE, REJECTED),
 * registration approval/rejection workflows, and safe soft-deletion (REQ-TNT-03, REQ-TNT-06).
 */
public interface TenantLifecycleService {

    TenantResponse suspendTenant(CentralTenant tenant);

    TenantResponse activateTenant(CentralTenant tenant);

    TenantResponse updateTenantStatus(CentralTenant tenant, UpdateTenantStatusRequest request);

    TenantResponse approveTenantRegistration(CentralTenant tenant);

    TenantResponse rejectTenantRegistration(CentralTenant tenant, RejectTenantRequest request);

    void deleteTenant(CentralTenant tenant);
}
