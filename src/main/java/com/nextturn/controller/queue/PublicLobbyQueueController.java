package com.nextturn.controller.queue;

import com.nextturn.constant.QueueEndpointConstant;
import com.nextturn.constant.TenantHeaderConstant;
import com.nextturn.controller.BaseController;
import com.nextturn.dto.BaseResponse;
import com.nextturn.dto.queue.LobbyDisplayResponse;
import com.nextturn.service.queue.TenantQueueCallingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Public Lobby Waiting Room Display REST & SSE controller (REQ-QC-01, REQ-QC-04).
 */
@RestController
@RequestMapping(QueueEndpointConstant.PUBLIC_QUEUE_BASE)
@Tag(name = "Public Lobby Queue Display", description = "Waiting room TV snapshot and real-time SSE stream for called/serving ticket announcements")
public class PublicLobbyQueueController extends BaseController {

    private final TenantQueueCallingService queueCallingService;

    public PublicLobbyQueueController(TenantQueueCallingService queueCallingService) {
        this.queueCallingService = queueCallingService;
    }

    @GetMapping(QueueEndpointConstant.LOBBY_DISPLAY)
    @Operation(
            summary = "Hiển thị danh sách số đang gọi / đang phục vụ trên màn hình sảnh",
            description = "Returns currently CALLING and SERVING queue tickets for today's lobby monitor."
    )
    public ResponseEntity<BaseResponse<LobbyDisplayResponse>> getLobbyDisplay(
            @RequestHeader(value = TenantHeaderConstant.X_TENANT_CODE_HEADER, required = false) String headerTenantCode,
            @RequestParam(required = false) String tenantCode
    ) {
        ensureTenantContext(headerTenantCode, tenantCode);
        LobbyDisplayResponse display = queueCallingService.getLobbyDisplay();
        return ResponseEntity.ok(BaseResponse.ok("Lấy trạng thái màn hình sảnh thành công", display));
    }

    @GetMapping(value = QueueEndpointConstant.LOBBY_DISPLAY_STREAM, produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(
            summary = "Đăng ký luồng SSE cập nhật màn hình sảnh thời gian thực",
            description = "Streams real-time ticket calling, recall, serving, completion, and no-show updates."
    )
    public SseEmitter streamLobbyDisplay(
            @RequestHeader(value = TenantHeaderConstant.X_TENANT_CODE_HEADER, required = false) String headerTenantCode,
            @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId,
            @RequestParam(required = false) String tenantCode
    ) {
        ensureTenantContext(headerTenantCode, tenantCode);
        return (lastEventId != null && !lastEventId.isBlank())
                ? queueCallingService.subscribeToLobbyDisplay(lastEventId)
                : queueCallingService.subscribeToLobbyDisplay();
    }
}
