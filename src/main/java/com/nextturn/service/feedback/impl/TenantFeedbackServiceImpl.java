package com.nextturn.service.feedback.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nextturn.constant.ValkeyKeyConstant;
import com.nextturn.dto.feedback.FeedbackFormInitResponse;
import com.nextturn.dto.feedback.FeedbackResponse;
import com.nextturn.dto.feedback.FeedbackSummaryResponse;
import com.nextturn.dto.feedback.SubmitFeedbackRequest;
import com.nextturn.dto.notification.NotificationTemplateResponse;
import com.nextturn.entity.CentralTenant;
import com.nextturn.entity.TenantAppointment;
import com.nextturn.entity.TenantFeedback;
import com.nextturn.entity.TenantQueueTicket;
import com.nextturn.entity.TenantService;
import com.nextturn.entity.TenantStaff;
import com.nextturn.enums.NotificationChannel;
import com.nextturn.enums.NotificationDeliveryStatus;
import com.nextturn.enums.NotificationTemplateCode;
import com.nextturn.enums.TicketStatus;
import com.nextturn.exception.feedback.FeedbackErrorCode;
import com.nextturn.exception.feedback.FeedbackException;
import com.nextturn.infrastructure.logging.LogContract;
import com.nextturn.infrastructure.logging.LogDomain;
import com.nextturn.infrastructure.logging.LogOutcome;
import com.nextturn.infrastructure.multitenancy.TenantContextHolder;
import com.nextturn.repository.auth.CentralTenantRepository;
import com.nextturn.repository.feedback.TenantFeedbackRepository;
import com.nextturn.repository.ticket.TenantQueueTicketRepository;
import com.nextturn.service.BaseService;
import com.nextturn.service.feedback.TenantFeedbackAnalyticsService;
import com.nextturn.service.feedback.TenantFeedbackService;
import com.nextturn.service.notification.EmailService;
import com.nextturn.service.notification.TenantNotificationLogService;
import com.nextturn.service.notification.TenantNotificationTemplateService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Default implementation of {@link TenantFeedbackService} fulfilling SRS REQ-FEEDBACK-01..04:
 * 24-hour secure token issuance, one-time feedback validation, XSS/HTML comment sanitization,
 * Valkey/MySQL CSAT aggregation, and Admin complaint triage queries.
 */
@Service
public class TenantFeedbackServiceImpl extends BaseService implements TenantFeedbackService {

    private static final Logger log = LoggerFactory.getLogger(TenantFeedbackServiceImpl.class);
    private static final long TOKEN_TTL_HOURS = 24L;
    private static final int MAX_COMMENT_LENGTH = 500;
    private static final String SURVEY_HTML_TEMPLATE = "templates/email/feedback-survey.html";
    private static final List<String> DEFAULT_CRITERIA_TAGS = List.of(
            "Thái độ phục vụ",
            "Thời gian chờ",
            "Không gian cơ sở"
    );
    private static final Pattern SCRIPT_BLOCK_PATTERN =
            Pattern.compile("(?is)<\\s*(script|style|iframe|object|embed)[^>]*>.*?<\\s*/\\s*\\1\\s*>");
    private static final Pattern HTML_TAG_PATTERN = Pattern.compile("<[^>]*>");
    private static final Pattern EVENT_HANDLER_PATTERN =
            Pattern.compile("(?i)(javascript:|vbscript:|on\\w+\\s*=)");
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final TenantQueueTicketRepository ticketRepository;
    private final TenantFeedbackRepository feedbackRepository;
    private final TenantFeedbackAnalyticsService analyticsService;
    private final TenantNotificationTemplateService templateService;
    private final TenantNotificationLogService notificationLogService;
    private final EmailService emailService;
    private final CentralTenantRepository centralTenantRepository;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final Map<String, String> htmlCache = new ConcurrentHashMap<>();

    @Value("${app.frontend.base-url:http://localhost:3000}")
    private String frontendBaseUrl = "http://localhost:3000";

    @Autowired
    public TenantFeedbackServiceImpl(
            TenantQueueTicketRepository ticketRepository,
            TenantFeedbackRepository feedbackRepository,
            TenantFeedbackAnalyticsService analyticsService,
            @Autowired(required = false) TenantNotificationTemplateService templateService,
            @Autowired(required = false) TenantNotificationLogService notificationLogService,
            @Autowired(required = false) EmailService emailService,
            @Autowired(required = false) CentralTenantRepository centralTenantRepository,
            @Autowired(required = false) StringRedisTemplate redisTemplate,
            @Autowired(required = false) ObjectMapper objectMapper,
            @Autowired(required = false) TransactionTemplate transactionTemplate
    ) {
        this(ticketRepository, feedbackRepository, analyticsService, templateService,
                notificationLogService, emailService, centralTenantRepository,
                redisTemplate, objectMapper, transactionTemplate, Clock.systemDefaultZone());
    }

    public TenantFeedbackServiceImpl(
            TenantQueueTicketRepository ticketRepository,
            TenantFeedbackRepository feedbackRepository,
            TenantFeedbackAnalyticsService analyticsService,
            TenantNotificationTemplateService templateService,
            TenantNotificationLogService notificationLogService,
            EmailService emailService,
            CentralTenantRepository centralTenantRepository,
            StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper,
            TransactionTemplate transactionTemplate,
            Clock clock
    ) {
        super(transactionTemplate);
        this.ticketRepository = ticketRepository;
        this.feedbackRepository = feedbackRepository;
        this.analyticsService = analyticsService;
        this.templateService = templateService;
        this.notificationLogService = notificationLogService;
        this.emailService = emailService;
        this.centralTenantRepository = centralTenantRepository;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper().findAndRegisterModules();
        this.clock = clock != null ? clock : Clock.systemDefaultZone();
    }

    @Override
    public String prepareFeedbackToken(TenantQueueTicket ticket, LocalDateTime completedAt) {
        if (ticket == null) {
            return null;
        }
        if (ticket.getFeedbackToken() != null && !ticket.getFeedbackToken().isBlank()) {
            return ticket.getFeedbackToken();
        }
        byte[] bytes = new byte[24];
        SECURE_RANDOM.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        LocalDateTime baseTime = completedAt != null ? completedAt : LocalDateTime.now(clock);
        ticket.setFeedbackToken(token);
        ticket.setFeedbackTokenExpiresAt(baseTime.plusHours(TOKEN_TTL_HOURS));
        return token;
    }

    @Override
    public void dispatchFeedbackSurvey(String tenantCode, TenantQueueTicket ticket) {
        if (ticket == null || ticket.getFeedbackToken() == null || ticket.getFeedbackToken().isBlank()) {
            return;
        }
        String token = ticket.getFeedbackToken();
        cacheFeedbackTokenRoute(tenantCode, ticket.getId(), token);

        TenantAppointment appointment = ticket.getAppointment();
        String recipientEmail = appointment != null ? appointment.getCustomerEmail() : null;
        if (recipientEmail == null || recipientEmail.isBlank()) {
            return;
        }

        TenantService service = ticket.getService();
        String tenantDisplay = resolveTenantDisplayName(tenantCode);
        String baseUrl = (frontendBaseUrl != null && !frontendBaseUrl.isBlank())
                ? frontendBaseUrl.replaceAll("/+$", "")
                : "http://localhost:3000";
        String feedbackUrl = baseUrl + "/feedback/" + token
                + (tenantCode != null && !tenantCode.isBlank() ? "?tenantCode=" + tenantCode.trim() : "");

        Map<String, String> vars = new HashMap<>();
        vars.put("customer_name", ticket.getCustomerName() != null ? ticket.getCustomerName() : "Quý khách");
        vars.put("service_name", service != null && service.getServiceName() != null ? service.getServiceName() : "");
        vars.put("ticket_code", ticket.getTicketCode() != null ? ticket.getTicketCode() : "");
        vars.put("tenant_name", tenantDisplay);
        vars.put("feedback_url", feedbackUrl);
        vars.put("feedback_token", token);

        String subject = "[" + tenantDisplay + "] Đánh giá chất lượng dịch vụ - " + vars.get("ticket_code");
        String summaryPayload = "Khảo sát đánh giá dịch vụ cho vé " + vars.get("ticket_code");
        if (templateService != null) {
            try {
                NotificationTemplateResponse cfg =
                        templateService.getTemplateOrDefault(NotificationTemplateCode.FEEDBACK_SURVEY.name());
                subject = templateService.renderTemplateText(cfg.getSubjectTemplate(), vars);
                summaryPayload = templateService.renderTemplateText(cfg.getBodyTemplate(), vars);
            } catch (Exception ignored) {
                // Fallback to default subject/body
            }
        }

        String html = buildSurveyHtml(vars, summaryPayload);
        if (emailService != null && emailService.isConfigured()) {
            emailService.sendHtmlEmailAsync(recipientEmail, subject, html);
        } else {
            LogContract.info(log, LogDomain.FEEDBACK, "survey_email_console_fallback")
                    .outcome(LogOutcome.DEGRADED)
                    .tenant(tenantCode)
                    .entity("ticket", ticket.getTicketCode())
                    .email(recipientEmail)
                    .field("feedbackUrl", feedbackUrl)
                    .emit();
        }

        if (notificationLogService != null) {
            notificationLogService.recordDeliveryLog(
                    appointment,
                    ticket,
                    recipientEmail,
                    NotificationChannel.EMAIL,
                    NotificationTemplateCode.FEEDBACK_SURVEY.name(),
                    summaryPayload,
                    NotificationDeliveryStatus.SUCCESS,
                    null
            );
        }
    }

    @Override
    public FeedbackFormInitResponse getFeedbackForm(String feedbackToken) {
        String cleanToken = requireNonBlankToken(feedbackToken);
        bindTenantFromTokenIfAbsent(cleanToken);
        resolveCurrentTenantCode();

        // Acquire the JPA transaction only AFTER TenantContextHolder is bound so Hibernate
        // routes the JDBC connection to the resolved tenant schema rather than 'central'.
        return executeInTransaction(status -> {
            TenantQueueTicket ticket = resolvePendingCompletedTicket(cleanToken);
            TenantService service = ticket.getService();
            TenantStaff provider = ticket.getProvider();

            return FeedbackFormInitResponse.builder()
                    .feedbackToken(cleanToken)
                    .ticketId(ticket.getId())
                    .ticketCode(ticket.getTicketCode())
                    .serviceId(service != null ? service.getId() : null)
                    .serviceName(service != null ? service.getServiceName() : null)
                    .providerId(provider != null ? provider.getId() : null)
                    .providerName(provider != null ? provider.getFullName() : null)
                    .customerName(ticket.getCustomerName())
                    .completedAt(ticket.getCompletedAt())
                    .expiresAt(ticket.getFeedbackTokenExpiresAt())
                    .availableCriteriaTags(DEFAULT_CRITERIA_TAGS)
                    .build();
        });
    }

    @Override
    public FeedbackResponse submitFeedback(String feedbackToken, SubmitFeedbackRequest request) {
        if (request == null || request.getRatingScore() == null
                || request.getRatingScore() < 1 || request.getRatingScore() > 5) {
            throw new FeedbackException(FeedbackErrorCode.FEEDBACK_INVALID_INPUT);
        }

        String cleanToken = requireNonBlankToken(feedbackToken);
        bindTenantFromTokenIfAbsent(cleanToken);
        String tenantCode = resolveCurrentTenantCode();

        String sanitizedComment = sanitizeComment(request.getComment());
        Map<String, Object> cleanCriteria = sanitizeCriteria(request.getCriteriaRatings());
        String criteriaJson = serializeCriteria(cleanCriteria);

        TenantFeedback saved = executeInTransaction(status -> {
            TenantQueueTicket ticket = resolvePendingCompletedTicket(cleanToken);

            TenantFeedback entity = TenantFeedback.builder()
                    .id(UUID.randomUUID().toString())
                    .ticket(ticket)
                    .service(ticket.getService())
                    .provider(ticket.getProvider())
                    .ratingScore(request.getRatingScore())
                    .criteriaRatings(criteriaJson)
                    .comment(sanitizedComment)
                    .feedbackToken(cleanToken)
                    .build();

            try {
                TenantFeedback persisted = feedbackRepository.saveAndFlush(entity);
                return persisted;
            } catch (DataIntegrityViolationException ex) {
                throw new FeedbackException(FeedbackErrorCode.FEEDBACK_ALREADY_SUBMITTED);
            }
        });

        if (analyticsService != null) {
            analyticsService.recordFeedbackMetrics(tenantCode, saved);
        }

        LogContract.info(log, LogDomain.FEEDBACK, "feedback_submitted")
                .outcome(LogOutcome.SUCCESS)
                .tenant(tenantCode)
                .entity("feedback", saved.getId())
                .field("ticketCode", saved.getTicket() != null ? saved.getTicket().getTicketCode() : null)
                .field("ratingScore", saved.getRatingScore())
                .emit();

        return FeedbackResponse.fromEntity(saved, cleanCriteria);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<FeedbackResponse> searchFeedbacks(
            Integer ratingScore,
            Boolean negativeOnly,
            String serviceId,
            String providerId,
            LocalDate fromDate,
            LocalDate toDate,
            Pageable pageable
    ) {
        resolveCurrentTenantCode();
        String cleanService = normalizeFilterId(serviceId);
        String cleanProvider = normalizeFilterId(providerId);
        LocalDateTime fromDt = fromDate != null ? fromDate.atStartOfDay() : null;
        LocalDateTime toDt = toDate != null ? toDate.atTime(LocalTime.MAX) : null;

        return feedbackRepository.searchFeedbacks(
                ratingScore, negativeOnly, cleanService, cleanProvider, fromDt, toDt, pageable
        ).map(entity -> FeedbackResponse.fromEntity(entity, parseCriteria(entity.getCriteriaRatings())));
    }

    @Override
    @Transactional(readOnly = true)
    public FeedbackSummaryResponse getFeedbackSummary(
            Integer ratingScore,
            Boolean negativeOnly,
            String serviceId,
            String providerId,
            LocalDate fromDate,
            LocalDate toDate
    ) {
        String tenantCode = resolveCurrentTenantCode();
        String cleanService = normalizeFilterId(serviceId);
        String cleanProvider = normalizeFilterId(providerId);
        LocalDateTime fromDt = fromDate != null ? fromDate.atStartOfDay() : null;
        LocalDateTime toDt = toDate != null ? toDate.atTime(LocalTime.MAX) : null;

        boolean isUnfiltered = ratingScore == null && !Boolean.TRUE.equals(negativeOnly)
                && cleanService == null && cleanProvider == null && fromDt == null && toDt == null;

        if (isUnfiltered && analyticsService != null) {
            FeedbackSummaryResponse cached = analyticsService.getCachedSummary(tenantCode);
            if (cached != null) {
                return cached;
            }
        }

        List<TenantFeedback> matching = feedbackRepository.findAllForSummary(
                ratingScore, negativeOnly, cleanService, cleanProvider, fromDt, toDt);
        FeedbackSummaryResponse summary = analyticsService.computeSummary(matching);
        if (isUnfiltered && analyticsService != null) {
            analyticsService.cacheSummary(tenantCode, summary);
        }
        return summary;
    }

    // -------------------------------------------------------------------------
    // Token Validation, Sanitization & Caching Helpers
    // -------------------------------------------------------------------------

    private TenantQueueTicket resolvePendingCompletedTicket(String cleanToken) {
        if (feedbackRepository.existsByFeedbackToken(cleanToken)) {
            throw new FeedbackException(FeedbackErrorCode.FEEDBACK_ALREADY_SUBMITTED);
        }

        TenantQueueTicket ticket = ticketRepository.findByFeedbackTokenWithDetails(cleanToken)
                .orElseThrow(() -> new FeedbackException(FeedbackErrorCode.FEEDBACK_TOKEN_INVALID));

        if (feedbackRepository.existsByTicketId(ticket.getId())) {
            throw new FeedbackException(FeedbackErrorCode.FEEDBACK_ALREADY_SUBMITTED);
        }
        if (ticket.getStatus() != TicketStatus.COMPLETED) {
            throw new FeedbackException(FeedbackErrorCode.FEEDBACK_TICKET_NOT_COMPLETED);
        }

        LocalDateTime now = LocalDateTime.now(clock);
        LocalDateTime expiresAt = ticket.getFeedbackTokenExpiresAt();
        if (expiresAt == null || now.isAfter(expiresAt)) {
            throw new FeedbackException(FeedbackErrorCode.FEEDBACK_TOKEN_EXPIRED);
        }
        return ticket;
    }

    private String requireNonBlankToken(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            throw new FeedbackException(FeedbackErrorCode.FEEDBACK_TOKEN_INVALID);
        }
        return rawToken.trim();
    }

    private void bindTenantFromTokenIfAbsent(String cleanToken) {
        if (TenantContextHolder.hasTenant() || redisTemplate == null) {
            return;
        }
        try {
            String mapped = redisTemplate.opsForValue().get(ValkeyKeyConstant.feedbackTokenKey(cleanToken));
            if (mapped != null && !mapped.isBlank()) {
                int sep = mapped.indexOf(':');
                String tenant = sep > 0 ? mapped.substring(0, sep) : mapped;
                if (!tenant.isBlank()) {
                    TenantContextHolder.setTenantCode(tenant.trim());
                }
            }
        } catch (Exception ex) {
            LogContract.warn(log, LogDomain.FEEDBACK, "token_tenant_resolve")
                    .outcome(LogOutcome.DEGRADED)
                    .cause(ex)
                    .emit();
        }
    }

    private void cacheFeedbackTokenRoute(String tenantCode, String ticketId, String token) {
        if (redisTemplate == null || tenantCode == null || tenantCode.isBlank() || token == null) {
            return;
        }
        try {
            String value = tenantCode.trim().toLowerCase() + ":" + (ticketId != null ? ticketId : "");
            redisTemplate.opsForValue().set(
                    ValkeyKeyConstant.feedbackTokenKey(token),
                    value,
                    Duration.ofHours(TOKEN_TTL_HOURS)
            );
        } catch (Exception ex) {
            LogContract.warn(log, LogDomain.FEEDBACK, "token_cache_write")
                    .outcome(LogOutcome.DEGRADED)
                    .tenant(tenantCode)
                    .cause(ex)
                    .emit();
        }
    }

    static String sanitizeComment(String rawComment) {
        if (rawComment == null) {
            return null;
        }
        String cleaned = SCRIPT_BLOCK_PATTERN.matcher(rawComment).replaceAll("");
        cleaned = HTML_TAG_PATTERN.matcher(cleaned).replaceAll("");
        cleaned = EVENT_HANDLER_PATTERN.matcher(cleaned).replaceAll("");
        cleaned = cleaned.trim();
        if (cleaned.isEmpty()) {
            return null;
        }
        return cleaned.length() > MAX_COMMENT_LENGTH ? cleaned.substring(0, MAX_COMMENT_LENGTH) : cleaned;
    }

    private Map<String, Object> sanitizeCriteria(Map<String, Object> raw) {
        if (raw == null || raw.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, Object> clean = new HashMap<>();
        for (Map.Entry<String, Object> entry : raw.entrySet()) {
            if (entry.getKey() == null || entry.getKey().isBlank() || entry.getValue() == null) {
                continue;
            }
            String safeKey = HTML_TAG_PATTERN.matcher(entry.getKey()).replaceAll("").trim();
            if (!safeKey.isEmpty()) {
                clean.put(safeKey, entry.getValue());
            }
        }
        return clean;
    }

    private String serializeCriteria(Map<String, Object> criteria) {
        if (criteria == null || criteria.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(criteria);
        } catch (Exception ex) {
            LogContract.warn(log, LogDomain.FEEDBACK, "criteria_serialize")
                    .outcome(LogOutcome.DEGRADED)
                    .cause(ex)
                    .emit();
            return null;
        }
    }

    private Map<String, Object> parseCriteria(String json) {
        if (json == null || json.isBlank()) {
            return Collections.emptyMap();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception ex) {
            return Collections.emptyMap();
        }
    }

    private String normalizeFilterId(String raw) {
        return (raw != null && !raw.isBlank()) ? raw.trim() : null;
    }

    private String resolveTenantDisplayName(String tenantCode) {
        if (centralTenantRepository == null || tenantCode == null || tenantCode.isBlank()) {
            return tenantCode != null ? tenantCode : "NextTurn";
        }
        return centralTenantRepository.findByTenantCodeIgnoreCaseAndIsDeletedFalse(tenantCode)
                .map(CentralTenant::getTenantName)
                .orElse(tenantCode);
    }

    private String buildSurveyHtml(Map<String, String> vars, String fallbackBody) {
        String rawHtml = htmlCache.computeIfAbsent(SURVEY_HTML_TEMPLATE, path -> {
            try (InputStream is = new ClassPathResource(path).getInputStream()) {
                return new String(is.readAllBytes(), StandardCharsets.UTF_8);
            } catch (Exception ex) {
                LogContract.warn(log, LogDomain.FEEDBACK, "survey_template_load")
                        .outcome(LogOutcome.DEGRADED)
                        .field("path", path)
                        .cause(ex)
                        .emit();
                return "";
            }
        });
        if (rawHtml.isBlank()) {
            return "<p>" + fallbackBody + "</p><p><a href=\"" + vars.get("feedback_url") + "\">Đánh giá ngay</a></p>";
        }
        String rendered = rawHtml;
        for (Map.Entry<String, String> entry : vars.entrySet()) {
            String val = entry.getValue() != null ? entry.getValue() : "";
            rendered = rendered
                    .replace("{{" + entry.getKey() + "}}", val)
                    .replace("{" + entry.getKey() + "}", val);
        }
        return rendered;
    }
}
