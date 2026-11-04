package com.nextturn.dto.calendar;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.nextturn.entity.TenantTimeSlot;
import com.nextturn.enums.TimeSlotStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalTime;

/**
 * Standard DTO response for capacity booking time slots (*khung giờ đặt hẹn*).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Appointment booking time slot response")
public class TimeSlotResponse {

    @Schema(description = "Slot unique UUID", example = "6ba7b810-9dad-11d1-80b4-00c04fd430c8")
    private String id;

    @Schema(description = "Service UUID", example = "8c7a65b4-d5e6-4f7a-8b9c-0d1e2f3a4b5c")
    private String serviceId;

    @Schema(description = "Service name", example = "Dịch vụ Đăng ký tài khoản")
    private String serviceName;

    @JsonFormat(pattern = "yyyy-MM-dd")
    @Schema(description = "Slot work date (YYYY-MM-DD)", example = "2026-11-05")
    private LocalDate workDate;

    @JsonFormat(pattern = "HH:mm:ss")
    @Schema(description = "Slot start time", example = "08:00:00")
    private LocalTime slotStart;

    @JsonFormat(pattern = "HH:mm:ss")
    @Schema(description = "Slot end time", example = "08:30:00")
    private LocalTime slotEnd;

    @Schema(description = "Maximum capacity of concurrent customers handled in this slot", example = "2")
    private Integer capacityPerSlot;

    @Schema(description = "Count of currently confirmed or held bookings in this slot", example = "1")
    private Integer bookedCount;

    @Schema(description = "Remaining available quota for customers to book", example = "1")
    private Integer availableCount;

    @Schema(description = "Slot availability status (AVAILABLE, HOLDING, BOOKED)", example = "AVAILABLE")
    private TimeSlotStatus status;

    public static TimeSlotResponse fromEntity(TenantTimeSlot slot) {
        if (slot == null) {
            return null;
        }
        int capacity = slot.getCapacityPerSlot() != null ? slot.getCapacityPerSlot() : 1;
        int booked = slot.getBookedCount() != null ? slot.getBookedCount() : 0;
        int available = Math.max(0, capacity - booked);

        return TimeSlotResponse.builder()
                .id(slot.getId())
                .serviceId(slot.getService() != null ? slot.getService().getId() : null)
                .serviceName(slot.getService() != null ? slot.getService().getServiceName() : null)
                .workDate(slot.getWorkDate())
                .slotStart(slot.getSlotStart())
                .slotEnd(slot.getSlotEnd())
                .capacityPerSlot(capacity)
                .bookedCount(booked)
                .availableCount(available)
                .status(slot.getStatus())
                .build();
    }
}

