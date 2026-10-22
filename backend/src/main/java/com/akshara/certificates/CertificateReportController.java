package com.akshara.certificates;

import java.time.LocalDate;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.akshara.certificates.CertificateViews.CardsPendingReport;
import com.akshara.certificates.CertificateViews.IssuedReport;
import com.akshara.certificates.CertificateViews.PendingReport;

/** The module's reports, as JSON and as CSV downloads. certificates.read. */
@RestController
@RequestMapping("/api/certificates/reports")
@PreAuthorize(CertificateAuth.READ)
public class CertificateReportController {

    private final CertificateReportService reports;

    CertificateReportController(CertificateReportService reports) {
        this.reports = reports;
    }

    @GetMapping("/issued")
    public IssuedReport issued(@RequestParam(required = false) LocalDate from,
            @RequestParam(required = false) LocalDate to,
            @RequestParam(required = false) CertificateType type) {
        return reports.issued(from, to, type);
    }

    @GetMapping("/issued.csv")
    public ResponseEntity<byte[]> issuedCsv(@RequestParam(required = false) LocalDate from,
            @RequestParam(required = false) LocalDate to,
            @RequestParam(required = false) CertificateType type) {
        return CertificateCsv.download(reports.issuedCsv(from, to, type), "certificates-issued.csv");
    }

    @GetMapping("/pending")
    public PendingReport pending() {
        return reports.pending();
    }

    @GetMapping("/pending.csv")
    public ResponseEntity<byte[]> pendingCsv() {
        return CertificateCsv.download(reports.pendingCsv(), "certificates-pending.csv");
    }

    @GetMapping("/id-cards-pending")
    public CardsPendingReport cardsPending(@RequestParam(required = false) UUID sectionId,
            @RequestParam(defaultValue = "false") boolean staff) {
        return reports.cardsPending(sectionId, staff);
    }

    @GetMapping("/id-cards-pending.csv")
    public ResponseEntity<byte[]> cardsPendingCsv(@RequestParam(required = false) UUID sectionId,
            @RequestParam(defaultValue = "false") boolean staff) {
        return CertificateCsv.download(reports.cardsPendingCsv(sectionId, staff), "id-cards-pending.csv");
    }
}
