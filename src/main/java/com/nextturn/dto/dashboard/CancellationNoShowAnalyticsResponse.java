package com.nextturn.dto.dashboard;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.util.List;

/**
 * Cancellation and no-show breakdown analytics across hourly slots and services (REQ-DASHBOARD-02, UI-04).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Cancellation and no-show analytics report")
public class CancellationNoShowAnalyticsResponse {

    private LocalDate fromDate;
    private LocalDate toDate;
    private String serviceId;
    private String staffId;

    private long totalTickets;
    private long completedTickets;
    private long noShowTickets;
    private double noShowRate;

    private long totalAppointments;
    private long cancelledAppointments;
    private double cancellationRate;

    private long appointmentTickets;
    private long walkInTickets;
    private double appointmentShareRate;
    private double walkInShareRate;

    private List<HourlyTrafficPoint> hourlyBreakdown;
    private List<ServiceOutcomeBreakdown> serviceBreakdown;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class HourlyTrafficPoint {
        private int hourSlot;
        private long totalTickets;
        private long completedTickets;
        private long noShowTickets;
        private double avgWaitMinutes;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ServiceOutcomeBreakdown {
        private String serviceId;
        private String serviceCode;
        private String serviceName;
        private long totalTickets;
        private long completedTickets;
        private long noShowTickets;
        private double noShowRate;
        private long totalAppointments;
        private long cancelledAppointments;
        private double cancellationRate;
    }
}
