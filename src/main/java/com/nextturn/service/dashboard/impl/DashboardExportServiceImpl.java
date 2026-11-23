package com.nextturn.service.dashboard.impl;

import com.nextturn.entity.TenantFeedback;
import com.nextturn.entity.TenantQueueTicket;
import com.nextturn.enums.DashboardDatePreset;
import com.nextturn.enums.DashboardExportFormat;
import com.nextturn.exception.dashboard.DashboardErrorCode;
import com.nextturn.exception.dashboard.DashboardException;
import com.nextturn.repository.feedback.TenantFeedbackRepository;
import com.nextturn.repository.ticket.TenantQueueTicketRepository;
import com.nextturn.service.BaseService;
import com.nextturn.service.dashboard.DashboardExportService;
import com.nextturn.service.dashboard.impl.DashboardSupport.DateRange;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Default implementation of {@link DashboardExportService} exporting filtered ticket,
 * wait/serve timing, no-show, and CSAT feedback rows to CSV (UTF-8 BOM) or XLSX (REQ-DASHBOARD-04).
 */
@Service
public class DashboardExportServiceImpl extends BaseService implements DashboardExportService {

    private static final String[] HEADERS = {
            "Work Date", "Ticket Code", "Customer Type", "Customer Name", "Customer Phone",
            "Service Code", "Service Name", "Status", "Counter", "Provider",
            "Issued At", "Served At", "Completed At", "Wait Time (min)", "Serve Time (min)",
            "Auto Completed", "Feedback Rating", "Feedback Comment"
    };

    private static final String CSV_CONTENT_TYPE = "text/csv; charset=UTF-8";
    private static final String XLSX_CONTENT_TYPE =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    private final TenantQueueTicketRepository ticketRepository;
    private final TenantFeedbackRepository feedbackRepository;
    private final Clock clock;

    @Autowired
    public DashboardExportServiceImpl(
            TenantQueueTicketRepository ticketRepository,
            TenantFeedbackRepository feedbackRepository
    ) {
        this(ticketRepository, feedbackRepository, Clock.systemDefaultZone());
    }

    public DashboardExportServiceImpl(
            TenantQueueTicketRepository ticketRepository,
            TenantFeedbackRepository feedbackRepository,
            Clock clock
    ) {
        this.ticketRepository = ticketRepository;
        this.feedbackRepository = feedbackRepository;
        this.clock = clock != null ? clock : Clock.systemDefaultZone();
    }

    @Override
    @Transactional(readOnly = true)
    public ExportedReportFile exportOperationalReport(
            DashboardExportFormat format,
            DashboardDatePreset preset,
            LocalDate fromDate,
            LocalDate toDate,
            String serviceId,
            String staffId
    ) {
        if (format == null) {
            throw new DashboardException(DashboardErrorCode.UNSUPPORTED_EXPORT_FORMAT);
        }
        String tenantCode = resolveCurrentTenantCode();
        DateRange range = DashboardSupport.resolveDateRange(preset, fromDate, toDate, LocalDate.now(clock));
        String cleanService = DashboardSupport.normalizeId(serviceId);
        String cleanStaff = DashboardSupport.normalizeId(staffId);

        List<TenantQueueTicket> tickets = ticketRepository
                .findByWorkDateBetweenWithDetails(range.fromDate(), range.toDate()).stream()
                .filter(t -> DashboardSupport.matchesTicketFilters(t, cleanService, cleanStaff))
                .toList();

        Map<String, TenantFeedback> feedbackByTicketId = indexFeedbacksByTicket(range, cleanService, cleanStaff);
        String baseName = "nextturn-report-" + tenantCode + "-" + range.fromDate() + "_to_" + range.toDate();

        return switch (format) {
            case CSV -> new ExportedReportFile(baseName + ".csv", CSV_CONTENT_TYPE, renderCsv(tickets, feedbackByTicketId));
            case XLSX -> new ExportedReportFile(baseName + ".xlsx", XLSX_CONTENT_TYPE, renderXlsx(tickets, feedbackByTicketId));
        };
    }

    private Map<String, TenantFeedback> indexFeedbacksByTicket(DateRange range, String serviceId, String staffId) {
        List<TenantFeedback> list = feedbackRepository.findAllForSummary(
                null,
                null,
                serviceId,
                staffId,
                range.fromDate().atStartOfDay(),
                range.toDate().atTime(LocalTime.MAX)
        );
        Map<String, TenantFeedback> byTicket = new HashMap<>();
        for (TenantFeedback fb : list) {
            if (fb.getTicket() != null && fb.getTicket().getId() != null) {
                byTicket.putIfAbsent(fb.getTicket().getId(), fb);
            }
        }
        return byTicket;
    }

    private String[] buildRowValues(TenantQueueTicket ticket, TenantFeedback feedback) {
        long waitMins = DashboardSupport.resolveWaitMinutes(ticket);
        long serveMins = Math.round(DashboardSupport.resolveServeSeconds(ticket) / 60.0d);
        return new String[]{
                ticket.getWorkDate() != null ? ticket.getWorkDate().toString() : "",
                safe(ticket.getTicketCode()),
                ticket.getCustomerType() != null ? ticket.getCustomerType().name() : "",
                safe(ticket.getCustomerName()),
                safe(ticket.getCustomerPhone()),
                ticket.getService() != null ? safe(ticket.getService().getServiceCode()) : "",
                ticket.getService() != null ? safe(ticket.getService().getServiceName()) : "",
                ticket.getStatus() != null ? ticket.getStatus().name() : "",
                safe(ticket.getCounterLabel()),
                ticket.getProvider() != null ? safe(ticket.getProvider().getFullName()) : "",
                ticket.getIssuedAt() != null ? ticket.getIssuedAt().toString() : "",
                ticket.getServedAt() != null ? ticket.getServedAt().toString() : "",
                ticket.getCompletedAt() != null ? ticket.getCompletedAt().toString() : "",
                String.valueOf(waitMins),
                String.valueOf(serveMins),
                String.valueOf(Boolean.TRUE.equals(ticket.getAutoCompleted())),
                feedback != null && feedback.getRatingScore() != null ? String.valueOf(feedback.getRatingScore()) : "",
                feedback != null ? safe(feedback.getComment()) : ""
        };
    }

    private byte[] renderCsv(List<TenantQueueTicket> tickets, Map<String, TenantFeedback> feedbackByTicketId) {
        StringBuilder sb = new StringBuilder();
        sb.append('\uFEFF'); // UTF-8 BOM so Microsoft Excel detects Vietnamese UTF-8 automatically
        appendCsvLine(sb, HEADERS);
        for (TenantQueueTicket ticket : tickets) {
            TenantFeedback fb = feedbackByTicketId.get(ticket.getId());
            appendCsvLine(sb, buildRowValues(ticket, fb));
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    private void appendCsvLine(StringBuilder sb, String[] columns) {
        for (int i = 0; i < columns.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(escapeCsvCell(columns[i]));
        }
        sb.append("\r\n");
    }

    private String escapeCsvCell(String raw) {
        String val = sanitizeFormulaPrefix(safe(raw));
        boolean needsQuotes = val.contains(",") || val.contains("\"") || val.contains("\n") || val.contains("\r");
        if (needsQuotes) {
            return "\"" + val.replace("\"", "\"\"") + "\"";
        }
        return val;
    }

    private String sanitizeFormulaPrefix(String val) {
        if (!val.isEmpty()) {
            char first = val.charAt(0);
            if (first == '=' || first == '+' || first == '-' || first == '@') {
                return "'" + val;
            }
        }
        return val;
    }

    private byte[] renderXlsx(List<TenantQueueTicket> tickets, Map<String, TenantFeedback> feedbackByTicketId) {
        try (XSSFWorkbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("Operational Report");

            CellStyle headerStyle = workbook.createCellStyle();
            headerStyle.setFillForegroundColor(IndexedColors.DARK_BLUE.getIndex());
            headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            Font headerFont = workbook.createFont();
            headerFont.setBold(true);
            headerFont.setColor(IndexedColors.WHITE.getIndex());
            headerStyle.setFont(headerFont);

            Row headerRow = sheet.createRow(0);
            for (int c = 0; c < HEADERS.length; c++) {
                var cell = headerRow.createCell(c);
                cell.setCellValue(HEADERS[c]);
                cell.setCellStyle(headerStyle);
            }

            int rowIdx = 1;
            for (TenantQueueTicket ticket : tickets) {
                TenantFeedback fb = feedbackByTicketId.get(ticket.getId());
                String[] values = buildRowValues(ticket, fb);
                Row row = sheet.createRow(rowIdx++);
                for (int c = 0; c < values.length; c++) {
                    row.createCell(c).setCellValue(sanitizeFormulaPrefix(values[c]));
                }
            }

            for (int c = 0; c < HEADERS.length; c++) {
                sheet.setColumnWidth(c, 18 * 256);
            }

            workbook.write(baos);
            return baos.toByteArray();
        } catch (Exception ex) {
            throw new DashboardException(DashboardErrorCode.EXPORT_GENERATION_FAILED);
        }
    }

    private String safe(String val) {
        return val != null ? val : "";
    }
}
