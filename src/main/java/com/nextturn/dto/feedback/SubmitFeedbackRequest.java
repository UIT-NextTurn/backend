package com.nextturn.dto.feedback;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * Request payload submitted by a customer for a completed queue ticket (REQ-FEEDBACK-02).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Customer CSAT rating and review submission payload")
public class SubmitFeedbackRequest {

    @NotNull(message = "Điểm đánh giá sao là bắt buộc")
    @Min(value = 1, message = "Điểm đánh giá tối thiểu là 1 sao")
    @Max(value = 5, message = "Điểm đánh giá tối đa là 5 sao")
    @Schema(description = "CSAT star score from 1 (Very Dissatisfied) to 5 (Very Satisfied)", example = "5")
    private Integer ratingScore;

    @Schema(description = "Quick criteria tags/ratings (e.g. {\"Thái độ phục vụ\": 5, \"Thời gian chờ\": 4, \"Không gian cơ sở\": 5})")
    private Map<String, Object> criteriaRatings;

    @Size(max = 500, message = "Nội dung nhận xét tối đa 500 ký tự")
    @Schema(description = "Optional customer review text (sanitized against HTML/XSS)", example = "Nhân viên tư vấn nhiệt tình, nhanh chóng.")
    private String comment;
}
