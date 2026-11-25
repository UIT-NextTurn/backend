package com.nextturn.service.appointment.impl;

import com.nextturn.dto.appointment.AppointmentResponse;
import com.nextturn.dto.appointment.CancelAppointmentRequest;
import com.nextturn.dto.appointment.CreateAppointmentRequest;
import com.nextturn.dto.appointment.RescheduleAppointmentRequest;
import com.nextturn.dto.calendar.CalendarSettingsResponse;
import com.nextturn.dto.calendar.SlotHoldingResponse;
import com.nextturn.dto.calendar.TimeSlotResponse;
import com.nextturn.entity.CentralGlobalUserAppointment;
import com.nextturn.entity.CentralTenant;
import com.nextturn.entity.TenantAppointment;
import com.nextturn.entity.TenantService;
import com.nextturn.entity.TenantTimeSlot;
import com.nextturn.enums.AppointmentStatus;
import com.nextturn.enums.TimeSlotStatus;
import com.nextturn.exception.appointment.AppointmentErrorCode;
import com.nextturn.exception.appointment.AppointmentException;
import com.nextturn.exception.calendar.CalendarErrorCode;
import com.nextturn.exception.calendar.CalendarException;
import com.nextturn.repository.appointment.CentralGlobalUserAppointmentRepository;
import com.nextturn.repository.appointment.TenantAppointmentRepository;
import com.nextturn.repository.auth.CentralTenantRepository;
import com.nextturn.repository.calendar.TenantTimeSlotRepository;
import com.nextturn.service.BaseService;
import com.nextturn.service.appointment.TenantAppointmentCodeGenerator;
import com.nextturn.service.appointment.TenantAppointmentNotificationService;
import com.nextturn.service.appointment.TenantAppointmentQueryService;
import com.nextturn.service.appointment.TenantAppointmentService;
import com.nextturn.service.appointment.TenantAppointmentValidator;
import com.nextturn.service.calendar.TenantCalendarSettingsService;
import com.nextturn.service.calendar.TenantTimeSlotGeneratorService;
import com.nextturn.service.calendar.TenantTimeSlotHoldingService;
import com.nextturn.service.calendar.TenantTimeSlotQueryService;
import com.nextturn.service.notification.EmailService;
import com.nextturn.infrastructure.logging.LogContract;
import com.nextturn.infrastructure.logging.LogDomain;
import com.nextturn.infrastructure.logging.LogOutcome;
import org.hibernate.Hibernate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Primary orchestrator implementation of {@link TenantAppointmentService} coordinating transactional
 * appointment booking, rescheduling, and cancellation while delegating specialized responsibilities
 * (validation, code generation, read queries, and email notifications) to dedicated single-responsibility services.
 */
@Service
public class TenantAppointmentServiceImpl extends BaseService implements TenantAppointmentService {

    private static final Logger log = LoggerFactory.getLogger(TenantAppointmentServiceImpl.class);

    private final TenantAppointmentRepository appointmentRepository;
    private final TenantTimeSlotRepository timeSlotRepository;
    private final TenantTimeSlotHoldingService timeSlotHoldingService;
    private final TenantTimeSlotGeneratorService timeSlotGeneratorService;
    private final TenantCalendarSettingsService calendarSettingsService;
    private final CentralGlobalUserAppointmentRepository globalUserAppointmentRepository;
    private final CentralTenantRepository centralTenantRepository;
    private final TenantAppointmentCodeGenerator codeGenerator;
    private final TenantAppointmentValidator validator;
    private final TenantAppointmentNotificationService notificationService;
    private final TenantAppointmentQueryService queryService;
    private com.nextturn.service.dashboard.DashboardAnalyticsSyncService dashboardAnalyticsSyncService;

    @Autowired(required = false)
    public void setDashboardAnalyticsSyncService(
            com.nextturn.service.dashboard.DashboardAnalyticsSyncService dashboardAnalyticsSyncService) {
        this.dashboardAnalyticsSyncService = dashboardAnalyticsSyncService;
    }

    @Autowired
    public TenantAppointmentServiceImpl(
            TenantAppointmentRepository appointmentRepository,
            TenantTimeSlotRepository timeSlotRepository,
            TenantTimeSlotQueryService timeSlotQueryService,
            TenantTimeSlotHoldingService timeSlotHoldingService,
            TenantTimeSlotGeneratorService timeSlotGeneratorService,
            TenantCalendarSettingsService calendarSettingsService,
            @Autowired(required = false) CentralGlobalUserAppointmentRepository globalUserAppointmentRepository,
            @Autowired(required = false) CentralTenantRepository centralTenantRepository,
            @Autowired(required = false) EmailService emailService,
            @Autowired(required = false) TenantAppointmentCodeGenerator codeGenerator,
            @Autowired(required = false) TenantAppointmentValidator validator,
            @Autowired(required = false) TenantAppointmentNotificationService notificationService,
            @Autowired(required = false) TenantAppointmentQueryService queryService,
            @Autowired(required = false) TransactionTemplate transactionTemplate
    ) {
        super(transactionTemplate);
        this.appointmentRepository = appointmentRepository;
        this.timeSlotRepository = timeSlotRepository;
        this.timeSlotHoldingService = timeSlotHoldingService;
        this.timeSlotGeneratorService = timeSlotGeneratorService;
        this.calendarSettingsService = calendarSettingsService;
        this.globalUserAppointmentRepository = globalUserAppointmentRepository;
        this.centralTenantRepository = centralTenantRepository;
        this.codeGenerator = codeGenerator != null
                ? codeGenerator
                : new TenantAppointmentCodeGeneratorImpl(appointmentRepository);
        this.validator = validator != null
                ? validator
                : new TenantAppointmentValidatorImpl(appointmentRepository, timeSlotRepository);
        this.notificationService = notificationService != null
                ? notificationService
                : new TenantAppointmentNotificationServiceImpl(emailService);
        this.queryService = queryService != null
                ? queryService
                : new TenantAppointmentQueryServiceImpl(
                        appointmentRepository,
                        timeSlotQueryService,
                        calendarSettingsService,
                        globalUserAppointmentRepository,
                        centralTenantRepository,
                        this.validator,
                        transactionTemplate
                );
    }

    /**
     * Backward-compatible constructor for unit tests using {@code @InjectMocks}.
     */
    public TenantAppointmentServiceImpl(
            TenantAppointmentRepository appointmentRepository,
            TenantTimeSlotRepository timeSlotRepository,
            TenantTimeSlotQueryService timeSlotQueryService,
            TenantTimeSlotHoldingService timeSlotHoldingService,
            TenantTimeSlotGeneratorService timeSlotGeneratorService,
            TenantCalendarSettingsService calendarSettingsService,
            CentralGlobalUserAppointmentRepository globalUserAppointmentRepository,
            CentralTenantRepository centralTenantRepository,
            EmailService emailService,
            TransactionTemplate transactionTemplate
    ) {
        this(
                appointmentRepository,
                timeSlotRepository,
                timeSlotQueryService,
                timeSlotHoldingService,
                timeSlotGeneratorService,
                calendarSettingsService,
                globalUserAppointmentRepository,
                centralTenantRepository,
                emailService,
                null,
                null,
                null,
                null,
                transactionTemplate
        );
    }

    // =========================================================================
    // Delegated Read Queries (REQ-APT-01, REQ-APT-04 Lookup, REQ-APT-05 History)
    // =========================================================================

    @Override
    public List<TimeSlotResponse> getAvailableSlotsForBooking(String serviceId, LocalDate appointmentDate) {
        return queryService.getAvailableSlotsForBooking(serviceId, appointmentDate);
    }

    @Override
    public AppointmentResponse lookupAppointment(String appointmentCode, String customerPhone) {
        return queryService.lookupAppointment(appointmentCode, customerPhone);
    }

    @Override
    public List<AppointmentResponse> getCustomerAppointmentsForCurrentTenant(String authenticatedUserId) {
        return queryService.getCustomerAppointmentsForCurrentTenant(authenticatedUserId);
    }

    @Override
    public List<AppointmentResponse> getAllTenantAppointments() {
        return queryService.getAllTenantAppointments();
    }

    @Override
    public AppointmentResponse getAppointmentById(String appointmentId) {
        return queryService.getAppointmentById(appointmentId);
    }

    // =========================================================================
    // REQ-APT-02: Temporary Slot Holding (10-Minute Distributed Lease)
    // =========================================================================

    @Override
    public SlotHoldingResponse holdSlotForBooking(String slotId, Integer holdingMinutes) {
        int duration = (holdingMinutes != null && holdingMinutes > 0) ? holdingMinutes : 10;
        try {
            return timeSlotHoldingService.holdSlot(slotId, duration);
        } catch (CalendarException ex) {
            if (CalendarErrorCode.SLOT_ALREADY_HOLDING_OR_BOOKED.code().equals(ex.getCode())) {
                throw new AppointmentException(
                        AppointmentErrorCode.SLOT_UNAVAILABLE,
                        "Khung giờ này vừa được người khác chọn giữ chỗ. Vui lòng chọn khung giờ khác."
                );
            }
            throw ex;
        }
    }

    // =========================================================================
    // REQ-APT-03: Confirm Customer Information & Create Appointment
    // =========================================================================

    @Override
    public AppointmentResponse createAppointment(CreateAppointmentRequest request, String authenticatedUserId) {
        if (request == null) {
            throw new AppointmentException(AppointmentErrorCode.INVALID_CUSTOMER_INFO);
        }

        String tenantCode = resolveCurrentTenantCode();
        CentralTenant centralTenant = resolveCentralTenant(tenantCode);
        String tenantName = centralTenant != null ? centralTenant.getTenantName() : tenantCode;

        CalendarSettingsResponse settings = resolveSettings();
        int minLeadHours = settings.getMinLeadTimeHours() != null ? settings.getMinLeadTimeHours() : 2;
        int cancelDeadlineHours = settings.getCancellationDeadlineHours() != null ? settings.getCancellationDeadlineHours() : 2;

        String cleanSlotId = request.getSlotId().trim();
        String cleanHoldingToken = request.getHoldingToken().trim();
        String cleanName = request.getCustomerName().trim();
        String cleanPhone = request.getCustomerPhone().trim();
        String cleanEmail = (request.getCustomerEmail() != null && !request.getCustomerEmail().isBlank())
                ? request.getCustomerEmail().trim().toLowerCase()
                : null;
        String cleanNotes = (request.getNotes() != null && !request.getNotes().isBlank())
                ? request.getNotes().trim()
                : null;

        TenantAppointment savedAppointment = executeInTransaction(status -> {
            TenantTimeSlot slot = timeSlotRepository.findByIdForUpdate(cleanSlotId)
                    .orElseThrow(() -> new AppointmentException(AppointmentErrorCode.APPOINTMENT_NOT_FOUND, "Khung giờ không tồn tại trong hệ thống."));

            LocalDateTime now = LocalDateTime.now();

            validator.validateHoldingSession(slot, cleanHoldingToken, now);
            validator.validateMinLeadTime(slot, minLeadHours, now);
            validator.validateNoDoubleBooking(slot.getId(), cleanPhone);

            occupySlotCapacity(slot);

            TenantService service = slot.getService();
            String appointmentCode = codeGenerator.generateAppointmentCode(slot.getWorkDate());
            String qrToken = codeGenerator.generateQrToken(tenantCode, appointmentCode);

            TenantAppointment appointment = TenantAppointment.builder()
                    .id(UUID.randomUUID().toString())
                    .appointmentCode(appointmentCode)
                    .userId(authenticatedUserId)
                    .service(service)
                    .slot(slot)
                    .customerName(cleanName)
                    .customerPhone(cleanPhone)
                    .customerEmail(cleanEmail)
                    .notes(cleanNotes)
                    .qrToken(qrToken)
                    .status(AppointmentStatus.CONFIRMED)
                    .build();

            TenantAppointment saved = appointmentRepository.save(appointment);
            initializeLazyAssociations(saved);
            return saved;
        });

        indexGlobalUserAppointment(savedAppointment, authenticatedUserId, tenantCode, centralTenant);
        resyncValkeySlotCache(savedAppointment.getService(), savedAppointment.getSlot());
        notificationService.sendConfirmationEmailAsync(savedAppointment, tenantName);

        boolean canModify = validator.canCustomerModifyAppointment(savedAppointment, cancelDeadlineHours);
        LogContract.info(log, LogDomain.APPOINTMENT, "appointment_created")
                .outcome(LogOutcome.SUCCESS)
                .tenant(tenantCode)
                .entity("appointment", savedAppointment.getAppointmentCode())
                .phone(cleanPhone)
                .emit();
        return AppointmentResponse.fromEntity(savedAppointment, tenantCode, tenantName, canModify);
    }

    // =========================================================================
    // REQ-APT-04: Cancel & Reschedule Appointment
    // =========================================================================

    @Override
    public AppointmentResponse cancelAppointment(
            String appointmentIdentifier,
            CancelAppointmentRequest request,
            String authenticatedUserId,
            boolean isAdminOrStaff
    ) {
        if (request == null || request.getCancellationReason() == null || request.getCancellationReason().isBlank()) {
            throw new AppointmentException(AppointmentErrorCode.INVALID_CUSTOMER_INFO, "Vui lòng nhập lý do hủy lịch hẹn.");
        }

        String tenantCode = resolveCurrentTenantCode();
        CentralTenant centralTenant = resolveCentralTenant(tenantCode);
        String tenantName = centralTenant != null ? centralTenant.getTenantName() : tenantCode;
        int cancelDeadlineHours = resolveCancellationDeadlineHours();

        TenantAppointment cancelled = executeInTransaction(status -> {
            TenantAppointment appointment = validator.resolveAndAuthorizeAppointment(
                    appointmentIdentifier,
                    request.getCustomerPhone(),
                    authenticatedUserId,
                    isAdminOrStaff
            );
            validator.validateCanModifyOrCancel(appointment, cancelDeadlineHours, isAdminOrStaff);

            TenantTimeSlot oldSlot = timeSlotRepository.findByIdForUpdate(appointment.getSlot().getId())
                    .orElse(appointment.getSlot());
            releaseSlotCapacity(oldSlot);

            appointment.setStatus(AppointmentStatus.CANCELLED);
            appointment.setCancellationReason(request.getCancellationReason().trim());
            TenantAppointment saved = appointmentRepository.save(appointment);
            initializeLazyAssociations(saved);
            return saved;
        });

        resyncValkeySlotCache(cancelled.getService(), cancelled.getSlot());
        notificationService.sendCancellationEmailAsync(cancelled, tenantName);
        if (dashboardAnalyticsSyncService != null) {
            dashboardAnalyticsSyncService.onAppointmentCancelled(tenantCode, cancelled);
        }

        LogContract.info(log, LogDomain.APPOINTMENT, "appointment_cancelled")
                .outcome(LogOutcome.SUCCESS)
                .tenant(tenantCode)
                .entity("appointment", cancelled.getAppointmentCode())
                .reason(cancelled.getCancellationReason())
                .emit();
        return AppointmentResponse.fromEntity(cancelled, tenantCode, tenantName, false);
    }

    @Override
    public AppointmentResponse rescheduleAppointment(
            String appointmentIdentifier,
            RescheduleAppointmentRequest request,
            String authenticatedUserId,
            boolean isAdminOrStaff
    ) {
        if (request == null || request.getNewSlotId() == null || request.getNewSlotId().isBlank()) {
            throw new AppointmentException(AppointmentErrorCode.INVALID_CUSTOMER_INFO, "Mã khung giờ mới là bắt buộc.");
        }

        String tenantCode = resolveCurrentTenantCode();
        CentralTenant centralTenant = resolveCentralTenant(tenantCode);
        String tenantName = centralTenant != null ? centralTenant.getTenantName() : tenantCode;

        CalendarSettingsResponse settings = resolveSettings();
        int cancelDeadlineHours = settings.getCancellationDeadlineHours() != null ? settings.getCancellationDeadlineHours() : 2;
        int minLeadHours = settings.getMinLeadTimeHours() != null ? settings.getMinLeadTimeHours() : 2;
        int maxAdvanceDays = settings.getMaxAdvanceBookingDays() != null ? settings.getMaxAdvanceBookingDays() : 30;
        String cleanNewSlotId = request.getNewSlotId().trim();

        record RescheduledResult(TenantAppointment appointment, LocalDate oldDate, LocalDate newDate, String serviceId) {}

        RescheduledResult result = executeInTransaction(status -> {
            TenantAppointment appointment = validator.resolveAndAuthorizeAppointment(
                    appointmentIdentifier,
                    request.getCustomerPhone(),
                    authenticatedUserId,
                    isAdminOrStaff
            );
            validator.validateCanModifyOrCancel(appointment, cancelDeadlineHours, isAdminOrStaff);

            TenantTimeSlot oldSlot = timeSlotRepository.findByIdForUpdate(appointment.getSlot().getId())
                    .orElseThrow(() -> new AppointmentException(AppointmentErrorCode.APPOINTMENT_NOT_FOUND));

            if (oldSlot.getId().equals(cleanNewSlotId)) {
                initializeLazyAssociations(appointment);
                return new RescheduledResult(appointment, oldSlot.getWorkDate(), oldSlot.getWorkDate(), appointment.getService().getId());
            }

            TenantTimeSlot newSlot = timeSlotRepository.findByIdForUpdate(cleanNewSlotId)
                    .orElseThrow(() -> new AppointmentException(AppointmentErrorCode.APPOINTMENT_NOT_FOUND, "Khung giờ mới không tồn tại."));

            LocalDateTime now = LocalDateTime.now();
            validator.validateAdvanceBookingWindow(newSlot.getWorkDate(), maxAdvanceDays);
            if (!isAdminOrStaff) {
                validator.validateMinLeadTime(newSlot, minLeadHours, now);
            }

            validateNewSlotAvailableForReschedule(newSlot, request.getHoldingToken(), now);
            releaseSlotCapacity(oldSlot);
            occupySlotCapacity(newSlot);

            LocalDate oldDate = oldSlot.getWorkDate();
            LocalDate newDate = newSlot.getWorkDate();
            appointment.setSlot(newSlot);
            if (newSlot.getService() != null) {
                appointment.setService(newSlot.getService());
            }
            TenantAppointment saved = appointmentRepository.save(appointment);
            initializeLazyAssociations(saved);
            return new RescheduledResult(saved, oldDate, newDate, saved.getService().getId());
        });

        if (timeSlotGeneratorService != null) {
            timeSlotGeneratorService.syncAvailableSlotsToValkey(result.serviceId(), result.oldDate());
            if (!result.oldDate().equals(result.newDate())) {
                timeSlotGeneratorService.syncAvailableSlotsToValkey(result.serviceId(), result.newDate());
            }
        }

        boolean canModify = validator.canCustomerModifyAppointment(result.appointment(), cancelDeadlineHours);
        LogContract.info(log, LogDomain.APPOINTMENT, "appointment_rescheduled")
                .outcome(LogOutcome.SUCCESS)
                .tenant(tenantCode)
                .entity("appointment", result.appointment().getAppointmentCode())
                .field("newSlotId", cleanNewSlotId)
                .field("newDate", result.newDate())
                .emit();
        return AppointmentResponse.fromEntity(result.appointment(), tenantCode, tenantName, canModify);
    }

    // =========================================================================
    // Internal Slot & Index Helpers
    // =========================================================================

    private void occupySlotCapacity(TenantTimeSlot slot) {
        int currentBooked = slot.getBookedCount() != null ? slot.getBookedCount() : 0;
        int capacity = slot.getCapacityPerSlot() != null ? slot.getCapacityPerSlot() : 1;
        if (currentBooked >= capacity) {
            throw new AppointmentException(
                    AppointmentErrorCode.SLOT_UNAVAILABLE,
                    "Khung giờ này đã đạt giới hạn tiếp nhận tối đa."
            );
        }
        int updatedBooked = currentBooked + 1;
        slot.setBookedCount(updatedBooked);
        slot.setHoldingToken(null);
        slot.setHoldingExpiresAt(null);
        slot.setStatus(updatedBooked >= capacity ? TimeSlotStatus.BOOKED : TimeSlotStatus.AVAILABLE);
        timeSlotRepository.save(slot);
    }

    private void releaseSlotCapacity(TenantTimeSlot slot) {
        int currentBooked = slot.getBookedCount() != null ? slot.getBookedCount() : 1;
        int newBooked = Math.max(0, currentBooked - 1);
        slot.setBookedCount(newBooked);
        int capacity = slot.getCapacityPerSlot() != null ? slot.getCapacityPerSlot() : 1;
        if (newBooked < capacity && slot.getStatus() == TimeSlotStatus.BOOKED) {
            slot.setStatus(TimeSlotStatus.AVAILABLE);
        }
        timeSlotRepository.save(slot);
    }

    private void validateNewSlotAvailableForReschedule(TenantTimeSlot newSlot, String holdingToken, LocalDateTime now) {
        int booked = newSlot.getBookedCount() != null ? newSlot.getBookedCount() : 0;
        int capacity = newSlot.getCapacityPerSlot() != null ? newSlot.getCapacityPerSlot() : 1;
        if (newSlot.getStatus() == TimeSlotStatus.BOOKED || booked >= capacity) {
            throw new AppointmentException(
                    AppointmentErrorCode.SLOT_UNAVAILABLE,
                    "Khung giờ này vừa được người khác chọn giữ chỗ. Vui lòng chọn khung giờ khác."
            );
        }
        if (newSlot.getStatus() == TimeSlotStatus.HOLDING
                && newSlot.getHoldingExpiresAt() != null
                && newSlot.getHoldingExpiresAt().isAfter(now)) {
            String cleanToken = holdingToken != null ? holdingToken.trim() : null;
            if (cleanToken == null || !cleanToken.equals(newSlot.getHoldingToken())) {
                throw new AppointmentException(
                        AppointmentErrorCode.SLOT_UNAVAILABLE,
                        "Khung giờ này vừa được người khác chọn giữ chỗ. Vui lòng chọn khung giờ khác."
                );
            }
        }
    }

    private void indexGlobalUserAppointment(
            TenantAppointment savedAppointment,
            String authenticatedUserId,
            String tenantCode,
            CentralTenant centralTenant
    ) {
        if (authenticatedUserId == null || authenticatedUserId.isBlank() || globalUserAppointmentRepository == null) {
            return;
        }
        try {
            String tenantId = centralTenant != null ? centralTenant.getId() : tenantCode;
            CentralGlobalUserAppointment globalIndex = CentralGlobalUserAppointment.builder()
                    .id(UUID.randomUUID().toString())
                    .userId(authenticatedUserId)
                    .tenantId(tenantId)
                    .appointmentId(savedAppointment.getId())
                    .tenantCode(tenantCode)
                    .appointmentCode(savedAppointment.getAppointmentCode())
                    .build();
            globalUserAppointmentRepository.save(globalIndex);
        } catch (Exception ex) {
            LogContract.warn(log, LogDomain.APPOINTMENT, "global_index_sync")
                    .outcome(LogOutcome.DEGRADED)
                    .entity("appointment", savedAppointment.getAppointmentCode())
                    .field("userId", authenticatedUserId)
                    .cause(ex)
                    .emit();
        }
    }

    private void resyncValkeySlotCache(TenantService service, TenantTimeSlot slot) {
        if (timeSlotGeneratorService != null && service != null && slot != null) {
            timeSlotGeneratorService.syncAvailableSlotsToValkey(service.getId(), slot.getWorkDate());
        }
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

    private CentralTenant resolveCentralTenant(String tenantCode) {
        if (centralTenantRepository == null || tenantCode == null || tenantCode.isBlank()) {
            return null;
        }
        return centralTenantRepository.findByTenantCodeIgnoreCaseAndIsDeletedFalse(tenantCode).orElse(null);
    }

    /**
     * Eagerly initializes lazy {@code service} and {@code slot} associations before the persistence
     * context closes ({@code spring.jpa.open-in-view=false}) so post-commit notifications, cache
     * sync, and response DTO mapping never trigger {@code LazyInitializationException}.
     */
    private void initializeLazyAssociations(TenantAppointment appointment) {
        if (appointment == null) {
            return;
        }
        Hibernate.initialize(appointment.getService());
        Hibernate.initialize(appointment.getSlot());
    }
}
