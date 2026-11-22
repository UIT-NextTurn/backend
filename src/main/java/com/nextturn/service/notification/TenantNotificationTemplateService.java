package com.nextturn.service.notification;

import com.nextturn.dto.notification.NotificationTemplateResponse;
import com.nextturn.dto.notification.UpsertNotificationTemplateRequest;

import java.util.List;
import java.util.Map;

/**
 * Contract for managing customizable tenant notification templates and rendering dynamic
 * placeholders ({customer_name}, {service_name}, {appointment_time}, {ticket_code}, {counter_staff_name})
 * per REQ-NOTI-04.
 */
public interface TenantNotificationTemplateService {

    List<NotificationTemplateResponse> getAllTemplates();

    NotificationTemplateResponse getTemplateOrDefault(String templateCode);

    NotificationTemplateResponse upsertTemplate(String templateCode, UpsertNotificationTemplateRequest request);

    String renderTemplateText(String templateText, Map<String, String> variables);
}
