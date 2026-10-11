package com.akshara.payroll;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.akshara.payroll.PayrollForms.AdjustmentForm;
import com.akshara.payroll.PayrollForms.PaidForm;
import com.akshara.payroll.PayrollForms.RunForm;
import com.akshara.payroll.PayrollForms.TdsOverrideForm;
import com.akshara.payroll.PayrollViews.BankAdvice;
import com.akshara.payroll.PayrollViews.Download;
import com.akshara.payroll.PayrollViews.PayslipView;
import com.akshara.payroll.PayrollViews.PtSummary;
import com.akshara.payroll.PayrollViews.RunDetail;
import com.akshara.payroll.PayrollViews.RunList;
import com.akshara.payroll.PayrollViews.TdsSummary;

/**
 * Monthly payroll runs, their payslips and files. payroll.read reviews them; payroll.manage creates and recalculates
 * drafts, adds adjustments and TDS overrides, marks a month paid and downloads the bank, PF, ESI, professional tax and
 * TDS files (audited); payroll.approve approves and locks a month. Ids of another school are 404.
 */
@RestController
@RequestMapping("/api/payroll")
public class PayrollRunController {

    private final PayrollRunService runs;
    private final PayslipService payslips;
    private final PayrollExportService exports;

    PayrollRunController(PayrollRunService runs, PayslipService payslips, PayrollExportService exports) {
        this.runs = runs;
        this.payslips = payslips;
        this.exports = exports;
    }

    @GetMapping("/runs")
    @PreAuthorize(PayrollAccess.READ)
    public RunList runs() {
        return runs.list();
    }

    @PostMapping("/runs")
    @PreAuthorize(PayrollAccess.MANAGE)
    @ResponseStatus(HttpStatus.CREATED)
    public RunDetail create(@Valid @RequestBody RunForm request) {
        return runs.create(request.month(), PayrollAccess.actor());
    }

    @GetMapping("/runs/{id}")
    @PreAuthorize(PayrollAccess.READ)
    public RunDetail run(@PathVariable UUID id) {
        return runs.detail(id);
    }

    @PostMapping("/runs/{id}/recalculate")
    @PreAuthorize(PayrollAccess.MANAGE)
    public RunDetail recalculate(@PathVariable UUID id) {
        return runs.recalculate(id, PayrollAccess.actor());
    }

    @DeleteMapping("/runs/{id}")
    @PreAuthorize(PayrollAccess.MANAGE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        runs.delete(id, PayrollAccess.actor());
    }

    @PostMapping("/runs/{id}/adjustments")
    @PreAuthorize(PayrollAccess.MANAGE)
    @ResponseStatus(HttpStatus.CREATED)
    public RunDetail addAdjustment(@PathVariable UUID id, @Valid @RequestBody AdjustmentForm request) {
        return runs.addAdjustment(id, request, PayrollAccess.actor());
    }

    @DeleteMapping("/runs/{id}/adjustments/{adjustmentId}")
    @PreAuthorize(PayrollAccess.MANAGE)
    public RunDetail removeAdjustment(@PathVariable UUID id, @PathVariable UUID adjustmentId) {
        return runs.removeAdjustment(id, adjustmentId, PayrollAccess.actor());
    }

    @PutMapping("/runs/{id}/payslips/{payslipId}/tds")
    @PreAuthorize(PayrollAccess.MANAGE)
    public RunDetail overrideTds(@PathVariable UUID id, @PathVariable UUID payslipId,
            @Valid @RequestBody TdsOverrideForm request) {
        return runs.overrideTds(id, payslipId, request, PayrollAccess.actor());
    }

    @DeleteMapping("/runs/{id}/payslips/{payslipId}/tds")
    @PreAuthorize(PayrollAccess.MANAGE)
    public RunDetail clearTdsOverride(@PathVariable UUID id, @PathVariable UUID payslipId) {
        return runs.clearTdsOverride(id, payslipId, PayrollAccess.actor());
    }

    @PostMapping("/runs/{id}/approve")
    @PreAuthorize(PayrollAccess.APPROVE)
    public RunDetail approve(@PathVariable UUID id) {
        return runs.approve(id, PayrollAccess.actor());
    }

    @PostMapping("/runs/{id}/paid")
    @PreAuthorize(PayrollAccess.MANAGE)
    public RunDetail paid(@PathVariable UUID id, @Valid @RequestBody PaidForm request) {
        return runs.markPaid(id, request, PayrollAccess.actor());
    }

    // ------------------------------------------------------------------ payslips

    @GetMapping("/runs/{id}/payslips")
    @PreAuthorize(PayrollAccess.READ)
    public List<PayslipView> payslips(@PathVariable UUID id) {
        return payslips.ofRun(id);
    }

    @GetMapping("/payslips/{id}")
    @PreAuthorize(PayrollAccess.READ)
    public PayslipView payslip(@PathVariable UUID id) {
        return payslips.payslip(id);
    }

    // ------------------------------------------------------------------ files and summaries

    @GetMapping("/runs/{id}/bank-file.csv")
    @PreAuthorize(PayrollAccess.MANAGE)
    public ResponseEntity<byte[]> bankFile(@PathVariable UUID id) {
        return file(exports.bankFile(id, PayrollAccess.actor()));
    }

    @GetMapping("/runs/{id}/bank-advice")
    @PreAuthorize(PayrollAccess.MANAGE)
    public BankAdvice bankAdvice(@PathVariable UUID id) {
        return exports.bankAdvice(id, PayrollAccess.actor());
    }

    @GetMapping("/runs/{id}/ecr.txt")
    @PreAuthorize(PayrollAccess.MANAGE)
    public ResponseEntity<byte[]> ecr(@PathVariable UUID id) {
        return file(exports.ecr(id, PayrollAccess.actor()));
    }

    @GetMapping("/runs/{id}/esi.csv")
    @PreAuthorize(PayrollAccess.MANAGE)
    public ResponseEntity<byte[]> esi(@PathVariable UUID id) {
        return file(exports.esi(id, PayrollAccess.actor()));
    }

    @GetMapping("/runs/{id}/professional-tax")
    @PreAuthorize(PayrollAccess.READ)
    public PtSummary professionalTax(@PathVariable UUID id) {
        return exports.pt(id);
    }

    @GetMapping("/runs/{id}/professional-tax.csv")
    @PreAuthorize(PayrollAccess.MANAGE)
    public ResponseEntity<byte[]> professionalTaxCsv(@PathVariable UUID id) {
        return file(exports.ptCsv(id, PayrollAccess.actor()));
    }

    @GetMapping("/runs/{id}/tds")
    @PreAuthorize(PayrollAccess.READ)
    public TdsSummary tds(@PathVariable UUID id) {
        return exports.tds(id);
    }

    @GetMapping("/runs/{id}/tds.csv")
    @PreAuthorize(PayrollAccess.MANAGE)
    public ResponseEntity<byte[]> tdsCsv(@PathVariable UUID id) {
        return file(exports.tdsCsv(id, PayrollAccess.actor()));
    }

    static ResponseEntity<byte[]> file(Download download) {
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(download.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(download.fileName()).build().toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(download.content());
    }
}
