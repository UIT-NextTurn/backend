package com.nextturn.dto.feedback;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Response DTO returned when a customer opens the feedback form using {@code feedbackToken}
 * (REQ-FEEDBACK-01, REQ-FEEDBACK-02).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "Service context loaded for rendering the public CSAT feedback form")
public class FeedbackFormInitResponse {

    private String feedbackToken;
    private String ticketId;
    private String ticketCode;
    private String serviceId;
    private String serviceName;
    private String providerId;
    private String providerName;
    private String customerName;
    private LocalDateTime completedAt;
    private LocalDateTime expiresAt;
    private List<String> availableCriteriaTags;
}
