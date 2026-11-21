package com.nextturn.service.appointment.impl;

import com.nextturn.entity.TenantAppointment;
import com.nextturn.entity.TenantTimeSlot;
import com.nextturn.enums.AppointmentStatus;
import com.nextturn.enums.TimeSlotStatus;
import com.nextturn.exception.appointment.AppointmentErrorCode;
import com.nextturn.exception.appointment.AppointmentException;
import com.nextturn.repository.appointment.TenantAppointmentRepository;
import com.nextturn.repository.calendar.TenantTimeSlotRepository;
import com.nextturn.service.appointment.TenantAppointmentValidator;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Default implementation of {@link TenantAppointmentValidator} enforcing appointment booking,
 * holding, lead-time, and cancellation/rescheduling deadline rules (REQ-APT-01 to REQ-APT-05).
 */
@Component
public class TenantAppointmentValidatorImpl implements TenantAppointmentValidator {

    private final TenantAppointmentRepository appointmentRepository;
    private final TenantTimeSlotRepository timeSlotRepository;

    public TenantAppointmentValidatorImpl(
            TenantAppointmentRepository appointmentRepository,
            TenantTimeSlotRepository timeSlotRepository
    ) {
        this.appointmentRepository = appointmentRepository;
        this.timeSlotRepository = timeSlotRepository;
    }

    @Override
    public void validateAdvanceBookingWindow(LocalDate appointmentDate, int maxAdvanceDays) {
        LocalDate today = LocalDate.now();
        if (appointmentDate.isBefore(today) || appointmentDate.isAfter(today.plusDays(maxAdvanceDays))) {
            throw new AppointmentException(
                    AppointmentErrorCode.BOOKING_WINDOW_INVALID,
                    String.format("Ngày đặt hẹn nằm ngoài phạm vi mở lịch cho phép của cơ sở (tối đa trước %d ngày).", maxAdvanceDays)
            );
        }
    }

    @Override
    public void validateHoldingSession(TenantTimeSlot slot, String providedHoldingToken, LocalDateTime now) {
        if (slot.getStatus() != TimeSlotStatus.HOLDING) {
            if (slot.getHoldingExpiresAt() != null && slot.getHoldingExpiresAt().isBefore(now)) {
                throw new AppointmentException(AppointmentErrorCode.HOLDING_SESSION_EXPIRED);
            }
            if (slot.getStatus() == TimeSlotStatus.BOOKED) {
                throw new AppointmentException(
                        AppointmentErrorCode.SLOT_UNAVAILABLE,
                        "Khung giờ này vừa được người khác chọn giữ chỗ. Vui lòng chọn khung giờ khác."
                );
            }
            throw new AppointmentException(AppointmentErrorCode.HOLDING_SESSION_EXPIRED);
        }

        if (slot.getHoldingExpiresAt() != null && slot.getHoldingExpiresAt().isBefore(now)) {
            slot.setStatus(TimeSlotStatus.AVAILABLE);
            slot.setHoldingToken(null);
            slot.setHoldingExpiresAt(null);
            if (timeSlotRepository != null) {
                timeSlotRepository.save(slot);
            }
            throw new AppointmentException(AppointmentErrorCode.HOLDING_SESSION_EXPIRED);
        }

        if (slot.getHoldingToken() == null || !slot.getHoldingToken().equals(providedHoldingToken)) {
            throw new AppointmentException(AppointmentErrorCode.HOLDING_TOKEN_MISMATCH);
        }
    }

    @Override
    public void validateMinLeadTime(TenantTimeSlot slot, int minLeadTimeHours, LocalDateTime now) {
        LocalDateTime slotStartDateTime = LocalDateTime.of(slot.getWorkDate(), slot.getSlotStart());
        if (slotStartDateTime.isBefore(now.plusHours(minLeadTimeHours))) {
            throw new AppointmentException(
                    AppointmentErrorCode.LEAD_TIME_VIOLATED,
                    String.format(
                            "Khung giờ này đã quá thời hạn đặt trước tối thiểu (%d giờ) theo quy định của cơ sở. Vui lòng chọn khung giờ khác.",
                            minLeadTimeHours
                    )
            );
        }
    }

    @Override
    public void validateNoDoubleBooking(String slotId, String customerPhone) {
        if (appointmentRepository != null && appointmentRepository.existsBySlotIdAndCustomerPhoneAndStatusIn(
                slotId,
                customerPhone,
                List.of(AppointmentStatus.CONFIRMED, AppointmentStatus.CHECKED_IN)
        )) {
            throw new AppointmentException(
                    AppointmentErrorCode.CUSTOMER_DOUBLE_BOOKING,
                    "Số điện thoại này đã có lịch hẹn được xác nhận trong cùng khung giờ."
            );
        }
    }

    @Override
    public void validateCanModifyOrCancel(TenantAppointment appointment, int cancellationDeadlineHours, boolean isAdminOrStaff) {
        if (appointment.getStatus() != AppointmentStatus.CONFIRMED) {
            throw new AppointmentException(AppointmentErrorCode.APPOINTMENT_NOT_CONFIRMED);
        }

        if (!isAdminOrStaff && !canCustomerModifyAppointment(appointment, cancellationDeadlineHours)) {
            throw new AppointmentException(
                    AppointmentErrorCode.CANCELLATION_DEADLINE_PASSED,
                    String.format(
                            "Cuộc hẹn đã vượt quá thời hạn cho phép tự hủy/đổi lịch trực tuyến (tối thiểu trước %d giờ). Quý khách vui lòng liên hệ trực tiếp tổng đài của cơ sở để được hỗ trợ điều phối.",
                            cancellationDeadlineHours
                    )
            );
        }
    }

    @Override
    public boolean canCustomerModifyAppointment(TenantAppointment appointment, int cancellationDeadlineHours) {
        if (appointment == null || appointment.getStatus() != AppointmentStatus.CONFIRMED || appointment.getSlot() == null) {
            return false;
        }
        TenantTimeSlot slot = appointment.getSlot();
        if (slot.getWorkDate() == null || slot.getSlotStart() == null) {
            return false;
        }
        LocalDateTime appointmentStart = LocalDateTime.of(slot.getWorkDate(), slot.getSlotStart());
        long remainingMinutes = Duration.between(LocalDateTime.now(), appointmentStart).toMinutes();
        return remainingMinutes >= (cancellationDeadlineHours * 60L);
    }

    @Override
    public TenantAppointment resolveAndAuthorizeAppointment(
            String appointmentIdentifier,
            String customerPhone,
            String authenticatedUserId,
            boolean isAdminOrStaff
    ) {
        if (appointmentIdentifier == null || appointmentIdentifier.isBlank() || appointmentRepository == null) {
            throw new AppointmentException(AppointmentErrorCode.APPOINTMENT_NOT_FOUND);
        }
        String cleanId = appointmentIdentifier.trim();

        TenantAppointment appointment = appointmentRepository.findByIdWithDetails(cleanId)
                .or(() -> appointmentRepository.findByAppointmentCodeIgnoreCase(cleanId))
                .orElseThrow(() -> new AppointmentException(AppointmentErrorCode.APPOINTMENT_NOT_FOUND));

        if (isAdminOrStaff) {
            return appointment;
        }

        boolean matchesUser = authenticatedUserId != null
                && !authenticatedUserId.isBlank()
                && authenticatedUserId.equals(appointment.getUserId());
        boolean matchesPhone = customerPhone != null
                && !customerPhone.isBlank()
                && customerPhone.trim().equals(appointment.getCustomerPhone());

        if (!matchesUser && !matchesPhone) {
            throw new AppointmentException(AppointmentErrorCode.APPOINTMENT_NOT_FOUND);
        }
        return appointment;
    }
}
