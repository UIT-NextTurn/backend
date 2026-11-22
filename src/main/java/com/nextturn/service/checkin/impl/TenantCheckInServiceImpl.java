package com.nextturn.service.checkin.impl;

import com.nextturn.dto.checkin.AppointmentCheckInRequest;
import com.nextturn.dto.checkin.ResolveReceptionQrResponse;
import com.nextturn.dto.checkin.WalkInCheckInRequest;
import com.nextturn.dto.service.ServiceResponse;
import com.nextturn.dto.ticket.IssueTicketRequest;
import com.nextturn.dto.ticket.TicketResponse;
import com.nextturn.entity.CentralTenant;
import com.nextturn.entity.TenantReceptionPoint;
import com.nextturn.entity.TenantService;
import com.nextturn.enums.ServiceStatus;
import com.nextturn.exception.checkin.CheckInErrorCode;
import com.nextturn.exception.checkin.CheckInException;
import com.nextturn.infrastructure.multitenancy.TenantContextHolder;
import com.nextturn.repository.auth.CentralTenantRepository;
import com.nextturn.repository.service.TenantServiceRepository;
import com.nextturn.service.BaseService;
import com.nextturn.service.checkin.TenantCheckInService;
import com.nextturn.service.checkin.TenantReceptionQrTokenService;
import com.nextturn.service.ticket.TenantTicketService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

/**
 * Default implementation of {@link TenantCheckInService} handling lobby QR resolution (REQ-CHECK-IN-03),
 * appointment QR check-in (REQ-CHECK-IN-01), and walk-in ticket issuance (REQ-CHECK-IN-02).
 */
@Service
public class TenantCheckInServiceImpl extends BaseService implements TenantCheckInService {

    private final TenantReceptionQrTokenService qrTokenService;
    private final TenantTicketService ticketService;
    private final TenantServiceRepository serviceRepository;
    private final CentralTenantRepository centralTenantRepository;

    @Autowired
    public TenantCheckInServiceImpl(
            TenantReceptionQrTokenService qrTokenService,
            TenantTicketService ticketService,
            TenantServiceRepository serviceRepository,
            @Autowired(required = false) CentralTenantRepository centralTenantRepository,
            @Autowired(required = false) TransactionTemplate transactionTemplate
    ) {
        super(transactionTemplate);
        this.qrTokenService = qrTokenService;
        this.ticketService = ticketService;
        this.serviceRepository = serviceRepository;
        this.centralTenantRepository = centralTenantRepository;
    }

    @Override
    public ResolveReceptionQrResponse resolveReceptionQr(String receptionToken) {
        String embeddedTenant = qrTokenService.extractTenantCodeFromToken(receptionToken);
        String currentTenant = TenantContextHolder.getTenantCode();
        if ((currentTenant == null || currentTenant.isBlank()) && embeddedTenant != null) {
            TenantContextHolder.setTenantCode(embeddedTenant);
        }

        String tenantCode = resolveCurrentTenantCode();
        return executeInTransaction(status -> {
            TenantReceptionPoint point = qrTokenService.validateAndResolveToken(receptionToken);

            TenantService scoped = point.getService();
            List<ServiceResponse> availableServices = scoped != null
                    ? (scoped.getStatus() == ServiceStatus.ACTIVE && !Boolean.TRUE.equals(scoped.getIsDeleted())
                            ? List.of(ServiceResponse.fromEntity(scoped))
                            : List.of())
                    : serviceRepository.findAllByStatusAndIsDeletedFalse(ServiceStatus.ACTIVE).stream()
                            .map(ServiceResponse::fromEntity)
                            .toList();

            String tenantName = resolveTenantDisplayName(tenantCode);

            return ResolveReceptionQrResponse.builder()
                    .tenantCode(tenantCode)
                    .tenantName(tenantName)
                    .receptionPointId(point.getId())
                    .pointCode(point.getPointCode())
                    .pointName(point.getPointName())
                    .locationLabel(point.getLocationLabel())
                    .qrMode(point.getQrMode())
                    .scopedService(scoped != null ? ServiceResponse.fromEntity(scoped) : null)
                    .availableServices(availableServices)
                    .supportsAppointmentCheckIn(true)
                    .supportsWalkIn(true)
                    .build();
        });
    }

    @Override
    public TicketResponse checkInAppointment(AppointmentCheckInRequest request) {
        if (request == null) {
            throw new CheckInException(CheckInErrorCode.RECEPTION_QR_INVALID, "Yêu cầu check-in không được để trống.");
        }
        IssueTicketRequest ticketRequest = IssueTicketRequest.builder()
                .qrToken(request.getQrToken())
                .appointmentId(request.getAppointmentId())
                .receptionQrToken(request.getReceptionQrToken())
                .build();
        return ticketService.issueTicket(ticketRequest);
    }

    @Override
    public TicketResponse checkInWalkIn(WalkInCheckInRequest request) {
        if (request == null) {
            throw new CheckInException(CheckInErrorCode.RECEPTION_QR_INVALID, "Yêu cầu đăng ký vãng lai không được để trống.");
        }
        IssueTicketRequest ticketRequest = IssueTicketRequest.builder()
                .serviceId(request.getServiceId())
                .customerPhone(request.getCustomerPhone())
                .customerName(request.getCustomerName())
                .receptionQrToken(request.getReceptionQrToken())
                .build();
        return ticketService.issueTicket(ticketRequest);
    }

    private String resolveTenantDisplayName(String tenantCode) {
        if (centralTenantRepository == null || tenantCode == null || tenantCode.isBlank()) {
            return tenantCode;
        }
        return centralTenantRepository.findByTenantCodeIgnoreCaseAndIsDeletedFalse(tenantCode)
                .map(CentralTenant::getTenantName)
                .orElse(tenantCode);
    }
}
