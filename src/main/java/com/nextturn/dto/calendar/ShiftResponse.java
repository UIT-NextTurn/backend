package com.nextturn.dto.calendar;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.nextturn.entity.TenantShift;
import com.nextturn.enums.ShiftStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * Standard DTO response for scheduled staff work shifts on the tenant calendar.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Work shift details response")
public class ShiftResponse {

    @Schema(description = "Shift unique UUID", example = "550e8400-e29b-41d4-a716-446655440000")
    private String id;

    @Schema(description = "Assigned staff UUID", example = "d3b07384-d113-4e89-8d7d-5e2634e9e030")
    private String staffId;

    @Schema(description = "Staff identification code", example = "STAFF-0001")
    private String staffCode;

    @Schema(description = "Staff full name", example = "Nguyễn Văn A")
    private String staffFullName;

    @Schema(description = "Assigned service UUID (if bound)", example = "8c7a65b4-d5e6-4f7a-8b9c-0d1e2f3a4b5c")
    private String serviceId;

    @Schema(description = "Assigned service name", example = "Dịch vụ Đăng ký tài khoản")
    private String serviceName;

    @JsonFormat(pattern = "yyyy-MM-dd")
    @Schema(description = "Scheduled work date (YYYY-MM-DD)", example = "2026-11-05")
    private LocalDate workDate;

    @JsonFormat(pattern = "HH:mm:ss")
    @Schema(description = "Shift start time", example = "08:00:00")
    private LocalTime shiftStart;

    @JsonFormat(pattern = "HH:mm:ss")
    @Schema(description = "Shift end time", example = "17:00:00")
    private LocalTime shiftEnd;

    @Schema(description = "Operational shift status", example = "SCHEDULED")
    private ShiftStatus status;

    @Schema(description = "Shift record creation timestamp")
    private LocalDateTime createdAt;

    public static ShiftResponse fromEntity(TenantShift shift) {
        if (shift == null) {
            return null;
        }
        return ShiftResponse.builder()
                .id(shift.getId())
                .staffId(shift.getStaff() != null ? shift.getStaff().getId() : null)
                .staffCode(shift.getStaff() != null ? shift.getStaff().getStaffCode() : null)
                .staffFullName(shift.getStaff() != null ? shift.getStaff().getFullName() : null)
                .serviceId(shift.getService() != null ? shift.getService().getId() : null)
                .serviceName(shift.getService() != null ? shift.getService().getServiceName() : null)
                .workDate(shift.getWorkDate())
                .shiftStart(shift.getShiftStart())
                .shiftEnd(shift.getShiftEnd())
                .status(shift.getStatus())
                .createdAt(shift.getCreatedAt())
                .build();
    }
}

