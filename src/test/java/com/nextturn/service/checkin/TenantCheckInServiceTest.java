package com.nextturn.service.checkin;

import com.nextturn.dto.checkin.AppointmentCheckInRequest;
import com.nextturn.dto.checkin.CreateReceptionPointRequest;
import com.nextturn.dto.checkin.ReceptionPointResponse;
import com.nextturn.dto.checkin.ResolveReceptionQrResponse;
import com.nextturn.dto.checkin.WalkInCheckInRequest;
import com.nextturn.dto.ticket.TicketResponse;
import com.nextturn.entity.CentralTenant;
import com.nextturn.entity.TenantReceptionPoint;
import com.nextturn.entity.TenantService;
import com.nextturn.entity.TenantShift;
import com.nextturn.enums.ReceptionPointStatus;
import com.nextturn.enums.ReceptionQrMode;
import com.nextturn.enums.ServiceStatus;
import com.nextturn.enums.ShiftStatus;
import com.nextturn.enums.TicketCustomerType;
import com.nextturn.exception.checkin.CheckInErrorCode;
import com.nextturn.exception.checkin.CheckInException;
import com.nextturn.exception.ticket.TicketErrorCode;
import com.nextturn.exception.ticket.TicketException;
import com.nextturn.infrastructure.multitenancy.TenantContextHolder;
import com.nextturn.repository.appointment.TenantAppointmentRepository;
import com.nextturn.repository.auth.CentralTenantRepository;
import com.nextturn.repository.calendar.TenantShiftRepository;
import com.nextturn.repository.checkin.TenantReceptionPointRepository;
import com.nextturn.repository.service.TenantServiceRepository;
import com.nextturn.repository.ticket.TenantQueueTicketRepository;
import com.nextturn.service.checkin.impl.TenantCheckInServiceImpl;
import com.nextturn.service.checkin.impl.TenantReceptionPointServiceImpl;
import com.nextturn.service.checkin.impl.TenantReceptionQrTokenServiceImpl;
import com.nextturn.service.ticket.TenantTicketService;
import com.nextturn.service.ticket.impl.TenantTicketValidatorImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TenantCheckInServiceTest {

    private static final String TENANT_CODE = "clinic-alpha";

    @Mock
    private TenantReceptionPointRepository receptionPointRepository;

    @Mock
    private TenantServiceRepository serviceRepository;

    @Mock
    private CentralTenantRepository centralTenantRepository;

    @Mock
    private TenantTicketService ticketService;

    @Mock
    private TenantAppointmentRepository appointmentRepository;

    @Mock
    private TenantQueueTicketRepository ticketRepository;

    @Mock
    private TenantShiftRepository shiftRepository;

    private TenantReceptionQrTokenServiceImpl qrTokenService;
    private TenantReceptionPointServiceImpl receptionPointService;
    private TenantCheckInServiceImpl checkInService;
    private TenantService service;

    @BeforeEach
    void setUp() {
        TenantContextHolder.setTenantCode(TENANT_CODE);
        qrTokenService = new TenantReceptionQrTokenServiceImpl(receptionPointRepository, null);
        receptionPointService = new TenantReceptionPointServiceImpl(
                receptionPointRepository, serviceRepository, qrTokenService, null);
        checkInService = new TenantCheckInServiceImpl(
                qrTokenService, ticketService, serviceRepository, centralTenantRepository, null);

        service = TenantService.builder()
                .id("srv-001")
                .serviceCode("SVC-0001")
                .serviceName("Khám Nội")
                .standardDuration(15)
                .status(ServiceStatus.ACTIVE)
                .isDeleted(false)
                .build();
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
    }

    @Test
    @DisplayName("REQ-CHECK-IN-03: Creates reception point with generated secret and prevents duplicate codes")
    void createReceptionPoint_SuccessAndDuplicateCheck() {
        when(receptionPointRepository.existsByPointCodeIgnoreCaseAndIsDeletedFalse("REC-LOBBY-1")).thenReturn(false);
        when(serviceRepository.findByIdAndIsDeletedFalse("srv-001")).thenReturn(Optional.of(service));
        when(receptionPointRepository.save(any(TenantReceptionPoint.class))).thenAnswer(inv -> inv.getArgument(0));

        ReceptionPointResponse created = receptionPointService.createReceptionPoint(CreateReceptionPointRequest.builder()
                .pointCode("rec-lobby-1")
                .pointName("Quầy Tiếp Đón Chính")
                .locationLabel("Tầng 1")
                .serviceId("srv-001")
                .qrMode(ReceptionQrMode.DYNAMIC)
                .rotationSeconds(60)
                .build());

        assertThat(created.getPointCode()).isEqualTo("REC-LOBBY-1");
        assertThat(created.getQrMode()).isEqualTo(ReceptionQrMode.DYNAMIC);
        assertThat(created.getStatus()).isEqualTo(ReceptionPointStatus.ACTIVE);

        when(receptionPointRepository.existsByPointCodeIgnoreCaseAndIsDeletedFalse("REC-LOBBY-1")).thenReturn(true);
        assertThatThrownBy(() -> receptionPointService.createReceptionPoint(CreateReceptionPointRequest.builder()
                .pointCode("REC-LOBBY-1")
                .pointName("Duplicate")
                .build()))
                .isInstanceOf(CheckInException.class)
                .hasFieldOrPropertyWithValue("code", CheckInErrorCode.RECEPTION_POINT_CODE_EXISTS.code());
    }

    @Test
    @DisplayName("REQ-CHECK-IN-03: Resolves dynamic lobby QR even when X-Tenant-Code header is omitted")
    void resolveReceptionQr_ExtractsTenantAndReturnsActiveServices() {
        TenantReceptionPoint lobbyPoint = TenantReceptionPoint.builder()
                .id("rp-lobby")
                .pointCode("REC-MAIN")
                .pointName("Quầy Sảnh Chính")
                .qrMode(ReceptionQrMode.DYNAMIC)
                .qrSecret("secret-key-for-lobby-point")
                .rotationSeconds(60)
                .status(ReceptionPointStatus.ACTIVE)
                .isDeleted(false)
                .build();

        String dynamicToken = qrTokenService.generateQrPayload(lobbyPoint, TENANT_CODE).getQrToken();

        // Simulate mobile phone camera scan without X-Tenant-Code header
        TenantContextHolder.clear();
        when(receptionPointRepository.findByIdWithServiceAndIsDeletedFalse("rp-lobby"))
                .thenReturn(Optional.of(lobbyPoint));
        when(serviceRepository.findAllByStatusAndIsDeletedFalse(ServiceStatus.ACTIVE))
                .thenReturn(List.of(service));
        when(centralTenantRepository.findByTenantCodeIgnoreCaseAndIsDeletedFalse(TENANT_CODE))
                .thenReturn(Optional.of(CentralTenant.builder().tenantCode(TENANT_CODE).tenantName("Phòng khám Alpha").build()));

        ResolveReceptionQrResponse resolved = checkInService.resolveReceptionQr(dynamicToken);

        assertThat(resolved.getTenantCode()).isEqualTo(TENANT_CODE);
        assertThat(resolved.getTenantName()).isEqualTo("Phòng khám Alpha");
        assertThat(resolved.getPointCode()).isEqualTo("REC-MAIN");
        assertThat(resolved.getAvailableServices()).hasSize(1);
        assertThat(resolved.getSupportsAppointmentCheckIn()).isTrue();
        assertThat(resolved.getSupportsWalkIn()).isTrue();
    }

    @Test
    @DisplayName("REQ-CHECK-IN-01 & 02: Delegates appointment and walk-in check-in to ticket issuance service")
    void checkInAppointmentAndWalkIn_DelegatesToTicketService() {
        TicketResponse expected = TicketResponse.builder()
                .ticketCode("A-001")
                .customerType(TicketCustomerType.WALK_IN)
                .build();
        when(ticketService.issueTicket(any())).thenReturn(expected);

        TicketResponse walkIn = checkInService.checkInWalkIn(WalkInCheckInRequest.builder()
                .serviceId("srv-001")
                .customerPhone("0901234567")
                .receptionQrToken("NTRD.test")
                .build());
        assertThat(walkIn.getTicketCode()).isEqualTo("A-001");

        TicketResponse apt = checkInService.checkInAppointment(AppointmentCheckInRequest.builder()
                .qrToken("NTQR-APT-1")
                .receptionQrToken("NTRD.test")
                .build());
        assertThat(apt.getTicketCode()).isEqualTo("A-001");

        verify(ticketService, org.mockito.Mockito.times(2)).issueTicket(any());
    }

    @Test
    @DisplayName("REQ-CHECK-IN-02: Enforces active shift window & remaining capacity for walk-in eligibility")
    void walkInShiftValidation_EnforcesActiveShiftAndCapacity() {
        LocalDate today = LocalDate.of(2026, 11, 22);
        Instant tenAm = today.atTime(10, 0).atZone(ZoneId.systemDefault()).toInstant();
        TenantTicketValidatorImpl validatorAt10Am = new TenantTicketValidatorImpl(
                serviceRepository, appointmentRepository, ticketRepository,
                shiftRepository, null, null,
                Clock.fixed(tenAm, ZoneId.systemDefault())
        );

        // 1. No shift scheduled today -> rejected
        when(shiftRepository.searchShifts(today, null, null, null, "srv-001", ShiftStatus.SCHEDULED))
                .thenReturn(List.of());
        assertThatThrownBy(() -> validatorAt10Am.validateWalkInEligibility(service, "0901234567", today))
                .isInstanceOf(TicketException.class)
                .hasFieldOrPropertyWithValue("code", TicketErrorCode.WALK_IN_CAPACITY_FULL.code());

        // 2. Shift already ended at 09:30 (now is 10:00) -> rejected
        TenantShift endedShift = TenantShift.builder()
                .shiftStart(LocalTime.of(8, 0))
                .shiftEnd(LocalTime.of(9, 30))
                .status(ShiftStatus.SCHEDULED)
                .build();
        when(shiftRepository.searchShifts(today, null, null, null, "srv-001", ShiftStatus.SCHEDULED))
                .thenReturn(List.of(endedShift));
        assertThatThrownBy(() -> validatorAt10Am.validateWalkInEligibility(service, "0901234567", today))
                .isInstanceOf(TicketException.class)
                .hasFieldOrPropertyWithValue("code", TicketErrorCode.WALK_IN_CAPACITY_FULL.code());

        // 3. Active shift 08:00 - 12:00 (240 mins / 15 mins = 16 max capacity); 15 issued -> allowed
        TenantShift activeShift = TenantShift.builder()
                .shiftStart(LocalTime.of(8, 0))
                .shiftEnd(LocalTime.of(12, 0))
                .status(ShiftStatus.SCHEDULED)
                .build();
        when(shiftRepository.searchShifts(today, null, null, null, "srv-001", ShiftStatus.SCHEDULED))
                .thenReturn(List.of(activeShift));
        when(ticketRepository.countByServiceIdAndWorkDate("srv-001", today)).thenReturn(15L);
        assertThatCode(() -> validatorAt10Am.validateWalkInEligibility(service, "0901234567", today))
                .doesNotThrowAnyException();

        // 4. Capacity reached (16 issued >= 16 max) -> rejected
        when(ticketRepository.countByServiceIdAndWorkDate("srv-001", today)).thenReturn(16L);
        assertThatThrownBy(() -> validatorAt10Am.validateWalkInEligibility(service, "0901234567", today))
                .isInstanceOf(TicketException.class)
                .hasFieldOrPropertyWithValue("code", TicketErrorCode.WALK_IN_CAPACITY_FULL.code());
    }
}
