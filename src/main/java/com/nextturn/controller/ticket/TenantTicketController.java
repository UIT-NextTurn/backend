package com.nextturn.controller.ticket;

import com.nextturn.constant.TenantHeaderConstant;
import com.nextturn.constant.TicketEndpointConstant;
import com.nextturn.controller.BaseController;
import com.nextturn.dto.BaseResponse;
import com.nextturn.dto.ticket.TicketResponse;
import com.nextturn.service.ticket.TenantTicketService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/**
 * Authenticated REST controller for Tenant Admin / Counter Staff to monitor issued queue tickets.
 */
@RestController
@RequestMapping(TicketEndpointConstant.TICKETS_BASE)
@Tag(name = "Tenant Queue Tickets", description = "Authenticated endpoints for viewing and monitoring issued queue tickets")
@SecurityRequirement(name = "bearerAuth")
public class TenantTicketController extends BaseController {

    private final TenantTicketService ticketService;

    public TenantTicketController(TenantTicketService ticketService) {
        this.ticketService = ticketService;
    }

    @GetMapping
    @Operation(
            summary = "Danh sách vé số thứ tự trong ngày của cơ sở",
            description = "Retrieves all queue tickets issued on the specified date (defaults to today) with optional serviceId filter."
    )
    public ResponseEntity<BaseResponse<List<TicketResponse>>> getTicketsForDate(
            @RequestHeader(value = TenantHeaderConstant.X_TENANT_CODE_HEADER, required = false) String headerTenantCode,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate workDate,
            @RequestParam(required = false) String serviceId
    ) {
        ensureTenantContext(headerTenantCode);
        List<TicketResponse> tickets = ticketService.getTicketsForDate(workDate, serviceId);
        return ResponseEntity.ok(BaseResponse.ok("Lấy danh sách vé hàng đợi thành công", tickets));
    }

    @GetMapping(TicketEndpointConstant.TICKET_BY_ID_OR_CODE)
    @Operation(
            summary = "Chi tiết vé số thứ tự theo ID hoặc mã vé",
            description = "Returns detailed status and waiting metrics for a specific queue ticket."
    )
    public ResponseEntity<BaseResponse<TicketResponse>> getTicketByIdOrCode(
            @RequestHeader(value = TenantHeaderConstant.X_TENANT_CODE_HEADER, required = false) String headerTenantCode,
            @PathVariable String ticketIdOrCode
    ) {
        ensureTenantContext(headerTenantCode);
        TicketResponse ticket = ticketService.getTicketDetails(ticketIdOrCode);
        return ResponseEntity.ok(BaseResponse.ok("Lấy thông tin chi tiết vé thành công", ticket));
    }
}
