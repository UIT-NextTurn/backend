package com.nextturn.service.tenant;

import com.nextturn.dto.tenant.CreateTenantRequest;
import com.nextturn.dto.tenant.TenantRegistrationRequest;
import com.nextturn.dto.tenant.TenantResponse;

/**
 * Service contract orchestrating new tenant facility provisioning and public onboarding self-registration
 * with distributed compensating rollback transactions (REQ-TNT-01, REQ-TNT-05).
 */
public interface TenantProvisioningService {

    TenantResponse createTenant(CreateTenantRequest request);

    TenantResponse registerTenant(TenantRegistrationRequest request);

    void validateQuotaInputs(Integer maxStaffs, Integer dailyTicketQuota, Integer maxServices, Integer maxAppointments);
}
