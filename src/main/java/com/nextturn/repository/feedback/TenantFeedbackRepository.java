package com.nextturn.repository.feedback;

import com.nextturn.entity.TenantFeedback;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Spring Data JPA repository for {@link TenantFeedback} supporting idempotent submission checks
 * and Admin complaint filtering (REQ-FEEDBACK-01 through REQ-FEEDBACK-04).
 */
@Repository
public interface TenantFeedbackRepository extends JpaRepository<TenantFeedback, String> {

    Optional<TenantFeedback> findByFeedbackToken(String feedbackToken);

    boolean existsByTicketId(String ticketId);

    boolean existsByFeedbackToken(String feedbackToken);

    @Query(
            value = """
                SELECT f FROM TenantFeedback f
                LEFT JOIN FETCH f.ticket t
                LEFT JOIN FETCH f.service s
                LEFT JOIN FETCH f.provider p
                WHERE (:ratingScore IS NULL OR f.ratingScore = :ratingScore)
                  AND (:negativeOnly IS NULL OR :negativeOnly = FALSE OR f.ratingScore <= 2)
                  AND (:serviceId IS NULL OR s.id = :serviceId)
                  AND (:providerId IS NULL OR p.id = :providerId)
                  AND (:fromDateTime IS NULL OR f.createdAt >= :fromDateTime)
                  AND (:toDateTime IS NULL OR f.createdAt <= :toDateTime)
                ORDER BY f.createdAt DESC
            """,
            countQuery = """
                SELECT COUNT(f) FROM TenantFeedback f
                LEFT JOIN f.service s
                LEFT JOIN f.provider p
                WHERE (:ratingScore IS NULL OR f.ratingScore = :ratingScore)
                  AND (:negativeOnly IS NULL OR :negativeOnly = FALSE OR f.ratingScore <= 2)
                  AND (:serviceId IS NULL OR s.id = :serviceId)
                  AND (:providerId IS NULL OR p.id = :providerId)
                  AND (:fromDateTime IS NULL OR f.createdAt >= :fromDateTime)
                  AND (:toDateTime IS NULL OR f.createdAt <= :toDateTime)
            """
    )
    Page<TenantFeedback> searchFeedbacks(
            @Param("ratingScore") Integer ratingScore,
            @Param("negativeOnly") Boolean negativeOnly,
            @Param("serviceId") String serviceId,
            @Param("providerId") String providerId,
            @Param("fromDateTime") LocalDateTime fromDateTime,
            @Param("toDateTime") LocalDateTime toDateTime,
            Pageable pageable
    );

    @Query("""
        SELECT f FROM TenantFeedback f
        LEFT JOIN f.service s
        LEFT JOIN f.provider p
        WHERE (:ratingScore IS NULL OR f.ratingScore = :ratingScore)
          AND (:negativeOnly IS NULL OR :negativeOnly = FALSE OR f.ratingScore <= 2)
          AND (:serviceId IS NULL OR s.id = :serviceId)
          AND (:providerId IS NULL OR p.id = :providerId)
          AND (:fromDateTime IS NULL OR f.createdAt >= :fromDateTime)
          AND (:toDateTime IS NULL OR f.createdAt <= :toDateTime)
    """)
    List<TenantFeedback> findAllForSummary(
            @Param("ratingScore") Integer ratingScore,
            @Param("negativeOnly") Boolean negativeOnly,
            @Param("serviceId") String serviceId,
            @Param("providerId") String providerId,
            @Param("fromDateTime") LocalDateTime fromDateTime,
            @Param("toDateTime") LocalDateTime toDateTime
    );
}
