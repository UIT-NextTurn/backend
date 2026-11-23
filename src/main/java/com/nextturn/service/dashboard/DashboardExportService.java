package com.nextturn.service.dashboard;

import com.nextturn.enums.DashboardDatePreset;
import com.nextturn.enums.DashboardExportFormat;

import java.time.LocalDate;

/**
 * Service contract exporting operational queue and feedback analytics datasets
 * into downloadable CSV or XLSX files (REQ-DASHBOARD-04).
 */
public interface DashboardExportService {

    ExportedReportFile exportOperationalReport(
            DashboardExportFormat format,
            DashboardDatePreset preset,
            LocalDate fromDate,
            LocalDate toDate,
            String serviceId,
            String staffId
    );

    record ExportedReportFile(
            String fileName,
            String contentType,
            byte[] bytes
    ) {
    }
}
