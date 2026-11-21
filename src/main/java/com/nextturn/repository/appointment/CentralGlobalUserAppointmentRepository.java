package com.nextturn.repository.appointment;

import com.nextturn.entity.CentralGlobalUserAppointment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Spring Data JPA repository for {@link CentralGlobalUserAppointment} in {@code nextturn_master} (REQ-APT-05).
 */
@Repository
public interface CentralGlobalUserAppointmentRepository extends JpaRepository<CentralGlobalUserAppointment, String> {

    List<CentralGlobalUserAppointment> findByUserIdOrderByCreatedAtDesc(String userId);

    List<CentralGlobalUserAppointment> findByUserIdAndTenantCodeIgnoreCaseOrderByCreatedAtDesc(
            String userId,
            String tenantCode
    );

    Optional<CentralGlobalUserAppointment> findByTenantCodeIgnoreCaseAndAppointmentId(
            String tenantCode,
            String appointmentId
    );
}
