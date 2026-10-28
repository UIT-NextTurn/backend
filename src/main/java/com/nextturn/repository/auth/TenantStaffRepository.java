package com.nextturn.repository.auth;

import com.nextturn.entity.TenantStaff;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * Spring Data JPA repository for {@link TenantStaff} operating on the active tenant schema.
 */
@Repository
public interface TenantStaffRepository extends JpaRepository<TenantStaff, String> {

    Optional<TenantStaff> findByIdAndIsDeletedFalse(String id);

    Optional<TenantStaff> findByEmailAndIsDeletedFalse(String email);

    Optional<TenantStaff> findByStaffCodeAndIsDeletedFalse(String staffCode);

    Optional<TenantStaff> findByPhoneNumberAndIsDeletedFalse(String phoneNumber);

    boolean existsByEmailAndIsDeletedFalse(String email);

    boolean existsByStaffCodeAndIsDeletedFalse(String staffCode);

    boolean existsByPhoneNumberAndIsDeletedFalse(String phoneNumber);

    Page<TenantStaff> findAllByIsDeletedFalse(Pageable pageable);
}
