package com.akshara.health;

import java.time.LocalDate;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.format.annotation.DateTimeFormat.ISO;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.akshara.health.HealthViews.AllergiesReport;
import com.akshara.health.HealthViews.DrillsReport;
import com.akshara.health.HealthViews.InfirmaryReport;
import com.akshara.health.HealthViews.VerificationsReport;

/**
 * The health and safety reports (docs/api/phase-3-health.md): JSON for the screen and CSV (UTF-8 with a BOM) for
 * Excel. Infirmary visits and allergies need health.read; drills and verifications need safety.manage.
 */
@RestController
@RequestMapping("/api/health/reports")
public class HealthReportController {

    private final HealthReportService reports;

    HealthReportController(HealthReportService reports) {
        this.reports = reports;
    }

    @GetMapping("/infirmary")
    @PreAuthorize(HealthAuth.READ)
    public InfirmaryReport infirmary(@RequestParam(required = false) @DateTimeFormat(iso = ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = ISO.DATE) LocalDate to) {
        return reports.infirmary(from, to);
    }

    @GetMapping("/infirmary.csv")
    @PreAuthorize(HealthAuth.READ)
    public ResponseEntity<byte[]> infirmaryCsv(
            @RequestParam(required = false) @DateTimeFormat(iso = ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = ISO.DATE) LocalDate to) {
        return HealthCsv.download(reports.infirmaryCsv(from, to), "infirmary-visits.csv");
    }

    @GetMapping("/allergies")
    @PreAuthorize(HealthAuth.READ)
    public AllergiesReport allergies() {
        return reports.allergies();
    }

    @GetMapping("/allergies.csv")
    @PreAuthorize(HealthAuth.READ)
    public ResponseEntity<byte[]> allergiesCsv() {
        return HealthCsv.download(reports.allergiesCsv(), "allergies-by-class.csv");
    }

    @GetMapping("/drills")
    @PreAuthorize(HealthAuth.SAFETY)
    public DrillsReport drills() {
        return reports.drills();
    }

    @GetMapping("/drills.csv")
    @PreAuthorize(HealthAuth.SAFETY)
    public ResponseEntity<byte[]> drillsCsv() {
        return HealthCsv.download(reports.drillsCsv(), "drills.csv");
    }

    @GetMapping("/verifications")
    @PreAuthorize(HealthAuth.SAFETY)
    public VerificationsReport verifications() {
        return reports.verificationsDue();
    }

    @GetMapping("/verifications.csv")
    @PreAuthorize(HealthAuth.SAFETY)
    public ResponseEntity<byte[]> verificationsCsv() {
        return HealthCsv.download(reports.verificationsCsv(), "verifications-due.csv");
    }
}
