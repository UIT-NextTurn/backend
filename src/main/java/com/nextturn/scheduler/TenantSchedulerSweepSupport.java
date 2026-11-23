package com.nextturn.scheduler;

import com.nextturn.entity.CentralTenant;
import com.nextturn.infrastructure.logging.LogContract;
import com.nextturn.infrastructure.logging.LogDomain;
import com.nextturn.infrastructure.logging.LogOutcome;
import com.nextturn.infrastructure.multitenancy.TenantContextHolder;
import com.nextturn.repository.auth.CentralTenantRepository;
import org.slf4j.Logger;

import java.util.List;
import java.util.function.ToIntFunction;

/**
 * Shared multi-tenant sweep runner for background schedulers, ensuring uniform
 * MDC {@code traceId}/{@code tenantCode} correlation and structured {@link LogContract} audit emission.
 */
final class TenantSchedulerSweepSupport {

    private TenantSchedulerSweepSupport() {
        // Utility class
    }

    static int runAcrossActiveTenants(
            Logger logger,
            String jobEvent,
            CentralTenantRepository centralTenantRepository,
            ToIntFunction<String> perTenantTask
    ) {
        if (centralTenantRepository == null) {
            LogContract.debug(logger, LogDomain.SCHEDULER, jobEvent)
                    .outcome(LogOutcome.SKIPPED)
                    .reason("CentralTenantRepository not available")
                    .emit();
            return 0;
        }

        LogContract.bindNewTraceId();
        try {
            List<CentralTenant> tenants;
            try {
                tenants = centralTenantRepository.findAll().stream()
                        .filter(t -> !Boolean.TRUE.equals(t.getIsDeleted()))
                        .toList();
            } catch (Exception ex) {
                LogContract.warn(logger, LogDomain.SCHEDULER, jobEvent + "_fetch_tenants")
                        .outcome(LogOutcome.FAILED)
                        .cause(ex)
                        .emit();
                return 0;
            }

            int totalAffected = 0;
            for (CentralTenant tenant : tenants) {
                String tenantCode = tenant.getTenantCode();
                if (tenantCode == null || tenantCode.isBlank()) {
                    continue;
                }
                String cleanTenant = tenantCode.trim();
                try {
                    TenantContextHolder.setTenantCode(cleanTenant);
                    int affected = perTenantTask.applyAsInt(cleanTenant);
                    if (affected > 0) {
                        totalAffected += affected;
                        LogContract.info(logger, LogDomain.SCHEDULER, jobEvent + "_tenant")
                                .outcome(LogOutcome.SUCCESS)
                                .tenant(cleanTenant)
                                .field("affected", affected)
                                .emit();
                    }
                } catch (Exception ex) {
                    LogContract.warn(logger, LogDomain.SCHEDULER, jobEvent + "_tenant")
                            .outcome(LogOutcome.FAILED)
                            .tenant(cleanTenant)
                            .cause(ex)
                            .emit();
                } finally {
                    TenantContextHolder.clear();
                }
            }

            if (totalAffected > 0) {
                LogContract.info(logger, LogDomain.SCHEDULER, jobEvent + "_sweep")
                        .outcome(LogOutcome.SUCCESS)
                        .field("totalAffected", totalAffected)
                        .field("tenantCount", tenants.size())
                        .emit();
            }
            return totalAffected;
        } finally {
            LogContract.clearTraceId();
        }
    }
}
