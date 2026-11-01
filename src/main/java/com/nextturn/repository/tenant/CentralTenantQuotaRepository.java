package com.nextturn.repository.tenant;

import com.nextturn.entity.CentralTenantQuota;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * Spring Data JPA repository for {@link CentralTenantQuota} in the central master schema.
 */
@Repository
public interface CentralTenantQuotaRepository extends JpaRepository<CentralTenantQuota, String> {

    /**
     * Finds operational quota configuration by tenant ID.
     *
     * @param tenantId unique identifier of the tenant
     * @return an Optional containing the quota entity, or empty if not found
     */
    Optional<CentralTenantQuota> findByTenantId(String tenantId);

    /**
     * Checks if quota record exists for tenant ID.
     *
     * @param tenantId unique identifier of the tenant
     * @return {@code true} if quota exists
     */
    boolean existsByTenantId(String tenantId);

    /**
     * Deletes quota configuration by tenant ID.
     *
     * @param tenantId unique identifier of the tenant
     */
    void deleteByTenantId(String tenantId);
}

