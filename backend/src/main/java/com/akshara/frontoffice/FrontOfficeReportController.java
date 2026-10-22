package com.akshara.frontoffice;

import java.time.LocalDate;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.format.annotation.DateTimeFormat.ISO;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.akshara.frontoffice.FrontOfficeViews.EnquiriesReport;
import com.akshara.frontoffice.FrontOfficeViews.FollowUpsReport;
import com.akshara.frontoffice.FrontOfficeViews.PassesReport;
import com.akshara.frontoffice.FrontOfficeViews.VisitorsReport;

/**
 * The front office's reports for the reports hub, as JSON and as CSV files (UTF-8 with a byte order mark, which
 * Excel opens). They need frontoffice.read.
 */
@RestController
@RequestMapping("/api/frontoffice/reports")
@PreAuthorize(FrontOfficeAuth.READ)
public class FrontOfficeReportController {

    private final FrontOfficeReportService reports;

    FrontOfficeReportController(FrontOfficeReportService reports) {
        this.reports = reports;
    }

    @GetMapping("/visitors")
    public VisitorsReport visitors(@RequestParam(required = false) @DateTimeFormat(iso = ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = ISO.DATE) LocalDate to) {
        return reports.visitors(from, to);
    }

    @GetMapping("/visitors.csv")
    public ResponseEntity<byte[]> visitorsCsv(
            @RequestParam(required = false) @DateTimeFormat(iso = ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = ISO.DATE) LocalDate to) {
        return FrontOfficeCsv.download(reports.visitorsCsv(from, to), "visitors.csv");
    }

    @GetMapping("/gate-passes")
    public PassesReport gatePasses(@RequestParam(required = false) @DateTimeFormat(iso = ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = ISO.DATE) LocalDate to) {
        return reports.gatePasses(from, to);
    }

    @GetMapping("/gate-passes.csv")
    public ResponseEntity<byte[]> gatePassesCsv(
            @RequestParam(required = false) @DateTimeFormat(iso = ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = ISO.DATE) LocalDate to) {
        return FrontOfficeCsv.download(reports.gatePassesCsv(from, to), "gate-passes.csv");
    }

    @GetMapping("/enquiries")
    public EnquiriesReport enquiries(@RequestParam(required = false) @DateTimeFormat(iso = ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = ISO.DATE) LocalDate to) {
        return reports.enquiries(from, to);
    }

    @GetMapping("/enquiries.csv")
    public ResponseEntity<byte[]> enquiriesCsv(
            @RequestParam(required = false) @DateTimeFormat(iso = ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = ISO.DATE) LocalDate to) {
        return FrontOfficeCsv.download(reports.enquiriesCsv(from, to), "enquiries.csv");
    }

    @GetMapping("/follow-ups")
    public FollowUpsReport followUps() {
        return reports.followUps();
    }

    @GetMapping("/follow-ups.csv")
    public ResponseEntity<byte[]> followUpsCsv() {
        return FrontOfficeCsv.download(reports.followUpsCsv(), "follow-ups.csv");
    }
}
