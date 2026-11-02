package com.nextturn.repository.auth;

import com.nextturn.entity.CentralUser;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * Spring Data JPA repository for {@link CentralUser} global public end-users in {@code nextturn_master}.
 */
@Repository
public interface CentralUserRepository extends JpaRepository<CentralUser, String> {

    /**
     * Finds an active, non-deleted central user by case-insensitive email.
     *
     * @param email the user's primary email address
     * @return an Optional containing the matched {@link CentralUser}, or empty if not found
     */
    Optional<CentralUser> findByEmailIgnoreCaseAndIsDeletedFalse(String email);

    /**
     * Finds an active, non-deleted central user by unique phone number.
     *
     * @param phoneNumber the user's phone number
     * @return an Optional containing the matched {@link CentralUser}, or empty if not found
     */
    Optional<CentralUser> findByPhoneNumberAndIsDeletedFalse(String phoneNumber);

    /**
     * Checks if a central user exists with the given email and is not soft-deleted.
     *
     * @param email the email to check
     * @return {@code true} if the user exists, {@code false} otherwise
     */
    boolean existsByEmailIgnoreCaseAndIsDeletedFalse(String email);
}
