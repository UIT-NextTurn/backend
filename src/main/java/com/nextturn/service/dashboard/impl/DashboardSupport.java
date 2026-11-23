package com.nextturn.service.dashboard.impl;

import com.nextturn.entity.TenantQueueTicket;
import com.nextturn.enums.DashboardDatePreset;
import com.nextturn.exception.dashboard.DashboardErrorCode;
import com.nextturn.exception.dashboard.DashboardException;

import java.time.Duration;
import java.time.LocalDate;

/**
 * Shared pure helpers for dashboard date window resolution, multi-filter matching,
 * duration computation, and rate rounding across Tenant and Export services.
 */
final class DashboardSupport {

    private DashboardSupport() {
        // Utility class
    }

    record DateRange(LocalDate fromDate, LocalDate toDate) {
    }

    static DateRange resolveDateRange(
            DashboardDatePreset preset,
            LocalDate fromDate,
            LocalDate toDate,
            LocalDate today
    ) {
        DashboardDatePreset effective = preset;
        if (effective == null) {
            effective = (fromDate != null || toDate != null)
                    ? DashboardDatePreset.CUSTOM
                    : DashboardDatePreset.TODAY;
        }

        LocalDate resolvedFrom;
        LocalDate resolvedTo;
        switch (effective) {
            case TODAY -> {
                resolvedFrom = today;
                resolvedTo = today;
            }
            case LAST_7_DAYS -> {
                resolvedFrom = today.minusDays(6);
                resolvedTo = today;
            }
            case THIS_MONTH -> {
                resolvedFrom = today.withDayOfMonth(1);
                resolvedTo = today;
            }
            case LAST_30_DAYS -> {
                resolvedFrom = today.minusDays(29);
                resolvedTo = today;
            }
            case CUSTOM -> {
                if (fromDate == null && toDate == null) {
                    resolvedFrom = today;
                    resolvedTo = today;
                } else {
                    resolvedFrom = fromDate != null ? fromDate : toDate;
                    resolvedTo = toDate != null ? toDate : fromDate;
                }
            }
            default -> {
                resolvedFrom = today;
                resolvedTo = today;
            }
        }

        if (resolvedFrom.isAfter(resolvedTo)) {
            throw new DashboardException(DashboardErrorCode.INVALID_DATE_RANGE);
        }
        return new DateRange(resolvedFrom, resolvedTo);
    }

    static String normalizeId(String raw) {
        return (raw != null && !raw.isBlank()) ? raw.trim() : null;
    }

    static boolean matchesTicketFilters(TenantQueueTicket ticket, String cleanServiceId, String cleanStaffId) {
        if (cleanServiceId != null) {
            if (ticket.getService() == null || !cleanServiceId.equals(ticket.getService().getId())) {
                return false;
            }
        }
        if (cleanStaffId != null) {
            boolean matchesProvider = ticket.getProvider() != null
                    && cleanStaffId.equals(ticket.getProvider().getId());
            boolean matchesCounter = ticket.getCounterStaff() != null
                    && cleanStaffId.equals(ticket.getCounterStaff().getId());
            return matchesProvider || matchesCounter;
        }
        return true;
    }

    static long resolveServeSeconds(TenantQueueTicket ticket) {
        if (ticket == null) {
            return 0L;
        }
        if (ticket.getActualDuration() != null && ticket.getActualDuration() >= 0) {
            return ticket.getActualDuration();
        }
        if (ticket.getServedAt() != null && ticket.getCompletedAt() != null
                && !ticket.getCompletedAt().isBefore(ticket.getServedAt())) {
            return Duration.between(ticket.getServedAt(), ticket.getCompletedAt()).getSeconds();
        }
        return 0L;
    }

    static long resolveWaitMinutes(TenantQueueTicket ticket) {
        if (ticket == null || ticket.getIssuedAt() == null || ticket.getServedAt() == null
                || ticket.getServedAt().isBefore(ticket.getIssuedAt())) {
            return 0L;
        }
        return Duration.between(ticket.getIssuedAt(), ticket.getServedAt()).toMinutes();
    }

    static double computePercentage(long part, long total) {
        if (total <= 0L) {
            return 0.0d;
        }
        return roundOneDecimal((part * 100.0d) / total);
    }

    static double roundOneDecimal(double value) {
        return Math.round(value * 10.0d) / 10.0d;
    }
}
