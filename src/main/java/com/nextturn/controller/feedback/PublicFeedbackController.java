package com.nextturn.controller.feedback;

import com.nextturn.constant.FeedbackEndpointConstant;
import com.nextturn.constant.TenantHeaderConstant;
import com.nextturn.controller.BaseController;
import com.nextturn.dto.BaseResponse;
import com.nextturn.dto.feedback.FeedbackFormInitResponse;
import com.nextturn.dto.feedback.FeedbackResponse;
import com.nextturn.dto.feedback.SubmitFeedbackRequest;
import com.nextturn.service.feedback.TenantFeedbackService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public customer-facing controller for opening a post-service CSAT survey form via its 24h token
 * and submitting star ratings & sanitized comments (REQ-FEEDBACK-01, REQ-FEEDBACK-02).
 */
@RestController
@RequestMapping(FeedbackEndpointConstant.PUBLIC_FEEDBACK_BASE)
@Tag(name = "Public Customer Feedback", description = "Public endpoints for validating CSAT feedback links and submitting post-service ratings (REQ-FEEDBACK-01, REQ-FEEDBACK-02)")
public class PublicFeedbackController extends BaseController {

    private final TenantFeedbackService feedbackService;

    public PublicFeedbackController(TenantFeedbackService feedbackService) {
        this.feedbackService = feedbackService;
    }

    @GetMapping(FeedbackEndpointConstant.FEEDBACK_BY_TOKEN)
    @Operation(
            summary = "Kiểm tra liên kết đánh giá và tải thông tin lượt phục vụ (REQ-FEEDBACK-01/02)",
            description = "Validates the one-time feedback token (24h TTL) and returns service/provider details for the CSAT survey form."
    )
    public ResponseEntity<BaseResponse<FeedbackFormInitResponse>> getFeedbackForm(
            @RequestHeader(value = TenantHeaderConstant.X_TENANT_CODE_HEADER, required = false) String headerTenantCode,
            @RequestParam(required = false) String tenantCode,
            @Parameter(description = "One-time CSAT feedback token issued on COMPLETED ticket")
            @PathVariable String token
    ) {
        ensureTenantContext(headerTenantCode, tenantCode);
        FeedbackFormInitResponse form = feedbackService.getFeedbackForm(token);
        return ResponseEntity.ok(BaseResponse.ok("Tải thông tin biểu mẫu đánh giá thành công", form));
    }

    @PostMapping(FeedbackEndpointConstant.FEEDBACK_BY_TOKEN)
    @Operation(
            summary = "Gửi điểm số hài lòng (1-5 sao) và ý kiến đóng góp (REQ-FEEDBACK-02/03)",
            description = "Submits a one-time CSAT rating (1-5 stars), quick criteria tags, and XSS-sanitized comment, triggering real-time CSAT analytics updates."
    )
    public ResponseEntity<BaseResponse<FeedbackResponse>> submitFeedback(
            @RequestHeader(value = TenantHeaderConstant.X_TENANT_CODE_HEADER, required = false) String headerTenantCode,
            @RequestParam(required = false) String tenantCode,
            @PathVariable String token,
            @Valid @RequestBody SubmitFeedbackRequest request
    ) {
        ensureTenantContext(headerTenantCode, tenantCode);
        FeedbackResponse saved = feedbackService.submitFeedback(token, request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(BaseResponse.created("Cảm ơn Quý khách đã gửi đánh giá chất lượng dịch vụ!", saved));
    }
}
