package com.nextturn.repository.auth;

import com.nextturn.entity.CentralAllowedDomain;
import com.nextturn.entity.CentralAllowedDomainId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Spring Data JPA repository for {@link CentralAllowedDomain} management in the master schema.
 * Supports whitelisted domain verification for embedded widget authentication (REQ-AUTH-06).
 */
@Repository
public interface CentralAllowedDomainRepository extends JpaRepository<CentralAllowedDomain, CentralAllowedDomainId> {

    /**
     * Finds all whitelisted domains registered for a specific tenant facility.
     *
     * @param tenantId the tenant ID
     * @return list of allowed domain records
     */
    List<CentralAllowedDomain> findByTenantId(String tenantId);

    /**
     * Checks if a domain is explicitly whitelisted for a tenant facility.
     *
     * @param tenantId   the tenant ID
     * @param domainName domain name (case-insensitive)
     * @return {@code true} if allowed, {@code false} otherwise
     */
    boolean existsByTenantIdAndDomainNameIgnoreCase(String tenantId, String domainName);
}
