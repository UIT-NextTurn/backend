package com.nextturn.controller.feedback;

import com.nextturn.constant.FeedbackEndpointConstant;
import com.nextturn.constant.TenantHeaderConstant;
import com.nextturn.controller.BaseController;
import com.nextturn.dto.BaseResponse;
import com.nextturn.dto.feedback.FeedbackResponse;
import com.nextturn.dto.feedback.FeedbackSummaryResponse;
import com.nextturn.infrastructure.security.annotation.RequireTenantAdmin;
import com.nextturn.service.feedback.TenantFeedbackService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/**
 * Authenticated Tenant Admin controller for monitoring CSAT metrics, searching reviews,
 * and triaging 1-2 star negative feedback complaints (REQ-FEEDBACK-03, REQ-FEEDBACK-04).
 */
@RestController
@RequestMapping(FeedbackEndpointConstant.TENANT_FEEDBACK_BASE)
@RequireTenantAdmin
@Tag(name = "Tenant Feedback & CSAT", description = "Admin analytics, CSAT aggregation, and complaint monitoring endpoints (REQ-FEEDBACK-03, REQ-FEEDBACK-04)")
@SecurityRequirement(name = "bearerAuth")
public class TenantFeedbackController extends BaseController {

    private final TenantFeedbackService feedbackService;

    public TenantFeedbackController(TenantFeedbackService feedbackService) {
        this.feedbackService = feedbackService;
    }

    @GetMapping
    @Operation(
            summary = "Tra cứu, lọc và giám sát danh sách đánh giá phản hồi (REQ-FEEDBACK-04)",
            description = "Supports filtering by ratingScore (1-5), negativeOnly (1-2 star complaint flag), serviceId, providerId, and date range."
    )
    public ResponseEntity<BaseResponse<Page<FeedbackResponse>>> searchFeedbacks(
            @RequestHeader(value = TenantHeaderConstant.X_TENANT_CODE_HEADER, required = false) String headerTenantCode,
            @RequestParam(required = false) Integer ratingScore,
            @RequestParam(required = false) Boolean negativeOnly,
            @RequestParam(required = false) String serviceId,
            @RequestParam(required = false) String providerId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        ensureTenantContext(headerTenantCode);
        Page<FeedbackResponse> result = feedbackService.searchFeedbacks(
                ratingScore, negativeOnly, serviceId, providerId, fromDate, toDate,
                PageRequest.of(Math.max(0, page), Math.min(100, Math.max(1, size)))
        );
        return ResponseEntity.ok(BaseResponse.ok("Lấy danh sách phản hồi đánh giá thành công", result));
    }

    @GetMapping(FeedbackEndpointConstant.FEEDBACK_SUMMARY)
    @Operation(
            summary = "Tổng hợp chỉ số hài lòng CSAT và phân bổ sao (REQ-FEEDBACK-03/04)",
            description = "Computes Average Rating, CSAT % (4-5 star share), star distribution (1-5), and negative review count (1-2 stars)."
    )
    public ResponseEntity<BaseResponse<FeedbackSummaryResponse>> getFeedbackSummary(
            @RequestHeader(value = TenantHeaderConstant.X_TENANT_CODE_HEADER, required = false) String headerTenantCode,
            @RequestParam(required = false) Integer ratingScore,
            @RequestParam(required = false) Boolean negativeOnly,
            @RequestParam(required = false) String serviceId,
            @RequestParam(required = false) String providerId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate
    ) {
        ensureTenantContext(headerTenantCode);
        FeedbackSummaryResponse summary = feedbackService.getFeedbackSummary(
                ratingScore, negativeOnly, serviceId, providerId, fromDate, toDate
        );
        return ResponseEntity.ok(BaseResponse.ok("Tổng hợp chỉ số hài lòng CSAT thành công", summary));
    }
}
