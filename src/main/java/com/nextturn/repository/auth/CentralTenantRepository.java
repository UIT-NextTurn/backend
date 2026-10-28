package com.nextturn.repository.auth;

import com.nextturn.entity.CentralTenant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * Spring Data JPA repository for {@link CentralTenant} management in the master schema.
 */
@Repository
public interface CentralTenantRepository extends JpaRepository<CentralTenant, String> {

    /**
     * Finds an active, non-deleted tenant by case-insensitive tenant code.
     *
     * @param tenantCode the unique alphanumeric tenant code (e.g. "clinic_sai_gon")
     * @return an Optional containing the matched {@link CentralTenant}, or empty if not found
     */
    Optional<CentralTenant> findByTenantCodeIgnoreCaseAndIsDeletedFalse(String tenantCode);

    /**
     * Checks if a tenant exists with the given code and is not soft-deleted.
     *
     * @param tenantCode the tenant code to check
     * @return {@code true} if the tenant exists, {@code false} otherwise
     */
    boolean existsByTenantCodeIgnoreCaseAndIsDeletedFalse(String tenantCode);
}
