package com.nextturn.repository.notification;

import com.nextturn.entity.TenantNotificationTemplate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Spring Data JPA repository for {@link TenantNotificationTemplate} (REQ-NOTI-04).
 */
@Repository
public interface TenantNotificationTemplateRepository extends JpaRepository<TenantNotificationTemplate, String> {

    Optional<TenantNotificationTemplate> findByTemplateCodeIgnoreCase(String templateCode);

    List<TenantNotificationTemplate> findAllByOrderByTemplateCodeAsc();
}
