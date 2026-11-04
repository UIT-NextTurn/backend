package com.nextturn.repository.staff;

import com.nextturn.entity.TenantStaffServiceAssignment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Spring Data JPA repository for {@link TenantStaffServiceAssignment} managing staff-to-service assignments.
 */
@Repository
public interface TenantStaffServiceRepository extends JpaRepository<TenantStaffServiceAssignment, String> {

    @Query("SELECT ssa FROM TenantStaffServiceAssignment ssa JOIN FETCH ssa.service s WHERE ssa.staff.id = :staffId AND s.isDeleted = false")
    List<TenantStaffServiceAssignment> findByStaffIdWithService(@Param("staffId") String staffId);

    @Query("SELECT ssa FROM TenantStaffServiceAssignment ssa JOIN FETCH ssa.staff st WHERE ssa.service.id = :serviceId AND st.isDeleted = false")
    List<TenantStaffServiceAssignment> findByServiceIdWithStaff(@Param("serviceId") String serviceId);

    List<TenantStaffServiceAssignment> findByStaffId(String staffId);

    List<TenantStaffServiceAssignment> findByServiceId(String serviceId);

    @Modifying
    @Query("DELETE FROM TenantStaffServiceAssignment ssa WHERE ssa.staff.id = :staffId")
    void deleteByStaffId(@Param("staffId") String staffId);

    @Query("SELECT ssa.service.id FROM TenantStaffServiceAssignment ssa WHERE ssa.staff.id = :staffId")
    List<String> findServiceIdsByStaffId(@Param("staffId") String staffId);

    @Query("SELECT ssa.staff.id FROM TenantStaffServiceAssignment ssa WHERE ssa.service.id = :serviceId")
    List<String> findStaffIdsByServiceId(@Param("serviceId") String serviceId);

    boolean existsByStaffIdAndServiceId(String staffId, String serviceId);

    long countByServiceId(String serviceId);
}
