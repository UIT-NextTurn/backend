package com.nextturn.repository.checkin;

import com.nextturn.entity.TenantReceptionPoint;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Spring Data JPA repository for {@link TenantReceptionPoint} managing lobby/counter QR intake points.
 */
@Repository
public interface TenantReceptionPointRepository extends JpaRepository<TenantReceptionPoint, String> {

    @Query("""
        SELECT rp FROM TenantReceptionPoint rp
        LEFT JOIN FETCH rp.service
        WHERE rp.id = :id AND rp.isDeleted = false
    """)
    Optional<TenantReceptionPoint> findByIdWithServiceAndIsDeletedFalse(@Param("id") String id);

    @Query("""
        SELECT rp FROM TenantReceptionPoint rp
        LEFT JOIN FETCH rp.service
        WHERE rp.isDeleted = false
        ORDER BY rp.createdAt DESC
    """)
    List<TenantReceptionPoint> findAllActiveOrderByCreatedAtDesc();

    @Query("""
        SELECT rp FROM TenantReceptionPoint rp
        LEFT JOIN FETCH rp.service
        WHERE rp.staticToken = :staticToken AND rp.isDeleted = false
    """)
    Optional<TenantReceptionPoint> findByStaticTokenAndIsDeletedFalse(@Param("staticToken") String staticToken);

    boolean existsByPointCodeIgnoreCaseAndIsDeletedFalse(String pointCode);
}
