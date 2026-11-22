package com.nextturn.service.notification.impl;

import com.nextturn.dto.notification.NotificationTemplateResponse;
import com.nextturn.dto.notification.UpsertNotificationTemplateRequest;
import com.nextturn.entity.TenantNotificationTemplate;
import com.nextturn.enums.NotificationChannel;
import com.nextturn.enums.NotificationTemplateCode;
import com.nextturn.exception.notification.NotificationErrorCode;
import com.nextturn.exception.notification.NotificationException;
import com.nextturn.repository.notification.TenantNotificationTemplateRepository;
import com.nextturn.service.BaseService;
import com.nextturn.service.notification.TenantNotificationTemplateService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Default implementation of {@link TenantNotificationTemplateService} supplying SRS default
 * notification templates with tenant-level overrides and dynamic placeholder rendering (REQ-NOTI-04).
 */
@Service
public class TenantNotificationTemplateServiceImpl extends BaseService implements TenantNotificationTemplateService {

    private static final Map<String, NotificationTemplateResponse> DEFAULT_TEMPLATES = new LinkedHashMap<>();

    static {
        registerDefault(NotificationTemplateCode.APT_CONFIRMATION.name(),
                "Xác nhận đặt lịch hẹn",
                NotificationChannel.EMAIL,
                "[{{tenant_name}}] Xác nhận đặt lịch hẹn - Mã {appointment_code}",
                "Xin chào {customer_name}, lịch hẹn dịch vụ {service_name} lúc {appointment_time} đã được xác nhận.");
        registerDefault(NotificationTemplateCode.APT_REMINDER_24H.name(),
                "Nhắc lịch hẹn trước 24 giờ",
                NotificationChannel.EMAIL,
                "[Nhắc lịch hẹn 24h] Lịch khám {service_name} vào ngày mai",
                "Xin chào {customer_name}, bạn có lịch hẹn dịch vụ {service_name} vào lúc {appointment_time}. Vui lòng chuẩn bị giấy tờ cần thiết.");
        registerDefault(NotificationTemplateCode.APT_REMINDER_1H.name(),
                "Nhắc lịch hẹn trước 1 giờ",
                NotificationChannel.EMAIL,
                "[Nhắc lịch hẹn sắp tới] Vui lòng đến trước giờ hẹn 15 phút",
                "Xin chào {customer_name}, lịch hẹn dịch vụ {service_name} lúc {appointment_time} sắp diễn ra. Vui lòng có mặt trước 15 phút để quét mã QR Check-in.");
        registerDefault(NotificationTemplateCode.APPROACHING_TURN_ALERT.name(),
                "Cảnh báo sắp đến lượt gọi số",
                NotificationChannel.PUSH,
                "Sắp đến lượt phục vụ",
                "Số vé {ticket_code} của Quý khách sắp đến lượt phục vụ (hiện còn {people_ahead} người phía trước). Xin vui lòng di chuyển đến gần khu vực sảnh phục vụ.");
        registerDefault(NotificationTemplateCode.TICKET_CALLED_ALERT.name(),
                "Mời vào quầy tiếp nhận",
                NotificationChannel.PUSH,
                "Mời Quý khách vào quầy",
                "Kính mời Quý khách mang số vé {ticket_code} đến ngay {counter_label} để gặp nhân viên {counter_staff_name} tiếp nhận.");
        registerDefault(NotificationTemplateCode.FEEDBACK_SURVEY.name(),
                "Khảo sát đánh giá chất lượng dịch vụ",
                NotificationChannel.EMAIL,
                "Đánh giá trải nghiệm dịch vụ {service_name}",
                "Cảm ơn {customer_name} đã hoàn tất dịch vụ {service_name}. Vui lòng chia sẻ đánh giá cho lượt phục vụ {ticket_code}.");
    }

    private static void registerDefault(
            String code,
            String name,
            NotificationChannel channel,
            String subject,
            String body
    ) {
        DEFAULT_TEMPLATES.put(code, NotificationTemplateResponse.builder()
                .templateCode(code)
                .templateName(name)
                .channel(channel)
                .subjectTemplate(subject)
                .bodyTemplate(body)
                .isActive(true)
                .customized(false)
                .build());
    }

    private final TenantNotificationTemplateRepository templateRepository;

    @Autowired
    public TenantNotificationTemplateServiceImpl(
            TenantNotificationTemplateRepository templateRepository,
            @Autowired(required = false) TransactionTemplate transactionTemplate
    ) {
        super(transactionTemplate);
        this.templateRepository = templateRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public List<NotificationTemplateResponse> getAllTemplates() {
        resolveCurrentTenantCode();
        Map<String, NotificationTemplateResponse> merged = new LinkedHashMap<>(DEFAULT_TEMPLATES);
        if (templateRepository != null) {
            for (TenantNotificationTemplate entity : templateRepository.findAllByOrderByTemplateCodeAsc()) {
                merged.put(entity.getTemplateCode().toUpperCase(), NotificationTemplateResponse.fromEntity(entity));
            }
        }
        return new ArrayList<>(merged.values());
    }

    @Override
    @Transactional(readOnly = true)
    public NotificationTemplateResponse getTemplateOrDefault(String templateCode) {
        if (templateCode == null || templateCode.isBlank()) {
            throw new NotificationException(NotificationErrorCode.NOTIFICATION_TEMPLATE_NOT_FOUND);
        }
        String normalizedCode = templateCode.trim().toUpperCase();
        if (templateRepository != null) {
            var customOpt = templateRepository.findByTemplateCodeIgnoreCase(normalizedCode);
            if (customOpt.isPresent()) {
                return NotificationTemplateResponse.fromEntity(customOpt.get());
            }
        }
        NotificationTemplateResponse defaultTemplate = DEFAULT_TEMPLATES.get(normalizedCode);
        if (defaultTemplate == null) {
            throw new NotificationException(NotificationErrorCode.NOTIFICATION_TEMPLATE_NOT_FOUND);
        }
        return defaultTemplate;
    }

    @Override
    @Transactional
    public NotificationTemplateResponse upsertTemplate(String templateCode, UpsertNotificationTemplateRequest request) {
        resolveCurrentTenantCode();
        if (templateCode == null || templateCode.isBlank()) {
            throw new NotificationException(NotificationErrorCode.NOTIFICATION_TEMPLATE_INVALID);
        }
        String normalizedCode = templateCode.trim().toUpperCase();
        TenantNotificationTemplate entity = templateRepository.findByTemplateCodeIgnoreCase(normalizedCode)
                .orElseGet(() -> TenantNotificationTemplate.builder()
                        .id(UUID.randomUUID().toString())
                        .templateCode(normalizedCode)
                        .build());

        entity.setTemplateName(request.getTemplateName().trim());
        entity.setChannel(request.getChannel());
        entity.setSubjectTemplate(request.getSubjectTemplate() != null ? request.getSubjectTemplate().trim() : null);
        entity.setBodyTemplate(request.getBodyTemplate().trim());
        entity.setIsActive(request.getIsActive() != null ? request.getIsActive() : true);

        TenantNotificationTemplate saved = templateRepository.save(entity);
        return NotificationTemplateResponse.fromEntity(saved);
    }

    @Override
    public String renderTemplateText(String templateText, Map<String, String> variables) {
        if (templateText == null || templateText.isBlank()) {
            return "";
        }
        String rendered = templateText;
        if (variables != null) {
            for (Map.Entry<String, String> entry : variables.entrySet()) {
                String safeValue = entry.getValue() != null ? entry.getValue() : "";
                rendered = rendered
                        .replace("{{" + entry.getKey() + "}}", safeValue)
                        .replace("{" + entry.getKey() + "}", safeValue);
            }
        }
        return rendered;
    }
}
