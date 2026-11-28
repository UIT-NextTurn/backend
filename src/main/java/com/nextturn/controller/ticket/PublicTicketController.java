package com.nextturn.controller.ticket;

import com.nextturn.constant.TenantHeaderConstant;
import com.nextturn.constant.TicketEndpointConstant;
import com.nextturn.controller.BaseController;
import com.nextturn.dto.BaseResponse;
import com.nextturn.dto.ticket.IssueTicketRequest;
import com.nextturn.dto.ticket.TicketResponse;
import com.nextturn.service.ticket.TenantTicketService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

/**
 * Public / Customer-facing REST controller for Electronic Queue Ticket issuance (REQ-TICKET-01),
 * live queue position tracking (REQ-TICKET-02), real-time SSE streaming (REQ-TICKET-03),
 * and phone-based e-ticket recovery (REQ-TICKET-04).
 */
@RestController
@RequestMapping(TicketEndpointConstant.PUBLIC_TICKETS_BASE)
@Tag(name = "Public Electronic Queue Tickets", description = "Customer endpoints for issuing queue tickets, tracking wait positions, SSE progress streaming, and ticket recovery (REQ-TICKET-01 to REQ-TICKET-04)")
public class PublicTicketController extends BaseController {

    private final TenantTicketService ticketService;

    public PublicTicketController(TenantTicketService ticketService) {
        this.ticketService = ticketService;
    }

    @PostMapping
    @Operation(
            summary = "Cấp phát phiếu số thứ tự điện tử (REQ-TICKET-01, REQ-CHECK-IN-01/02)",
            description = "Atomically generates a strictly monotonic daily queue ticket (e.g. A-001..A-999, A-1000) using Valkey distributed lock and Lua script."
    )
    public ResponseEntity<BaseResponse<TicketResponse>> issueTicket(
            @RequestHeader(value = TenantHeaderConstant.X_TENANT_CODE_HEADER, required = false) String headerTenantCode,
            @RequestParam(required = false) String tenantCode,
            @Valid @RequestBody IssueTicketRequest request
    ) {
        ensureTenantContext(headerTenantCode, tenantCode);
        TicketResponse response = ticketService.issueTicket(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(BaseResponse.created("Cấp phiếu số thứ tự điện tử thành công", response));
    }

    @GetMapping(TicketEndpointConstant.TICKET_RECOVER)
    @Operation(
            summary = "Tra cứu và khôi phục phiếu vé theo số điện thoại (REQ-TICKET-04)",
            description = "Recovers active queue tickets (WAITING, CALLING, SERVING) issued today for the given customer phone number."
    )
    public ResponseEntity<BaseResponse<List<TicketResponse>>> recoverTicketsByPhone(
            @RequestHeader(value = TenantHeaderConstant.X_TENANT_CODE_HEADER, required = false) String headerTenantCode,
            @RequestParam(required = false) String tenantCode,
            @Parameter(description = "10-digit customer phone number", required = true)
            @RequestParam String customerPhone
    ) {
        ensureTenantContext(headerTenantCode, tenantCode);
        List<TicketResponse> tickets = ticketService.recoverTicketsByPhoneToday(customerPhone);
        return ResponseEntity.ok(BaseResponse.ok("Khôi phục thông tin phiếu vé thành công", tickets));
    }

    @GetMapping(TicketEndpointConstant.TICKET_BY_ID_OR_CODE)
    @Operation(
            summary = "Hiển thị thông tin vé ảo & vị trí hàng đợi hiện tại (REQ-TICKET-02)",
            description = "Returns live virtual ticket view including current_serving_number, queue_position, people_ahead_count, and estimated_wait_minutes."
    )
    public ResponseEntity<BaseResponse<TicketResponse>> getTicketDetails(
            @RequestHeader(value = TenantHeaderConstant.X_TENANT_CODE_HEADER, required = false) String headerTenantCode,
            @RequestParam(required = false) String tenantCode,
            @Parameter(description = "Ticket UUID or ticket code (e.g. A-001)")
            @PathVariable String ticketIdOrCode
    ) {
        ensureTenantContext(headerTenantCode, tenantCode);
        TicketResponse response = ticketService.getTicketDetails(ticketIdOrCode);
        return ResponseEntity.ok(BaseResponse.ok("Lấy thông tin phiếu số thứ tự thành công", response));
    }

    @GetMapping(value = TicketEndpointConstant.TICKET_STREAM, produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(
            summary = "Đăng ký kênh SSE đồng bộ tiến độ hàng đợi thời gian thực (REQ-TICKET-03)",
            description = "Opens a Server-Sent Events (SSE) stream pushing real-time queue progress updates."
    )
    public SseEmitter streamTicketProgress(
            @RequestHeader(value = TenantHeaderConstant.X_TENANT_CODE_HEADER, required = false) String headerTenantCode,
            @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId,
            @RequestParam(required = false) String tenantCode,
            @PathVariable String ticketIdOrCode
    ) {
        ensureTenantContext(headerTenantCode, tenantCode);
        return (lastEventId != null && !lastEventId.isBlank())
                ? ticketService.subscribeToTicketStream(ticketIdOrCode, lastEventId)
                : ticketService.subscribeToTicketStream(ticketIdOrCode);
    }
}
