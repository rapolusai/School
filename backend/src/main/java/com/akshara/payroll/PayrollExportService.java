package com.akshara.payroll;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.akshara.audit.AuditService;
import com.akshara.audit.AuditService.Actor;
import com.akshara.fees.AmountInWords;
import com.akshara.payroll.PayrollCsv.Money;
import com.akshara.payroll.PayrollSettings.Values;
import com.akshara.payroll.PayrollViews.BankAdvice;
import com.akshara.payroll.PayrollViews.BankAdviceRow;
import com.akshara.payroll.PayrollViews.Download;
import com.akshara.payroll.PayrollViews.PersonRef;
import com.akshara.payroll.PayrollViews.PtRow;
import com.akshara.payroll.PayrollViews.PtSlabTotal;
import com.akshara.payroll.PayrollViews.PtSummary;
import com.akshara.payroll.PayrollViews.TdsRow;
import com.akshara.payroll.PayrollViews.TdsSummary;
import com.akshara.shared.ApiException;
import com.akshara.shared.TenantContext;

/**
 * The files of an approved month: the bank transfer file (CSV) with its covering letter, the EPFO ECR 2.0 text file,
 * the ESI monthly contribution file, and the professional tax and TDS summaries. Nothing is filed or uploaded
 * anywhere: these are files for the school to check and upload itself. Every export is audited as payroll.exported
 * with who, the month, the file and its row count, never amounts or names.
 */
@Service
@Transactional
public class PayrollExportService {

    static final String ECR_SEPARATOR = "#~#";
    static final DateTimeFormatter MONTH_NAME = DateTimeFormatter.ofPattern("MMM yyyy", Locale.ENGLISH);
    static final DateTimeFormatter ESI_DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.ENGLISH);

    private final PayrollRunRepository runs;
    private final PayslipRepository payslips;
    private final StaffPayProfileRepository profiles;
    private final PayrollSettingsService settings;
    private final PayslipService payslipViews;
    private final AuditService audit;

    PayrollExportService(PayrollRunRepository runs, PayslipRepository payslips, StaffPayProfileRepository profiles,
            PayrollSettingsService settings, PayslipService payslipViews, AuditService audit) {
        this.runs = runs;
        this.payslips = payslips;
        this.profiles = profiles;
        this.settings = settings;
        this.payslipViews = payslipViews;
        this.audit = audit;
    }

    // ------------------------------------------------------------------ bank

    /**
     * Beneficiary name, full account number, IFSC, amount and narration for everyone with net pay and a bank account.
     * The only place full account numbers leave the system.
     */
    public Download bankFile(UUID runId, Actor actor) {
        PayrollRun run = locked(runId);
        Map<UUID, StaffPayProfile> byUser = profilesByUser();
        String narration = "Salary " + MONTH_NAME.format(run.yearMonth());
        PayrollCsv csv = new PayrollCsv("Beneficiary name", "Account number", "IFSC", "Amount", "Narration");
        for (Payslip s : payslips.findByRun(runId)) {
            StaffPayProfile p = byUser.get(s.getUserId());
            if (s.getNetPaise() <= 0 || p == null || p.getBankAccountNumber() == null) {
                continue;
            }
            csv.row(beneficiary(s, p), p.getBankAccountNumber(), p.getBankIfsc(), new Money(s.getNetPaise()),
                    narration);
        }
        exported(actor, run, "BANK", csv.rows());
        return new Download("bank-transfer-" + run.yearMonth() + ".csv", PayrollCsv.CONTENT_TYPE, csv.plain());
    }

    /** The covering letter to the bank: totals in figures and words, each account masked to its last four digits. */
    public BankAdvice bankAdvice(UUID runId, Actor actor) {
        PayrollRun run = locked(runId);
        Map<UUID, StaffPayProfile> byUser = profilesByUser();
        Values v = settings.values();
        List<BankAdviceRow> rows = new ArrayList<>();
        List<PersonRef> without = new ArrayList<>();
        long total = 0;
        for (Payslip s : payslips.findByRun(runId)) {
            if (s.getNetPaise() <= 0) {
                continue;
            }
            StaffPayProfile p = byUser.get(s.getUserId());
            if (p == null || p.getBankAccountNumber() == null) {
                without.add(new PersonRef(s.getUserId(), s.getEmployeeName(), s.getEmployeeCode()));
                continue;
            }
            rows.add(new BankAdviceRow(beneficiary(s, p), s.getEmployeeCode(),
                    PayrollViews.maskAccount(p.getBankAccountNumber()), p.getBankIfsc(), s.getNetPaise()));
            total += s.getNetPaise();
        }
        exported(actor, run, "BANK_ADVICE", rows.size());
        return new BankAdvice(run.yearMonth().toString(), run.getStatus(), run.getPaidOn(), payslipViews.school(),
                v.bankName(), v.bankBranch(), v.bankAccountNumber(), v.bankIfsc(), rows.size(), total,
                AmountInWords.rupees(total), rows, without);
    }

    // ------------------------------------------------------------------ PF and ESI

    /**
     * The EPFO ECR 2.0 text file: one line per PF member with a UAN, eleven fields separated by #~#: UAN, name, gross
     * wages, EPF wages, EPS wages, EDLI wages, EPF (employee) contribution, EPS contribution, EPF-EPS difference
     * (employer), NCP days and refund of advances, in whole rupees. Members without a UAN are left out (the run
     * review lists them).
     */
    public Download ecr(UUID runId, Actor actor) {
        PayrollRun run = locked(runId);
        StringBuilder out = new StringBuilder();
        int rows = 0;
        for (Payslip s : payslips.findByRun(runId)) {
            if (!s.isPfMember() || s.getEpfWagesPaise() <= 0 || s.getUan() == null) {
                continue;
            }
            String name = s.getEmployeeName().replace("#", " ").replace("~", " ").strip();
            out.append(String.join(ECR_SEPARATOR, s.getUan(), name, rupees(s.getGrossPaise()),
                    rupees(s.getEpfWagesPaise()), rupees(s.getEpsWagesPaise()), rupees(s.getEdliWagesPaise()),
                    rupees(s.getPfEmployeePaise()), rupees(s.getEpsPaise()), rupees(s.getEpfEmployerPaise()),
                    days(s.getLopDays()), "0")).append("\n");
            rows++;
        }
        exported(actor, run, "ECR", rows);
        return new Download("ecr-" + run.yearMonth() + ".txt", "text/plain;charset=UTF-8",
                out.toString().getBytes(StandardCharsets.UTF_8));
    }

    /**
     * The ESI monthly contribution file for everyone covered with an insurance number: IP number, name, days paid,
     * wages, the reason code (2 "left service" for someone who left in the month, with their last working day; 1 "on
     * leave" for zero days; 0 otherwise) and the last working day.
     */
    public Download esi(UUID runId, Actor actor) {
        PayrollRun run = locked(runId);
        YearMonth month = run.yearMonth();
        PayrollCsv csv = new PayrollCsv("IP Number", "IP Name", "No of Days for which wages paid/payable",
                "Total Monthly Wages", "Reason Code for Zero working days", "Last Working Day");
        for (Payslip s : payslips.findByRun(runId)) {
            if (!s.isEsiCovered() || s.getEsiNumber() == null) {
                continue;
            }
            int paidDays = Integer.parseInt(days(s.getPayableDays()));
            boolean leftThisMonth = s.getDateOfLeaving() != null && !YearMonth.from(s.getDateOfLeaving())
                    .isAfter(month);
            String reason = leftThisMonth ? "2" : paidDays > 0 ? "0" : "1";
            csv.row(s.getEsiNumber(), s.getEmployeeName(), paidDays, new Money(s.getEsiWagesPaise()), reason,
                    leftThisMonth ? ESI_DATE.format(s.getDateOfLeaving()) : null);
        }
        exported(actor, run, "ESI", csv.rows());
        return new Download("esi-" + month + ".csv", PayrollCsv.CONTENT_TYPE, csv.forSpreadsheet());
    }

    // ------------------------------------------------------------------ professional tax and TDS

    /** Professional tax of the month by slab amount and by person (any run, draft included). */
    @Transactional(readOnly = true)
    public PtSummary pt(UUID runId) {
        TenantContext.require();
        PayrollRun run = runs.findById(runId).orElseThrow(() -> ApiException.notFound("Payroll run"));
        List<Payslip> slips = payslips.findByRun(runId);
        List<PtRow> rows = slips.stream()
                .map(s -> new PtRow(s.getEmployeeName(), s.getEmployeeCode(), s.getGrossPaise(), s.getPtPaise()))
                .toList();
        Map<Long, List<Payslip>> bySlab = slips.stream()
                .collect(Collectors.groupingBy(Payslip::getPtPaise, TreeMap::new, Collectors.toList()));
        List<PtSlabTotal> slabs = bySlab.entrySet().stream()
                .map(e -> new PtSlabTotal(e.getKey(), e.getValue().size(), e.getKey() * e.getValue().size()))
                .toList();
        return new PtSummary(run.yearMonth().toString(), settings.values().ptState(), slabs, rows,
                slips.stream().mapToLong(Payslip::getPtPaise).sum());
    }

    public Download ptCsv(UUID runId, Actor actor) {
        PayrollRun run = locked(runId);
        PtSummary summary = pt(runId);
        PayrollCsv csv = new PayrollCsv("Employee code", "Name", "Gross wages", "Professional tax");
        for (PtRow r : summary.rows()) {
            csv.row(r.employeeCode(), r.name(), new Money(r.grossPaise()), new Money(r.ptPaise()));
        }
        int rows = csv.rows();
        csv.row(null, "Total", null, new Money(summary.totalPaise()));
        exported(actor, run, "PT", rows);
        return new Download("professional-tax-" + run.yearMonth() + ".csv", PayrollCsv.CONTENT_TYPE,
                csv.forSpreadsheet());
    }

    /** TDS of the month by person with the projected year it was worked out from (PAN masked). */
    @Transactional(readOnly = true)
    public TdsSummary tds(UUID runId) {
        TenantContext.require();
        PayrollRun run = runs.findById(runId).orElseThrow(() -> ApiException.notFound("Payroll run"));
        List<Payslip> slips = payslips.findByRun(runId);
        List<TdsRow> rows = slips.stream()
                .map(s -> new TdsRow(s.getEmployeeName(), s.getEmployeeCode(), s.getPanMasked(), s.getTaxablePaise(),
                        s.getTdsProjectedIncomePaise(), s.getTdsAnnualTaxPaise(), s.getTdsCalculatedPaise(),
                        s.getTdsPaise(), s.getTdsOverridePaise() != null))
                .toList();
        return new TdsSummary(run.yearMonth().toString(), PayrollMath.financialYear(run.yearMonth()), rows,
                slips.stream().mapToLong(Payslip::getTdsPaise).sum());
    }

    public Download tdsCsv(UUID runId, Actor actor) {
        PayrollRun run = locked(runId);
        TdsSummary summary = tds(runId);
        PayrollCsv csv = new PayrollCsv("Employee code", "Name", "PAN", "Taxable this month",
                "Projected taxable income for the year", "Tax for the year", "TDS calculated", "TDS deducted",
                "Overridden");
        for (TdsRow r : summary.rows()) {
            csv.row(r.employeeCode(), r.name(), r.panMasked(), new Money(r.taxablePaise()),
                    new Money(r.projectedIncomePaise()), new Money(r.annualTaxPaise()), new Money(r.calculatedPaise()),
                    new Money(r.tdsPaise()), r.overridden() ? "Yes" : "No");
        }
        int rows = csv.rows();
        csv.row(null, "Total", null, null, null, null, null, new Money(summary.totalPaise()), null);
        exported(actor, run, "TDS", rows);
        return new Download("tds-" + run.yearMonth() + ".csv", PayrollCsv.CONTENT_TYPE, csv.forSpreadsheet());
    }

    // ------------------------------------------------------------------ helpers

    /** An approved or paid run (404 for unknown ids, 409 for a draft). */
    private PayrollRun locked(UUID runId) {
        TenantContext.require();
        PayrollRun run = runs.findById(runId).orElseThrow(() -> ApiException.notFound("Payroll run"));
        if (!run.getStatus().locked()) {
            throw new ApiException(HttpStatus.CONFLICT, "Not approved",
                    "Files are made from an approved month only. Approve the month's payroll first.");
        }
        return run;
    }

    private Map<UUID, StaffPayProfile> profilesByUser() {
        return profiles.findAll().stream().collect(Collectors.toMap(StaffPayProfile::getUserId, Function.identity()));
    }

    private static String beneficiary(Payslip s, StaffPayProfile p) {
        return p.getAccountHolderName() != null ? p.getAccountHolderName() : s.getEmployeeName();
    }

    /** Who exported which file of which month and how many rows: never amounts or names. */
    private void exported(Actor actor, PayrollRun run, String file, int rows) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("month", run.yearMonth().toString());
        details.put("file", file);
        details.put("rows", rows);
        audit.record(actor, "payroll.exported", "payroll_run", run.getId(), details);
    }

    /** Whole rupees, halves up. */
    static String rupees(long paise) {
        return BigDecimal.valueOf(paise).divide(BigDecimal.valueOf(100)).setScale(0, RoundingMode.HALF_UP)
                .toPlainString();
    }

    /** Whole days, halves up. */
    static String days(BigDecimal days) {
        return days == null ? "0" : days.setScale(0, RoundingMode.HALF_UP).toPlainString();
    }
}
