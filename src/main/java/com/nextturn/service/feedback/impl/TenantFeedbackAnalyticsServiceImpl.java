package com.nextturn.service.feedback.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nextturn.constant.ValkeyKeyConstant;
import com.nextturn.dto.feedback.FeedbackSummaryResponse;
import com.nextturn.entity.TenantFeedback;
import com.nextturn.infrastructure.logging.LogContract;
import com.nextturn.infrastructure.logging.LogDomain;
import com.nextturn.infrastructure.logging.LogOutcome;
import com.nextturn.infrastructure.multitenancy.TenantContextHolder;
import com.nextturn.service.feedback.TenantFeedbackAnalyticsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Default implementation of {@link TenantFeedbackAnalyticsService} calculating CSAT KPIs,
 * maintaining Valkey dashboard caches, and atomically syncing daily service/staff analytics tables
 * (REQ-FEEDBACK-03).
 */
@Service
public class TenantFeedbackAnalyticsServiceImpl implements TenantFeedbackAnalyticsService {

    private static final Logger log = LoggerFactory.getLogger(TenantFeedbackAnalyticsServiceImpl.class);
    private static final Duration CSAT_CACHE_TTL = Duration.ofMinutes(15);

    private final DataSource dataSource;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    @Autowired
    public TenantFeedbackAnalyticsServiceImpl(
            @Autowired(required = false) DataSource dataSource,
            @Autowired(required = false) StringRedisTemplate redisTemplate,
            @Autowired(required = false) ObjectMapper objectMapper
    ) {
        this.dataSource = dataSource;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper().findAndRegisterModules();
    }

    @Override
    public void recordFeedbackMetrics(String tenantCode, TenantFeedback feedback) {
        if (feedback == null || feedback.getRatingScore() == null) {
            return;
        }
        evictCachedSummary(tenantCode);
        syncDailyRollupTables(tenantCode, feedback);
    }

    @Override
    public FeedbackSummaryResponse computeSummary(List<TenantFeedback> feedbacks) {
        Map<Integer, Long> distribution = new LinkedHashMap<>();
        for (int star = 1; star <= 5; star++) {
            distribution.put(star, 0L);
        }

        if (feedbacks == null || feedbacks.isEmpty()) {
            return FeedbackSummaryResponse.builder()
                    .totalFeedbacks(0L)
                    .averageRating(0.0d)
                    .csatPercentage(0.0d)
                    .negativeCount(0L)
                    .ratingDistribution(distribution)
                    .build();
        }

        long total = 0L;
        long sumScore = 0L;
        long positiveCount = 0L; // 4 and 5 stars
        long negativeCount = 0L; // 1 and 2 stars

        for (TenantFeedback fb : feedbacks) {
            Integer score = fb.getRatingScore();
            if (score == null || score < 1 || score > 5) {
                continue;
            }
            total++;
            sumScore += score;
            distribution.put(score, distribution.get(score) + 1L);
            if (score >= 4) {
                positiveCount++;
            } else if (score <= 2) {
                negativeCount++;
            }
        }

        double average = total > 0 ? roundOneDecimal((double) sumScore / total) : 0.0d;
        double csat = total > 0 ? roundOneDecimal(((double) positiveCount / total) * 100.0d) : 0.0d;

        return FeedbackSummaryResponse.builder()
                .totalFeedbacks(total)
                .averageRating(average)
                .csatPercentage(csat)
                .negativeCount(negativeCount)
                .ratingDistribution(distribution)
                .build();
    }

    @Override
    public FeedbackSummaryResponse getCachedSummary(String tenantCode) {
        if (redisTemplate == null || tenantCode == null || tenantCode.isBlank()) {
            return null;
        }
        try {
            String json = redisTemplate.opsForValue().get(ValkeyKeyConstant.feedbackCsatKey(tenantCode));
            if (json != null && !json.isBlank()) {
                return objectMapper.readValue(json, FeedbackSummaryResponse.class);
            }
        } catch (Exception ex) {
            LogContract.warn(log, LogDomain.FEEDBACK, "csat_cache_read")
                    .outcome(LogOutcome.DEGRADED)
                    .tenant(tenantCode)
                    .cause(ex)
                    .emit();
        }
        return null;
    }

    @Override
    public void cacheSummary(String tenantCode, FeedbackSummaryResponse summary) {
        if (redisTemplate == null || tenantCode == null || tenantCode.isBlank() || summary == null) {
            return;
        }
        try {
            String json = objectMapper.writeValueAsString(summary);
            redisTemplate.opsForValue().set(ValkeyKeyConstant.feedbackCsatKey(tenantCode), json, CSAT_CACHE_TTL);
        } catch (Exception ex) {
            LogContract.warn(log, LogDomain.FEEDBACK, "csat_cache_write")
                    .outcome(LogOutcome.DEGRADED)
                    .tenant(tenantCode)
                    .cause(ex)
                    .emit();
        }
    }

    private void evictCachedSummary(String tenantCode) {
        if (redisTemplate == null || tenantCode == null || tenantCode.isBlank()) {
            return;
        }
        try {
            redisTemplate.delete(ValkeyKeyConstant.feedbackCsatKey(tenantCode));
        } catch (Exception ex) {
            LogContract.warn(log, LogDomain.FEEDBACK, "csat_cache_evict")
                    .outcome(LogOutcome.DEGRADED)
                    .tenant(tenantCode)
                    .cause(ex)
                    .emit();
        }
    }

    private void syncDailyRollupTables(String tenantCode, TenantFeedback feedback) {
        if (dataSource == null || feedback.getService() == null) {
            return;
        }
        int score = feedback.getRatingScore();
        if (score < 1 || score > 5) {
            return;
        }
        LocalDate reportDate = feedback.getCreatedAt() != null
                ? feedback.getCreatedAt().toLocalDate()
                : LocalDate.now();
        String serviceId = feedback.getService().getId();
        String providerId = feedback.getProvider() != null ? feedback.getProvider().getId() : null;

        String prevTenant = TenantContextHolder.getTenantCode();
        try {
            if (tenantCode != null && !tenantCode.isBlank()) {
                TenantContextHolder.setTenantCode(tenantCode);
            }
            try (Connection conn = dataSource.getConnection()) {
                if (conn == null) {
                    return;
                }
                upsertDailyServiceRating(conn, serviceId, reportDate, score);
                if (providerId != null && !providerId.isBlank()) {
                    upsertDailyStaffRating(conn, providerId, serviceId, reportDate, score);
                }
            }
        } catch (Exception ex) {
            LogContract.warn(log, LogDomain.FEEDBACK, "analytics_rollup_sync")
                    .outcome(LogOutcome.DEGRADED)
                    .tenant(tenantCode)
                    .entity("feedback", feedback.getId())
                    .cause(ex)
                    .emit();
        } finally {
            if (prevTenant != null) {
                TenantContextHolder.setTenantCode(prevTenant);
            } else {
                TenantContextHolder.clear();
            }
        }
    }

    private void upsertDailyServiceRating(Connection conn, String serviceId, LocalDate reportDate, int score) throws Exception {
        String col = "rating_" + score + "_count";
        String sql = "INSERT INTO tenant__analytics_daily_services "
                + "(id, service_id, report_date, " + col + ", feedback_count) "
                + "VALUES (?, ?, ?, 1, 1) "
                + "ON DUPLICATE KEY UPDATE "
                + col + " = " + col + " + 1, "
                + "feedback_count = feedback_count + 1";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, UUID.randomUUID().toString());
            ps.setString(2, serviceId);
            ps.setDate(3, Date.valueOf(reportDate));
            ps.executeUpdate();
        }
    }

    private void upsertDailyStaffRating(
            Connection conn,
            String staffId,
            String serviceId,
            LocalDate reportDate,
            int score
    ) throws Exception {
        int positiveInc = score >= 4 ? 1 : 0;
        String sql = "INSERT INTO tenant__analytics_daily_staffs "
                + "(id, staff_id, service_id, report_date, positive_feedback_count, total_feedback_count) "
                + "VALUES (?, ?, ?, ?, ?, 1) "
                + "ON DUPLICATE KEY UPDATE "
                + "positive_feedback_count = positive_feedback_count + ?, "
                + "total_feedback_count = total_feedback_count + 1";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, UUID.randomUUID().toString());
            ps.setString(2, staffId);
            ps.setString(3, serviceId);
            ps.setDate(4, Date.valueOf(reportDate));
            ps.setInt(5, positiveInc);
            ps.setInt(6, positiveInc);
            ps.executeUpdate();
        }
    }

    private double roundOneDecimal(double val) {
        return Math.round(val * 10.0d) / 10.0d;
    }
}
