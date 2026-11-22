package com.nextturn.controller.notification;

import com.nextturn.constant.NotificationEndpointConstant;
import com.nextturn.constant.TenantHeaderConstant;
import com.nextturn.controller.BaseController;
import com.nextturn.dto.BaseResponse;
import com.nextturn.dto.notification.NotificationLogResponse;
import com.nextturn.dto.notification.NotificationTemplateResponse;
import com.nextturn.dto.notification.ReminderDispatchSummaryResponse;
import com.nextturn.dto.notification.UpsertNotificationTemplateRequest;
import com.nextturn.enums.NotificationChannel;
import com.nextturn.enums.NotificationDeliveryStatus;
import com.nextturn.infrastructure.security.annotation.RequireTenantAdmin;
import com.nextturn.service.notification.TenantAppointmentReminderService;
import com.nextturn.service.notification.TenantNotificationLogService;
import com.nextturn.service.notification.TenantNotificationTemplateService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Authenticated Tenant Admin controller for customizing notification templates,
 * inspecting delivery logs, and triggering reminder sweeps (REQ-NOTI-01, REQ-NOTI-04).
 */
@RestController
@RequestMapping(NotificationEndpointConstant.NOTIFICATION_BASE)
@RequireTenantAdmin
@Tag(name = "Tenant Notifications", description = "Notification template customization, multi-channel delivery log audit, and appointment reminder sweeps (REQ-NOTI-01, REQ-NOTI-04)")
@SecurityRequirement(name = "bearerAuth")
public class TenantNotificationController extends BaseController {

    private final TenantNotificationTemplateService templateService;
    private final TenantNotificationLogService logService;
    private final TenantAppointmentReminderService reminderService;

    public TenantNotificationController(
            TenantNotificationTemplateService templateService,
            TenantNotificationLogService logService,
            TenantAppointmentReminderService reminderService
    ) {
        this.templateService = templateService;
        this.logService = logService;
        this.reminderService = reminderService;
    }

    @GetMapping(NotificationEndpointConstant.TEMPLATES)
    @Operation(
            summary = "Danh sách mẫu bản tin thông báo của cơ sở (REQ-NOTI-04)",
            description = "Returns all standard and customized notification templates supporting dynamic placeholders."
    )
    public ResponseEntity<BaseResponse<List<NotificationTemplateResponse>>> getAllTemplates(
            @RequestHeader(value = TenantHeaderConstant.X_TENANT_CODE_HEADER, required = false) String headerTenantCode
    ) {
        ensureTenantContext(headerTenantCode);
        return ResponseEntity.ok(BaseResponse.ok("Lấy danh sách mẫu thông báo thành công",
                templateService.getAllTemplates()));
    }

    @PutMapping(NotificationEndpointConstant.TEMPLATE_BY_CODE)
    @Operation(
            summary = "Tùy biến hoặc cập nhật mẫu thông báo (REQ-NOTI-04)",
            description = "Creates or updates a tenant-specific template with dynamic variables ({customer_name}, {service_name}, {appointment_time}, {ticket_code}, {counter_staff_name})."
    )
    public ResponseEntity<BaseResponse<NotificationTemplateResponse>> upsertTemplate(
            @RequestHeader(value = TenantHeaderConstant.X_TENANT_CODE_HEADER, required = false) String headerTenantCode,
            @PathVariable String templateCode,
            @Valid @RequestBody UpsertNotificationTemplateRequest request
    ) {
        ensureTenantContext(headerTenantCode);
        NotificationTemplateResponse saved = templateService.upsertTemplate(templateCode, request);
        return ResponseEntity.ok(BaseResponse.ok("Cập nhật mẫu thông báo thành công", saved));
    }

    @GetMapping(NotificationEndpointConstant.LOGS)
    @Operation(
            summary = "Tra cứu và lọc nhật ký gửi thông báo (REQ-NOTI-04)",
            description = "Searches delivery audit logs by customer phone/email, ticket code, channel (EMAIL/PUSH/SMS), status, or templateCode."
    )
    public ResponseEntity<BaseResponse<Page<NotificationLogResponse>>> searchLogs(
            @RequestHeader(value = TenantHeaderConstant.X_TENANT_CODE_HEADER, required = false) String headerTenantCode,
            @RequestParam(required = false) String recipient,
            @RequestParam(required = false) String ticketCode,
            @RequestParam(required = false) NotificationChannel channel,
            @RequestParam(required = false) NotificationDeliveryStatus status,
            @RequestParam(required = false) String templateCode,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        ensureTenantContext(headerTenantCode);
        Page<NotificationLogResponse> logs = logService.searchLogs(
                recipient, ticketCode, channel, status, templateCode,
                PageRequest.of(Math.max(0, page), Math.min(100, Math.max(1, size)))
        );
        return ResponseEntity.ok(BaseResponse.ok("Tra cứu nhật ký thông báo thành công", logs));
    }

    @PostMapping(NotificationEndpointConstant.TRIGGER_REMINDERS)
    @Operation(
            summary = "Kích hoạt quét gửi nhắc lịch hẹn 24h & 1h (REQ-NOTI-01)",
            description = "Manually executes the upcoming appointment reminder sweep for the current tenant."
    )
    public ResponseEntity<BaseResponse<ReminderDispatchSummaryResponse>> triggerReminderSweep(
            @RequestHeader(value = TenantHeaderConstant.X_TENANT_CODE_HEADER, required = false) String headerTenantCode
    ) {
        ensureTenantContext(headerTenantCode);
        ReminderDispatchSummaryResponse summary = reminderService.dispatchUpcomingReminders();
        return ResponseEntity.ok(BaseResponse.ok("Thực thi gửi nhắc lịch hẹn thành công", summary));
    }
}
