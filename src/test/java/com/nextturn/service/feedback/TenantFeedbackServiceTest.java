package com.nextturn.service.feedback;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nextturn.dto.feedback.FeedbackFormInitResponse;
import com.nextturn.dto.feedback.FeedbackResponse;
import com.nextturn.dto.feedback.FeedbackSummaryResponse;
import com.nextturn.dto.feedback.SubmitFeedbackRequest;
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
import com.nextturn.infrastructure.multitenancy.TenantContextHolder;
import com.nextturn.repository.auth.CentralTenantRepository;
import com.nextturn.repository.feedback.TenantFeedbackRepository;
import com.nextturn.repository.ticket.TenantQueueTicketRepository;
import com.nextturn.service.feedback.impl.TenantFeedbackAnalyticsServiceImpl;
import com.nextturn.service.feedback.impl.TenantFeedbackServiceImpl;
import com.nextturn.service.notification.EmailService;
import com.nextturn.service.notification.TenantNotificationLogService;
import com.nextturn.service.notification.TenantNotificationTemplateService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link TenantFeedbackServiceImpl} and {@link TenantFeedbackAnalyticsServiceImpl}
 * covering SRS REQ-FEEDBACK-01 through REQ-FEEDBACK-04.
 */
@ExtendWith(MockitoExtension.class)
class TenantFeedbackServiceTest {

    private static final String TENANT_CODE = "clinic-alpha";
    private static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2026-11-23T06:00:00Z"), ZONE); // 13:00:00 +07:00

    @Mock
    private TenantQueueTicketRepository ticketRepository;

    @Mock
    private TenantFeedbackRepository feedbackRepository;

    @Mock
    private TenantNotificationTemplateService templateService;

    @Mock
    private TenantNotificationLogService logService;

    @Mock
    private EmailService emailService;

    @Mock
    private CentralTenantRepository centralTenantRepository;

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOps;

    private TenantFeedbackAnalyticsServiceImpl analyticsService;
    private TenantFeedbackServiceImpl feedbackService;

    @BeforeEach
    void setUp() {
        TenantContextHolder.setTenantCode(TENANT_CODE);
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOps);
        analyticsService = new TenantFeedbackAnalyticsServiceImpl(null, redisTemplate, new ObjectMapper().findAndRegisterModules());
        feedbackService = new TenantFeedbackServiceImpl(
                ticketRepository,
                feedbackRepository,
                analyticsService,
                templateService,
                logService,
                emailService,
                centralTenantRepository,
                redisTemplate,
                new ObjectMapper().findAndRegisterModules(),
                null,
                FIXED_CLOCK
        );
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
    }

    @Test
    @DisplayName("REQ-FEEDBACK-01: Generates 24h token on COMPLETED ticket and dispatches survey email + notification log")
    void prepareAndDispatchFeedbackSurvey() {
        TenantService service = TenantService.builder().id("srv-1").serviceName("Khám nhi").build();
        TenantAppointment appointment = TenantAppointment.builder()
                .id("apt-1")
                .appointmentCode("APT-20261123-0001")
                .customerEmail("customer@example.com")
                .build();
        LocalDateTime now = LocalDateTime.now(FIXED_CLOCK);
        TenantQueueTicket ticket = TenantQueueTicket.builder()
                .id("tkt-1")
                .ticketCode("A-005")
                .service(service)
                .appointment(appointment)
                .customerName("Nguyễn Minh")
                .status(TicketStatus.COMPLETED)
                .completedAt(now)
                .build();

        String token = feedbackService.prepareFeedbackToken(ticket, now);
        assertThat(token).isNotBlank().hasSizeLessThanOrEqualTo(64);
        assertThat(ticket.getFeedbackTokenExpiresAt()).isEqualTo(now.plusHours(24));
        // Idempotent reuse
        assertThat(feedbackService.prepareFeedbackToken(ticket, now.plusMinutes(5))).isEqualTo(token);

        when(centralTenantRepository.findByTenantCodeIgnoreCaseAndIsDeletedFalse(TENANT_CODE))
                .thenReturn(Optional.of(CentralTenant.builder().tenantCode(TENANT_CODE).tenantName("Phòng khám Alpha").build()));
        when(emailService.isConfigured()).thenReturn(true);

        feedbackService.dispatchFeedbackSurvey(TENANT_CODE, ticket);

        verify(valueOps).set(eq("nextturn:feedback:token:" + token), eq("clinic-alpha:tkt-1"), eq(Duration.ofHours(24)));
        verify(emailService).sendHtmlEmailAsync(eq("customer@example.com"), anyString(), contains(token));
        verify(logService).recordDeliveryLog(
                eq(appointment), eq(ticket), eq("customer@example.com"), eq(NotificationChannel.EMAIL),
                eq(NotificationTemplateCode.FEEDBACK_SURVEY.name()), anyString(),
                eq(NotificationDeliveryStatus.SUCCESS), isNull()
        );
    }

    @Test
    @DisplayName("REQ-FEEDBACK-01/02: Enforces 24h expiry (410 Gone) and one-time submission guard (409 Conflict)")
    void getFeedbackForm_ValidExpiredAndDuplicateChecks() {
        LocalDateTime now = LocalDateTime.now(FIXED_CLOCK);
        TenantService service = TenantService.builder().id("srv-1").serviceName("Xét nghiệm").build();
        TenantStaff provider = TenantStaff.builder().id("stf-1").fullName("BS. Lê Hoa").build();

        TenantQueueTicket validTicket = TenantQueueTicket.builder()
                .id("tkt-valid")
                .ticketCode("B-010")
                .service(service)
                .provider(provider)
                .customerName("Trần Bình")
                .status(TicketStatus.COMPLETED)
                .completedAt(now.minusHours(2))
                .feedbackToken("valid-tok")
                .feedbackTokenExpiresAt(now.plusHours(22))
                .build();

        when(feedbackRepository.existsByFeedbackToken("valid-tok")).thenReturn(false);
        when(ticketRepository.findByFeedbackTokenWithDetails("valid-tok")).thenReturn(Optional.of(validTicket));
        when(feedbackRepository.existsByTicketId("tkt-valid")).thenReturn(false);

        FeedbackFormInitResponse form = feedbackService.getFeedbackForm("valid-tok");
        assertThat(form.getTicketCode()).isEqualTo("B-010");
        assertThat(form.getProviderName()).isEqualTo("BS. Lê Hoa");
        assertThat(form.getAvailableCriteriaTags()).contains("Thái độ phục vụ", "Thời gian chờ", "Không gian cơ sở");

        // Expired token (> 24h)
        TenantQueueTicket expiredTicket = TenantQueueTicket.builder()
                .id("tkt-exp")
                .status(TicketStatus.COMPLETED)
                .feedbackToken("exp-tok")
                .feedbackTokenExpiresAt(now.minusMinutes(1))
                .build();
        when(feedbackRepository.existsByFeedbackToken("exp-tok")).thenReturn(false);
        when(ticketRepository.findByFeedbackTokenWithDetails("exp-tok")).thenReturn(Optional.of(expiredTicket));

        assertThatThrownBy(() -> feedbackService.getFeedbackForm("exp-tok"))
                .isInstanceOf(FeedbackException.class)
                .extracting(ex -> ((FeedbackException) ex).getCode())
                .isEqualTo(FeedbackErrorCode.FEEDBACK_TOKEN_EXPIRED.code());

        // Duplicate submission
        when(feedbackRepository.existsByFeedbackToken("done-tok")).thenReturn(true);
        assertThatThrownBy(() -> feedbackService.getFeedbackForm("done-tok"))
                .isInstanceOf(FeedbackException.class)
                .extracting(ex -> ((FeedbackException) ex).getCode())
                .isEqualTo(FeedbackErrorCode.FEEDBACK_ALREADY_SUBMITTED.code());
    }

    @Test
    @DisplayName("REQ-FEEDBACK-02: Sanitizes XSS script/HTML tags in comment and saves criteria ratings")
    void submitFeedback_SanitizesCommentAndStoresFeedback() {
        LocalDateTime now = LocalDateTime.now(FIXED_CLOCK);
        TenantService service = TenantService.builder().id("srv-1").serviceName("Nha khoa").build();
        TenantStaff provider = TenantStaff.builder().id("stf-2").fullName("BS. Nam").build();
        TenantQueueTicket ticket = TenantQueueTicket.builder()
                .id("tkt-sub")
                .ticketCode("C-003")
                .service(service)
                .provider(provider)
                .customerName("Phạm An")
                .status(TicketStatus.COMPLETED)
                .completedAt(now.minusMinutes(10))
                .feedbackToken("tok-sub")
                .feedbackTokenExpiresAt(now.plusHours(23))
                .build();

        when(feedbackRepository.existsByFeedbackToken("tok-sub")).thenReturn(false);
        when(ticketRepository.findByFeedbackTokenWithDetails("tok-sub")).thenReturn(Optional.of(ticket));
        when(feedbackRepository.existsByTicketId("tkt-sub")).thenReturn(false);
        when(feedbackRepository.saveAndFlush(any(TenantFeedback.class))).thenAnswer(inv -> inv.getArgument(0));

        SubmitFeedbackRequest request = SubmitFeedbackRequest.builder()
                .ratingScore(2)
                .criteriaRatings(Map.of("Thái độ phục vụ", 2, "Thời gian chờ", 1))
                .comment("<script>alert('xss')</script><b>Chờ khá lâu</b> onclick=steal()")
                .build();

        FeedbackResponse saved = feedbackService.submitFeedback("tok-sub", request);

        ArgumentCaptor<TenantFeedback> captor = ArgumentCaptor.forClass(TenantFeedback.class);
        verify(feedbackRepository).saveAndFlush(captor.capture());

        assertThat(captor.getValue().getComment()).isEqualTo("Chờ khá lâu steal()");
        assertThat(captor.getValue().getCriteriaRatings()).contains("Thái độ phục vụ");
        assertThat(saved.getNegativeFlag()).isTrue();
        assertThat(saved.getRatingScore()).isEqualTo(2);
        // Retain feedback token route in Valkey for its 24h TTL so duplicate visits without ?tenantCode= still return 409 FEEDBACK_003
        verify(redisTemplate, org.mockito.Mockito.never()).delete("nextturn:feedback:token:tok-sub");
        verify(redisTemplate).delete("nextturn:feedback:csat:" + TENANT_CODE);
    }

    @Test
    @DisplayName("REQ-FEEDBACK-03/04: Computes exact Average Rating, CSAT %, star distribution, and negative review count")
    void computeSummary_CalculatesCsatMetricsAccurately() {
        List<TenantFeedback> feedbacks = List.of(
                TenantFeedback.builder().ratingScore(5).build(),
                TenantFeedback.builder().ratingScore(4).build(),
                TenantFeedback.builder().ratingScore(4).build(),
                TenantFeedback.builder().ratingScore(2).build(),
                TenantFeedback.builder().ratingScore(1).build()
        );

        FeedbackSummaryResponse summary = analyticsService.computeSummary(feedbacks);

        assertThat(summary.getTotalFeedbacks()).isEqualTo(5L);
        assertThat(summary.getAverageRating()).isEqualTo(3.2d); // (5+4+4+2+1)/5
        assertThat(summary.getCsatPercentage()).isEqualTo(60.0d); // 3 out of 5 are 4 or 5 stars
        assertThat(summary.getNegativeCount()).isEqualTo(2L); // 1 and 2 star ratings
        assertThat(summary.getRatingDistribution()).containsEntry(5, 1L)
                .containsEntry(4, 2L)
                .containsEntry(3, 0L)
                .containsEntry(2, 1L)
                .containsEntry(1, 1L);
    }
}
