package com.nextturn.dto.feedback;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.nextturn.entity.TenantFeedback;
import com.nextturn.entity.TenantQueueTicket;
import com.nextturn.entity.TenantService;
import com.nextturn.entity.TenantStaff;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * Response DTO representing a submitted customer feedback record (REQ-FEEDBACK-02, REQ-FEEDBACK-04).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "Submitted post-service feedback record")
public class FeedbackResponse {

    private String id;
    private String ticketId;
    private String ticketCode;
    private String customerName;
    private String customerPhone;
    private String serviceId;
    private String serviceName;
    private String providerId;
    private String providerName;
    private Integer ratingScore;
    private Map<String, Object> criteriaRatings;
    private String comment;
    private Boolean negativeFlag;
    private LocalDateTime createdAt;

    public static FeedbackResponse fromEntity(TenantFeedback entity, Map<String, Object> parsedCriteria) {
        if (entity == null) {
            return null;
        }
        TenantQueueTicket ticket = entity.getTicket();
        TenantService service = entity.getService();
        TenantStaff provider = entity.getProvider();
        Integer score = entity.getRatingScore();

        return FeedbackResponse.builder()
                .id(entity.getId())
                .ticketId(ticket != null ? ticket.getId() : null)
                .ticketCode(ticket != null ? ticket.getTicketCode() : null)
                .customerName(ticket != null ? ticket.getCustomerName() : null)
                .customerPhone(ticket != null ? ticket.getCustomerPhone() : null)
                .serviceId(service != null ? service.getId() : null)
                .serviceName(service != null ? service.getServiceName() : null)
                .providerId(provider != null ? provider.getId() : null)
                .providerName(provider != null ? provider.getFullName() : null)
                .ratingScore(score)
                .criteriaRatings(parsedCriteria)
                .comment(entity.getComment())
                .negativeFlag(score != null && score <= 2)
                .createdAt(entity.getCreatedAt())
                .build();
    }
}
