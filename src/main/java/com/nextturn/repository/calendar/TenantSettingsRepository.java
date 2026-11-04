package com.nextturn.repository.calendar;

import com.nextturn.entity.TenantSettings;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * Spring Data JPA repository for {@link TenantSettings} managing facility-level advance booking rules.
 */
@Repository
public interface TenantSettingsRepository extends JpaRepository<TenantSettings, String> {

    Optional<TenantSettings> findFirstByOrderByIdAsc();
}

