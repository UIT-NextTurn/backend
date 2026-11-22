package com.nextturn.dto.notification;

import com.nextturn.enums.NotificationChannel;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request payload allowing Tenant Admins to customize notification templates (REQ-NOTI-04).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Upsert payload for customizing a notification template")
public class UpsertNotificationTemplateRequest {

    @NotBlank(message = "Tên mẫu thông báo không được để trống")
    @Size(max = 150, message = "Tên mẫu thông báo không vượt quá 150 ký tự")
    private String templateName;

    @NotNull(message = "Kênh gửi thông báo không được để trống")
    private NotificationChannel channel;

    @Size(max = 255, message = "Tiêu đề mẫu thông báo không vượt quá 255 ký tự")
    private String subjectTemplate;

    @NotBlank(message = "Nội dung mẫu thông báo không được để trống")
    @Schema(description = "Template body supporting dynamic placeholders like {customer_name}, {service_name}, {appointment_time}, {ticket_code}, {counter_staff_name}")
    private String bodyTemplate;

    @Builder.Default
    private Boolean isActive = true;
}
