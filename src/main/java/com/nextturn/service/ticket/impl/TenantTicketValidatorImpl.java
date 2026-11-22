package com.nextturn.service.ticket.impl;

import com.nextturn.dto.ticket.IssueTicketRequest;
import com.nextturn.entity.CentralTenant;
import com.nextturn.entity.CentralTenantQuota;
import com.nextturn.entity.TenantAppointment;
import com.nextturn.entity.TenantService;
import com.nextturn.entity.TenantShift;
import com.nextturn.enums.AppointmentStatus;
import com.nextturn.enums.ServiceStatus;
import com.nextturn.enums.ShiftStatus;
import com.nextturn.enums.TicketStatus;
import com.nextturn.exception.appointment.AppointmentErrorCode;
import com.nextturn.exception.appointment.AppointmentException;
import com.nextturn.exception.ticket.TicketErrorCode;
import com.nextturn.exception.ticket.TicketException;
import com.nextturn.repository.appointment.TenantAppointmentRepository;
import com.nextturn.repository.auth.CentralTenantRepository;
import com.nextturn.repository.calendar.TenantShiftRepository;
import com.nextturn.repository.service.TenantServiceRepository;
import com.nextturn.repository.tenant.CentralTenantQuotaRepository;
import com.nextturn.repository.ticket.TenantQueueTicketRepository;
import com.nextturn.service.ticket.TenantTicketValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

/**
 * Default implementation of {@link TenantTicketValidator} enforcing service availability,
 * appointment QR check-in time windows ({@code [-15m, +10m]}), walk-in capacity, duplicate prevention,
 * and tenant daily ticket quotas (REQ-TICKET-01, REQ-CHECK-IN-01, REQ-CHECK-IN-02).
 */
@Component
public class TenantTicketValidatorImpl implements TenantTicketValidator {

    private static final Logger log = LoggerFactory.getLogger(TenantTicketValidatorImpl.class);
    private static final int CHECKIN_EARLY_MINUTES = 15;
    private static final int CHECKIN_LATE_MINUTES = 10;

    private final TenantServiceRepository serviceRepository;
    private final TenantAppointmentRepository appointmentRepository;
    private final TenantQueueTicketRepository ticketRepository;
    private final TenantShiftRepository shiftRepository;
    private final CentralTenantRepository centralTenantRepository;
    private final CentralTenantQuotaRepository quotaRepository;

    @Autowired
    public TenantTicketValidatorImpl(
            TenantServiceRepository serviceRepository,
            TenantAppointmentRepository appointmentRepository,
            TenantQueueTicketRepository ticketRepository,
            @Autowired(required = false) TenantShiftRepository shiftRepository,
            @Autowired(required = false) CentralTenantRepository centralTenantRepository,
            @Autowired(required = false) CentralTenantQuotaRepository quotaRepository
    ) {
        this.serviceRepository = serviceRepository;
        this.appointmentRepository = appointmentRepository;
        this.ticketRepository = ticketRepository;
        this.shiftRepository = shiftRepository;
        this.centralTenantRepository = centralTenantRepository;
        this.quotaRepository = quotaRepository;
    }

    @Override
    public TenantService validateActiveService(String serviceId) {
        if (serviceId == null || serviceId.isBlank()) {
            throw new TicketException(TicketErrorCode.SERVICE_UNAVAILABLE_FOR_TICKET);
        }
        TenantService service = serviceRepository.findByIdAndIsDeletedFalse(serviceId.trim())
                .orElseThrow(() -> new TicketException(TicketErrorCode.SERVICE_UNAVAILABLE_FOR_TICKET));
        if (service.getStatus() != ServiceStatus.ACTIVE) {
            throw new TicketException(TicketErrorCode.SERVICE_UNAVAILABLE_FOR_TICKET);
        }
        return service;
    }

    @Override
    public TenantAppointment validateAppointmentForCheckIn(IssueTicketRequest request, LocalDate today) {
        TenantAppointment appointment = resolveAppointment(request);

        if (appointment.getStatus() == AppointmentStatus.CANCELLED || appointment.getStatus() == AppointmentStatus.NO_SHOW) {
            throw new AppointmentException(AppointmentErrorCode.APPOINTMENT_NOT_FOUND, "Cuộc hẹn đã bị hủy hoặc đánh dấu vắng mặt.");
        }

        if (appointment.getSlot() == null || appointment.getSlot().getWorkDate() == null) {
            throw new AppointmentException(AppointmentErrorCode.APPOINTMENT_NOT_FOUND);
        }

        LocalDate appointmentDate = appointment.getSlot().getWorkDate();
        if (!appointmentDate.equals(today)) {
            throw new TicketException(TicketErrorCode.APPOINTMENT_WRONG_DATE);
        }

        // If already checked in, allow caller to handle idempotency
        if (appointment.getStatus() == AppointmentStatus.CHECKED_IN) {
            return appointment;
        }

        if (appointment.getStatus() != AppointmentStatus.CONFIRMED) {
            throw new AppointmentException(AppointmentErrorCode.APPOINTMENT_NOT_CONFIRMED);
        }

        // Check-in time window: [appointmentStart - 15 minutes, appointmentStart + 10 minutes] (REQ-CHECK-IN-01)
        LocalTime slotStart = appointment.getSlot().getSlotStart();
        if (slotStart != null) {
            LocalDateTime appointmentStart = LocalDateTime.of(appointmentDate, slotStart);
            LocalDateTime now = LocalDateTime.now();
            LocalDateTime earliestAllowed = appointmentStart.minusMinutes(CHECKIN_EARLY_MINUTES);
            LocalDateTime latestAllowed = appointmentStart.plusMinutes(CHECKIN_LATE_MINUTES);

            if (now.isBefore(earliestAllowed)) {
                String msg = String.format(
                        "Bạn đến quá sớm so với giờ hẹn (%s). Vui lòng quét mã lại trong khoảng thời gian từ %s để được xếp hàng.",
                        slotStart,
                        earliestAllowed.toLocalTime()
                );
                throw new TicketException(TicketErrorCode.CHECKIN_TOO_EARLY, msg);
            }

            if (now.isAfter(latestAllowed)) {
                appointment.setStatus(AppointmentStatus.NO_SHOW);
                appointmentRepository.save(appointment);
                throw new TicketException(TicketErrorCode.CHECKIN_TOO_LATE);
            }
        }

        return appointment;
    }

    @Override
    public void validateWalkInEligibility(TenantService service, String customerPhone, LocalDate today) {
        if (customerPhone == null || !customerPhone.trim().matches("^[0-9]{10}$")) {
            throw new TicketException(
                    TicketErrorCode.INVALID_TICKET_STATE,
                    "Số điện thoại khách hàng phải chuẩn định dạng 10 chữ số để lấy phiếu số thứ tự."
            );
        }

        String cleanPhone = customerPhone.trim();
        if (ticketRepository != null && ticketRepository.existsByServiceIdAndCustomerPhoneAndWorkDateAndStatusIn(
                service.getId(),
                cleanPhone,
                today,
                List.of(TicketStatus.WAITING, TicketStatus.CALLING, TicketStatus.SERVING)
        )) {
            throw new TicketException(TicketErrorCode.DUPLICATE_ACTIVE_TICKET);
        }

        // Verify shift capacity if calendar shifts are scheduled for this service today (REQ-CHECK-IN-02)
        if (shiftRepository != null && ticketRepository != null) {
            List<TenantShift> todayShifts = shiftRepository.searchShifts(
                    today, null, null, null, service.getId(), ShiftStatus.SCHEDULED
            );
            if (!todayShifts.isEmpty()) {
                int standardDuration = (service.getStandardDuration() != null && service.getStandardDuration() > 0)
                        ? service.getStandardDuration()
                        : 15;
                long totalShiftMinutes = todayShifts.stream()
                        .mapToLong(s -> Duration.between(s.getShiftStart(), s.getShiftEnd()).toMinutes())
                        .sum();
                long maxShiftCapacity = Math.max(1L, totalShiftMinutes / standardDuration);
                long todayIssuedForService = ticketRepository.countByServiceIdAndWorkDate(service.getId(), today);
                if (todayIssuedForService >= maxShiftCapacity) {
                    log.warn("Walk-in capacity full for service [{}] on [{}]: issued {} >= max {}",
                            service.getServiceCode(), today, todayIssuedForService, maxShiftCapacity);
                    throw new TicketException(TicketErrorCode.WALK_IN_CAPACITY_FULL);
                }
            }
        }
    }

    @Override
    public void validateTenantDailyTicketQuota(String tenantCode, LocalDate today) {
        if (centralTenantRepository == null || quotaRepository == null || ticketRepository == null
                || tenantCode == null || tenantCode.isBlank()) {
            return;
        }
        centralTenantRepository.findByTenantCodeIgnoreCaseAndIsDeletedFalse(tenantCode)
                .flatMap(t -> quotaRepository.findByTenantId(t.getId()))
                .ifPresent(quota -> {
                    Integer dailyLimit = quota.getDailyTicketQuota();
                    if (dailyLimit != null && dailyLimit > 0) {
                        long todayTotal = ticketRepository.countByWorkDate(today);
                        if (todayTotal >= dailyLimit) {
                            log.warn("Tenant [{}] reached daily ticket quota ({}/{})", tenantCode, todayTotal, dailyLimit);
                            throw new TicketException(TicketErrorCode.DAILY_TICKET_QUOTA_EXCEEDED);
                        }
                    }
                });
    }

    private TenantAppointment resolveAppointment(IssueTicketRequest request) {
        if (appointmentRepository == null) {
            throw new AppointmentException(AppointmentErrorCode.APPOINTMENT_NOT_FOUND);
        }
        if (request.getQrToken() != null && !request.getQrToken().isBlank()) {
            return appointmentRepository.findByQrToken(request.getQrToken().trim())
                    .orElseThrow(() -> new AppointmentException(AppointmentErrorCode.APPOINTMENT_NOT_FOUND));
        }
        String identifier = request.getAppointmentId().trim();
        return appointmentRepository.findByIdWithDetails(identifier)
                .or(() -> appointmentRepository.findByAppointmentCodeIgnoreCase(identifier))
                .orElseThrow(() -> new AppointmentException(AppointmentErrorCode.APPOINTMENT_NOT_FOUND));
    }
}
