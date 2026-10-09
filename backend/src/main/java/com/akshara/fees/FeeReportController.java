package com.akshara.fees;

import java.time.LocalDate;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.akshara.fees.FeeForms.ReminderForm;
import com.akshara.fees.FeeViews.CollectionReport;
import com.akshara.fees.FeeViews.OutstandingReport;
import com.akshara.fees.FeeViews.OverdueReport;
import com.akshara.fees.FeeViews.Overview;
import com.akshara.fees.FeeViews.ReminderResult;

/** Fee reports and exports (fees.read) and overdue reminders (fees.collect). */
@RestController
@RequestMapping("/api/fees")
public class FeeReportController {

    private final FeeReportService reports;

    public FeeReportController(FeeReportService reports) {
        this.reports = reports;
    }

    @GetMapping("/reports/overview")
    @PreAuthorize(FeeSetupController.READ)
    public Overview overview() {
        return reports.overview();
    }

    @GetMapping("/reports/collection")
    @PreAuthorize(FeeSetupController.READ)
    public CollectionReport collection(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return reports.collection(from, to);
    }

    @GetMapping("/reports/outstanding")
    @PreAuthorize(FeeSetupController.READ)
    public OutstandingReport outstanding(@RequestParam(required = false) UUID yearId,
            @RequestParam(required = false) UUID classId, @RequestParam(required = false) UUID sectionId) {
        return reports.outstanding(yearId, classId, sectionId);
    }

    @GetMapping("/reports/overdue")
    @PreAuthorize(FeeSetupController.READ)
    public OverdueReport overdue(@RequestParam(required = false) UUID yearId,
            @RequestParam(required = false) UUID classId, @RequestParam(required = false) UUID sectionId,
            @RequestParam(required = false) @Min(0) @Max(3650) Integer minDays) {
        return reports.overdue(yearId, classId, sectionId, minDays);
    }

    /** Collections as CSV for Tally: date, receipt no, ledger (fee head), amount, mode, narration. */
    @GetMapping("/reports/tally.csv")
    @PreAuthorize(FeeSetupController.READ)
    public ResponseEntity<String> tally(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return FeeCollectionController.csv(reports.tallyCsv(from, to), "tally-" + from + "-to-" + to + ".csv");
    }

    /** Asks for an overdue-fee reminder to each chosen student's parents. */
    @PostMapping("/reminders")
    @PreAuthorize(FeeSetupController.COLLECT)
    public ReminderResult remind(@Valid @RequestBody ReminderForm request) {
        return reports.remind(request, null);
    }
}
