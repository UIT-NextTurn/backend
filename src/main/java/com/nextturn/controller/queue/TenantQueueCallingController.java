package com.nextturn.controller.queue;

import com.nextturn.constant.QueueEndpointConstant;
import com.nextturn.constant.TenantHeaderConstant;
import com.nextturn.controller.BaseController;
import com.nextturn.dto.BaseResponse;
import com.nextturn.dto.queue.CallNextTicketRequest;
import com.nextturn.dto.queue.QueueBoardResponse;
import com.nextturn.dto.queue.ServeTicketRequest;
import com.nextturn.dto.queue.TicketTransitionRequest;
import com.nextturn.dto.ticket.TicketResponse;
import com.nextturn.infrastructure.security.annotation.CurrentUser;
import com.nextturn.infrastructure.security.annotation.RequireStaff;
import com.nextturn.infrastructure.security.principal.AuthenticatedUser;
import com.nextturn.service.queue.TenantQueueCallingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
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
 * Authenticated REST controller for Counter Staff & Tenant Admin Queue Calling FSM operations
 * (REQ-QC-01 through REQ-QC-04).
 */
@RestController
@RequestMapping(QueueEndpointConstant.QUEUE_BASE)
@RequireStaff
@Tag(name = "Counter Queue Calling (FSM)", description = "Counter staff Kanban and queue calling workflows: call next with dynamic priority, recall, transfer to specialist, complete, and no-show (REQ-QC-01 to REQ-QC-04)")
@SecurityRequirement(name = "bearerAuth")
public class TenantQueueCallingController extends BaseController {

    private final TenantQueueCallingService queueCallingService;

    public TenantQueueCallingController(TenantQueueCallingService queueCallingService) {
        this.queueCallingService = queueCallingService;
    }

    @PostMapping(QueueEndpointConstant.CALL_NEXT)
    @Operation(
            summary = "Bấm gọi số tiếp theo theo điểm ưu tiên động (REQ-QC-01)",
            description = "Checks counter readiness and specialist capacity (>0), extracts highest priority WAITING ticket (APPOINTMENT=100, WALK_IN=0 + 5 pts/min) using FOR UPDATE SKIP LOCKED, and broadcasts SSE updates."
    )
    public ResponseEntity<BaseResponse<TicketResponse>> callNextTicket(
            @RequestHeader(value = TenantHeaderConstant.X_TENANT_CODE_HEADER, required = false) String headerTenantCode,
            @Valid @RequestBody CallNextTicketRequest request,
            @CurrentUser AuthenticatedUser caller
    ) {
        ensureTenantContext(headerTenantCode);
        TicketResponse called = queueCallingService.callNextTicket(request, caller);
        return ResponseEntity.ok(BaseResponse.ok("Gọi số thứ tự tiếp theo thành công", called));
    }

    @PostMapping(QueueEndpointConstant.TICKET_RECALL)
    @Operation(
            summary = "Bấm gọi lại số thứ tự đang gọi (REQ-QC-04)",
            description = "Increments recall_count on a CALLING ticket without resetting initial called_at timer and re-announces on Lobby Display."
    )
    public ResponseEntity<BaseResponse<TicketResponse>> recallTicket(
            @RequestHeader(value = TenantHeaderConstant.X_TENANT_CODE_HEADER, required = false) String headerTenantCode,
            @PathVariable String ticketId,
            @RequestBody(required = false) TicketTransitionRequest request,
            @CurrentUser AuthenticatedUser caller
    ) {
        ensureTenantContext(headerTenantCode);
        TicketResponse recalled = queueCallingService.recallTicket(ticketId, request, caller);
        return ResponseEntity.ok(BaseResponse.ok("Gọi lại số thứ tự thành công", recalled));
    }

    @PostMapping(QueueEndpointConstant.TICKET_SERVE)
    @Operation(
            summary = "Tiếp nhận khách tại quầy & chuyển giao chuyên viên phục vụ (REQ-QC-02)",
            description = "Transitions CALLING -> SERVING with optimistic @Version lock and assigns/balances to an active in-shift Specialist."
    )
    public ResponseEntity<BaseResponse<TicketResponse>> serveTicket(
            @RequestHeader(value = TenantHeaderConstant.X_TENANT_CODE_HEADER, required = false) String headerTenantCode,
            @PathVariable String ticketId,
            @RequestBody(required = false) ServeTicketRequest request,
            @CurrentUser AuthenticatedUser caller
    ) {
        ensureTenantContext(headerTenantCode);
        TicketResponse serving = queueCallingService.serveTicket(ticketId, request, caller);
        return ResponseEntity.ok(BaseResponse.ok("Chuyển giao phục vụ chuyên viên thành công", serving));
    }

    @PostMapping(QueueEndpointConstant.TICKET_COMPLETE)
    @Operation(
            summary = "Hoàn tất phiên dịch vụ (REQ-QC-03)",
            description = "Transitions SERVING -> COMPLETED, records actual_duration in seconds, frees specialist capacity, and broadcasts SSE update."
    )
    public ResponseEntity<BaseResponse<TicketResponse>> completeTicket(
            @RequestHeader(value = TenantHeaderConstant.X_TENANT_CODE_HEADER, required = false) String headerTenantCode,
            @PathVariable String ticketId,
            @RequestBody(required = false) TicketTransitionRequest request,
            @CurrentUser AuthenticatedUser caller
    ) {
        ensureTenantContext(headerTenantCode);
        TicketResponse completed = queueCallingService.completeTicket(ticketId, request, caller);
        return ResponseEntity.ok(BaseResponse.ok("Hoàn tất lượt phục vụ thành công", completed));
    }

    @PostMapping(QueueEndpointConstant.TICKET_NO_SHOW)
    @Operation(
            summary = "Đánh dấu khách vắng mặt tại quầy gọi số (REQ-QC-04)",
            description = "Transitions CALLING -> NO_SHOW after >= 3 recalls or >= 3 minutes wait, syncs linked appointment to NO_SHOW, and clears lobby display."
    )
    public ResponseEntity<BaseResponse<TicketResponse>> markNoShow(
            @RequestHeader(value = TenantHeaderConstant.X_TENANT_CODE_HEADER, required = false) String headerTenantCode,
            @PathVariable String ticketId,
            @RequestBody(required = false) TicketTransitionRequest request,
            @CurrentUser AuthenticatedUser caller
    ) {
        ensureTenantContext(headerTenantCode);
        TicketResponse noShow = queueCallingService.markNoShow(ticketId, request, caller);
        return ResponseEntity.ok(BaseResponse.ok("Đã đánh dấu khách vắng mặt (No-show)", noShow));
    }

    @GetMapping(QueueEndpointConstant.BOARD)
    @Operation(
            summary = "Bảng Kanban điều phối hàng đợi quầy tiếp nhận",
            description = "Returns today's priority-sorted WAITING queue, CALLING, SERVING, COMPLETED columns, and specialist workloads."
    )
    public ResponseEntity<BaseResponse<QueueBoardResponse>> getQueueBoard(
            @RequestHeader(value = TenantHeaderConstant.X_TENANT_CODE_HEADER, required = false) String headerTenantCode,
            @RequestParam(required = false) String serviceId
    ) {
        ensureTenantContext(headerTenantCode);
        QueueBoardResponse board = queueCallingService.getQueueBoard(serviceId);
        return ResponseEntity.ok(BaseResponse.ok("Lấy bảng điều phối hàng đợi thành công", board));
    }
}
