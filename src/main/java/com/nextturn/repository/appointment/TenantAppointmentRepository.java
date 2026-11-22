package com.nextturn.repository.appointment;

import com.nextturn.entity.TenantAppointment;
import com.nextturn.enums.AppointmentStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Spring Data JPA repository for {@link TenantAppointment} in the tenant-isolated schema.
 */
@Repository
public interface TenantAppointmentRepository extends JpaRepository<TenantAppointment, String> {

    @Query("""
        SELECT a FROM TenantAppointment a
        LEFT JOIN FETCH a.service
        LEFT JOIN FETCH a.slot
        WHERE a.id = :id
    """)
    Optional<TenantAppointment> findByIdWithDetails(@Param("id") String id);

    @Query("""
        SELECT a FROM TenantAppointment a
        LEFT JOIN FETCH a.service
        LEFT JOIN FETCH a.slot
        WHERE LOWER(a.appointmentCode) = LOWER(:appointmentCode)
          AND a.customerPhone = :customerPhone
    """)
    Optional<TenantAppointment> findByAppointmentCodeAndCustomerPhone(
            @Param("appointmentCode") String appointmentCode,
            @Param("customerPhone") String customerPhone
    );

    @Query("""
        SELECT a FROM TenantAppointment a
        LEFT JOIN FETCH a.service
        LEFT JOIN FETCH a.slot
        WHERE LOWER(a.appointmentCode) = LOWER(:appointmentCode)
    """)
    Optional<TenantAppointment> findByAppointmentCodeIgnoreCase(@Param("appointmentCode") String appointmentCode);

    @Query("""
        SELECT a FROM TenantAppointment a
        LEFT JOIN FETCH a.service
        LEFT JOIN FETCH a.slot
        WHERE a.qrToken = :qrToken
    """)
    Optional<TenantAppointment> findByQrToken(@Param("qrToken") String qrToken);

    @Query("""
        SELECT a FROM TenantAppointment a
        LEFT JOIN FETCH a.service
        LEFT JOIN FETCH a.slot s
        WHERE a.userId = :userId
        ORDER BY s.workDate DESC, s.slotStart DESC, a.createdAt DESC
    """)
    List<TenantAppointment> findByUserIdWithDetails(@Param("userId") String userId);

    @Query("""
        SELECT a FROM TenantAppointment a
        LEFT JOIN FETCH a.service
        LEFT JOIN FETCH a.slot s
        ORDER BY s.workDate DESC, s.slotStart DESC, a.createdAt DESC
    """)
    List<TenantAppointment> findAllWithDetails();

    boolean existsByAppointmentCodeIgnoreCase(String appointmentCode);

    long countByAppointmentCodeStartingWith(String prefix);

    boolean existsBySlotIdAndCustomerPhoneAndStatusIn(
            String slotId,
            String customerPhone,
            List<AppointmentStatus> statuses
    );
}
