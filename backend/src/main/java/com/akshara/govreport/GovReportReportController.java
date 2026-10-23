package com.akshara.govreport;

import java.time.LocalDate;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.akshara.govreport.GovReportViews.CompletenessReport;
import com.akshara.govreport.GovReportViews.EnrolmentReport;
import com.akshara.govreport.GovReportViews.RteReport;
import com.akshara.govreport.GovReportViews.SchemeReport;

/** The "Government" group of the reports hub, as JSON and as CSV downloads. govreport.read. */
@RestController
@RequestMapping("/api/govreport/reports")
@PreAuthorize(GovReportAuth.READ)
public class GovReportReportController {

    private final GovReportReportService reports;

    GovReportReportController(GovReportReportService reports) {
        this.reports = reports;
    }

    @GetMapping("/completeness")
    public CompletenessReport completeness(@RequestParam(required = false) UUID yearId) {
        return reports.completeness(yearId);
    }

    @GetMapping("/completeness.csv")
    public ResponseEntity<byte[]> completenessCsv(@RequestParam(required = false) UUID yearId) {
        return GovCsv.download(reports.completenessCsv(yearId), "government-fields-completeness.csv");
    }

    @GetMapping("/enrolment")
    public EnrolmentReport enrolment(@RequestParam(required = false) UUID yearId,
            @RequestParam(required = false) LocalDate asOf) {
        return reports.enrolment(yearId, asOf);
    }

    @GetMapping("/enrolment.csv")
    public ResponseEntity<byte[]> enrolmentCsv(@RequestParam(required = false) UUID yearId,
            @RequestParam(required = false) LocalDate asOf) {
        return GovCsv.download(reports.enrolmentCsv(yearId, asOf), "enrolment-by-category.csv");
    }

    @GetMapping("/rte")
    public RteReport rte(@RequestParam(required = false) UUID yearId) {
        return reports.rteSeats(yearId);
    }

    @GetMapping("/rte.csv")
    public ResponseEntity<byte[]> rteCsv(@RequestParam(required = false) UUID yearId) {
        return GovCsv.download(reports.rteSeatsCsv(yearId), "rte-seats.csv");
    }

    @GetMapping("/schemes")
    public SchemeReport schemes(@RequestParam(required = false) UUID yearId) {
        return reports.schemes(yearId);
    }

    @GetMapping("/schemes.csv")
    public ResponseEntity<byte[]> schemesCsv(@RequestParam(required = false) UUID yearId) {
        return GovCsv.download(reports.schemesCsv(yearId), "scheme-enrolments.csv");
    }
}
