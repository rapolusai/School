package com.akshara.transport;

import java.time.LocalDate;
import java.util.UUID;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.format.annotation.DateTimeFormat.ISO;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.akshara.transport.TransportViews.BoardingReport;
import com.akshara.transport.TransportViews.DocumentsReport;
import com.akshara.transport.TransportViews.FeesReport;
import com.akshara.transport.TransportViews.RouteStudentsReport;

/**
 * The transport reports for the reports hub, as JSON and as CSV files (UTF-8 with a byte order mark, which Excel
 * opens). They need transport.read.
 */
@RestController
@RequestMapping("/api/transport/reports")
@PreAuthorize(TransportAuth.READ)
public class TransportReportController {

    private final TransportReportService reports;

    TransportReportController(TransportReportService reports) {
        this.reports = reports;
    }

    @GetMapping("/route-students")
    public RouteStudentsReport routeStudents(@RequestParam(required = false) UUID routeId,
            @RequestParam(required = false) @DateTimeFormat(iso = ISO.DATE) LocalDate date) {
        return reports.routeStudents(routeId, date);
    }

    @GetMapping("/route-students.csv")
    public ResponseEntity<byte[]> routeStudentsCsv(@RequestParam(required = false) UUID routeId,
            @RequestParam(required = false) @DateTimeFormat(iso = ISO.DATE) LocalDate date) {
        LocalDate day = date == null ? TransportRules.today() : date;
        return TransportCsv.download(reports.routeStudentsCsv(routeId, day), "transport-route-students-" + day
                + ".csv");
    }

    @GetMapping("/documents")
    public DocumentsReport documents(@RequestParam(required = false) Integer withinDays) {
        return reports.documents(withinDays);
    }

    @GetMapping("/documents.csv")
    public ResponseEntity<byte[]> documentsCsv(@RequestParam(required = false) Integer withinDays) {
        return TransportCsv.download(reports.documentsCsv(withinDays), "transport-documents-"
                + TransportRules.today() + ".csv");
    }

    @GetMapping("/fees")
    public FeesReport fees(@RequestParam(required = false) UUID yearId,
            @RequestParam(required = false) UUID routeId) {
        return reports.fees(yearId, routeId);
    }

    @GetMapping("/fees.csv")
    public ResponseEntity<byte[]> feesCsv(@RequestParam(required = false) UUID yearId,
            @RequestParam(required = false) UUID routeId) {
        return TransportCsv.download(reports.feesCsv(yearId, routeId), "transport-fees.csv");
    }

    @GetMapping("/boarding")
    public BoardingReport boarding(@RequestParam(required = false) @DateTimeFormat(iso = ISO.DATE) LocalDate date,
            @RequestParam(required = false) UUID routeId) {
        return reports.boarding(date, routeId);
    }

    @GetMapping("/boarding.csv")
    public ResponseEntity<byte[]> boardingCsv(
            @RequestParam(required = false) @DateTimeFormat(iso = ISO.DATE) LocalDate date,
            @RequestParam(required = false) UUID routeId) {
        LocalDate day = date == null ? TransportRules.today() : date;
        return TransportCsv.download(reports.boardingCsv(day, routeId), "transport-boarding-" + day + ".csv");
    }
}
