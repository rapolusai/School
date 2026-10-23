package com.akshara.assist;

import java.util.UUID;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.akshara.assist.AssistCsv.Download;
import com.akshara.assist.AssistViews.AcceptedReport;
import com.akshara.assist.AssistViews.AtRiskReport;

/** The assistance reports of the reports hub, as JSON and as CSV (UTF-8 with a byte order mark). */
@RestController
@RequestMapping("/api/assist/reports")
@PreAuthorize(AssistAccess.USE)
public class AssistReportController {

    private final AssistReportService reports;

    AssistReportController(AssistReportService reports) {
        this.reports = reports;
    }

    @GetMapping("/at-risk")
    public AtRiskReport atRisk(@RequestParam(required = false) UUID classId) {
        return reports.atRisk(classId);
    }

    @GetMapping("/at-risk.csv")
    public ResponseEntity<byte[]> atRiskCsv(@RequestParam(required = false) UUID classId) {
        return file(reports.atRiskCsv(classId));
    }

    @GetMapping("/drafts-accepted")
    public AcceptedReport accepted(@RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        return reports.accepted(from, to);
    }

    @GetMapping("/drafts-accepted.csv")
    public ResponseEntity<byte[]> acceptedCsv(@RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        return file(reports.acceptedCsv(from, to));
    }

    private static ResponseEntity<byte[]> file(Download download) {
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(download.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + download.fileName() + "\"")
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(download.content());
    }
}
