package com.nextturn.service.appointment.impl;

import com.nextturn.dto.appointment.AppointmentResponse;
import com.nextturn.dto.calendar.CalendarSettingsResponse;
import com.nextturn.dto.calendar.TimeSlotResponse;
import com.nextturn.entity.CentralGlobalUserAppointment;
import com.nextturn.entity.CentralTenant;
import com.nextturn.entity.TenantAppointment;
import com.nextturn.exception.appointment.AppointmentErrorCode;
import com.nextturn.exception.appointment.AppointmentException;
import com.nextturn.repository.appointment.CentralGlobalUserAppointmentRepository;
import com.nextturn.repository.appointment.TenantAppointmentRepository;
import com.nextturn.repository.auth.CentralTenantRepository;
import com.nextturn.service.BaseService;
import com.nextturn.service.appointment.TenantAppointmentQueryService;
import com.nextturn.service.appointment.TenantAppointmentValidator;
import com.nextturn.service.calendar.TenantCalendarSettingsService;
import com.nextturn.service.calendar.TenantTimeSlotQueryService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Default implementation of {@link TenantAppointmentQueryService} handling read-only appointment
 * and policy-filtered slot queries (REQ-APT-01, REQ-APT-04, REQ-APT-05).
 */
@Service
public class TenantAppointmentQueryServiceImpl extends BaseService implements TenantAppointmentQueryService {

    private final TenantAppointmentRepository appointmentRepository;
    private final TenantTimeSlotQueryService timeSlotQueryService;
    private final TenantCalendarSettingsService calendarSettingsService;
    private final CentralGlobalUserAppointmentRepository globalUserAppointmentRepository;
    private final CentralTenantRepository centralTenantRepository;
    private final TenantAppointmentValidator validator;

    @Autowired
    public TenantAppointmentQueryServiceImpl(
            TenantAppointmentRepository appointmentRepository,
            TenantTimeSlotQueryService timeSlotQueryService,
            TenantCalendarSettingsService calendarSettingsService,
            @Autowired(required = false) CentralGlobalUserAppointmentRepository globalUserAppointmentRepository,
            @Autowired(required = false) CentralTenantRepository centralTenantRepository,
            @Autowired(required = false) TenantAppointmentValidator validator,
            @Autowired(required = false) TransactionTemplate transactionTemplate
    ) {
        super(transactionTemplate);
        this.appointmentRepository = appointmentRepository;
        this.timeSlotQueryService = timeSlotQueryService;
        this.calendarSettingsService = calendarSettingsService;
        this.globalUserAppointmentRepository = globalUserAppointmentRepository;
        this.centralTenantRepository = centralTenantRepository;
        this.validator = validator != null ? validator : new TenantAppointmentValidatorImpl(appointmentRepository, null);
    }

    @Override
    @Transactional(readOnly = true)
    public List<TimeSlotResponse> getAvailableSlotsForBooking(String serviceId, LocalDate appointmentDate) {
        if (serviceId == null || serviceId.isBlank() || appointmentDate == null) {
            throw new AppointmentException(AppointmentErrorCode.INVALID_CUSTOMER_INFO, "Mã dịch vụ và ngày đặt hẹn là bắt buộc.");
        }

        CalendarSettingsResponse settings = resolveSettings();
        int maxAdvanceDays = settings.getMaxAdvanceBookingDays() != null ? settings.getMaxAdvanceBookingDays() : 30;
        validator.validateAdvanceBookingWindow(appointmentDate, maxAdvanceDays);

        List<TimeSlotResponse> availableSlots = timeSlotQueryService.getAvailableSlots(serviceId.trim(), appointmentDate);
        if (availableSlots == null || availableSlots.isEmpty()) {
            throw new AppointmentException(AppointmentErrorCode.NO_AVAILABLE_SLOTS);
        }
        return availableSlots;
    }

    @Override
    @Transactional(readOnly = true)
    public AppointmentResponse lookupAppointment(String appointmentCode, String customerPhone) {
        if (appointmentCode == null || appointmentCode.isBlank() || customerPhone == null || customerPhone.isBlank()) {
            throw new AppointmentException(AppointmentErrorCode.APPOINTMENT_NOT_FOUND);
        }

        String tenantCode = resolveCurrentTenantCode();
        String tenantName = resolveTenantName(tenantCode);

        TenantAppointment appointment = appointmentRepository
                .findByAppointmentCodeAndCustomerPhone(appointmentCode.trim(), customerPhone.trim())
                .orElseThrow(() -> new AppointmentException(AppointmentErrorCode.APPOINTMENT_NOT_FOUND));

        int cancelDeadlineHours = resolveCancellationDeadlineHours();
        boolean canModify = validator.canCustomerModifyAppointment(appointment, cancelDeadlineHours);
        return AppointmentResponse.fromEntity(appointment, tenantCode, tenantName, canModify);
    }

    @Override
    @Transactional(readOnly = true)
    public List<AppointmentResponse> getCustomerAppointmentsForCurrentTenant(String authenticatedUserId) {
        if (authenticatedUserId == null || authenticatedUserId.isBlank()) {
            throw new AppointmentException(AppointmentErrorCode.UNAUTHORIZED_APPOINTMENT_ACCESS);
        }

        String tenantCode = resolveCurrentTenantCode();
        String tenantName = resolveTenantName(tenantCode);
        int cancelDeadlineHours = resolveCancellationDeadlineHours();

        Map<String, TenantAppointment> merged = new LinkedHashMap<>();

        if (globalUserAppointmentRepository != null) {
            List<CentralGlobalUserAppointment> indexed = globalUserAppointmentRepository
                    .findByUserIdAndTenantCodeIgnoreCaseOrderByCreatedAtDesc(authenticatedUserId, tenantCode);
            for (CentralGlobalUserAppointment idx : indexed) {
                appointmentRepository.findByIdWithDetails(idx.getAppointmentId())
                        .ifPresent(apt -> merged.put(apt.getId(), apt));
            }
        }

        List<TenantAppointment> directMatches = appointmentRepository.findByUserIdWithDetails(authenticatedUserId);
        for (TenantAppointment apt : directMatches) {
            merged.putIfAbsent(apt.getId(), apt);
        }

        List<AppointmentResponse> responses = new ArrayList<>(merged.size());
        for (TenantAppointment apt : merged.values()) {
            boolean canModify = validator.canCustomerModifyAppointment(apt, cancelDeadlineHours);
            responses.add(AppointmentResponse.fromEntity(apt, tenantCode, tenantName, canModify));
        }
        return responses;
    }

    @Override
    @Transactional(readOnly = true)
    public List<AppointmentResponse> getAllTenantAppointments() {
        String tenantCode = resolveCurrentTenantCode();
        String tenantName = resolveTenantName(tenantCode);
        int cancelDeadlineHours = resolveCancellationDeadlineHours();

        return appointmentRepository.findAllWithDetails().stream()
                .map(apt -> AppointmentResponse.fromEntity(
                        apt,
                        tenantCode,
                        tenantName,
                        validator.canCustomerModifyAppointment(apt, cancelDeadlineHours)
                ))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public AppointmentResponse getAppointmentById(String appointmentId) {
        String tenantCode = resolveCurrentTenantCode();
        String tenantName = resolveTenantName(tenantCode);
        int cancelDeadlineHours = resolveCancellationDeadlineHours();

        TenantAppointment appointment = appointmentRepository.findByIdWithDetails(appointmentId)
                .or(() -> appointmentRepository.findByAppointmentCodeIgnoreCase(appointmentId))
                .orElseThrow(() -> new AppointmentException(AppointmentErrorCode.APPOINTMENT_NOT_FOUND));

        return AppointmentResponse.fromEntity(
                appointment,
                tenantCode,
                tenantName,
                validator.canCustomerModifyAppointment(appointment, cancelDeadlineHours)
        );
    }

    private CalendarSettingsResponse resolveSettings() {
        if (calendarSettingsService != null) {
            CalendarSettingsResponse s = calendarSettingsService.getSettings();
            if (s != null) {
                return s;
            }
        }
        return CalendarSettingsResponse.builder()
                .minLeadTimeHours(2)
                .maxAdvanceBookingDays(30)
                .cancellationDeadlineHours(2)
                .build();
    }

    private int resolveCancellationDeadlineHours() {
        CalendarSettingsResponse settings = resolveSettings();
        return settings.getCancellationDeadlineHours() != null ? settings.getCancellationDeadlineHours() : 2;
    }

    private String resolveTenantName(String tenantCode) {
        if (centralTenantRepository == null || tenantCode == null || tenantCode.isBlank()) {
            return tenantCode;
        }
        return centralTenantRepository.findByTenantCodeIgnoreCaseAndIsDeletedFalse(tenantCode)
                .map(CentralTenant::getTenantName)
                .orElse(tenantCode);
    }
}
