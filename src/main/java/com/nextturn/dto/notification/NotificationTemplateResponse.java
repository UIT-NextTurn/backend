package com.nextturn.dto.notification;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.nextturn.entity.TenantNotificationTemplate;
import com.nextturn.enums.NotificationChannel;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Response DTO representing a notification template configuration (REQ-NOTI-04).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "Notification template definition with placeholder support")
public class NotificationTemplateResponse {

    private String id;
    private String templateCode;
    private String templateName;
    private NotificationChannel channel;
    private String subjectTemplate;
    private String bodyTemplate;
    private Boolean isActive;
    private Boolean customized;
    private LocalDateTime updatedAt;

    public static NotificationTemplateResponse fromEntity(TenantNotificationTemplate entity) {
        if (entity == null) {
            return null;
        }
        return NotificationTemplateResponse.builder()
                .id(entity.getId())
                .templateCode(entity.getTemplateCode())
                .templateName(entity.getTemplateName())
                .channel(entity.getChannel())
                .subjectTemplate(entity.getSubjectTemplate())
                .bodyTemplate(entity.getBodyTemplate())
                .isActive(entity.getIsActive())
                .customized(true)
                .updatedAt(entity.getUpdatedAt())
                .build();
    }
}
