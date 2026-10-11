package com.akshara.payroll;

import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.akshara.audit.AuditService;
import com.akshara.audit.AuditService.Actor;
import com.akshara.payroll.PayrollCsv.Money;
import com.akshara.payroll.PayrollViews.Download;
import com.akshara.payroll.PayrollViews.Register;
import com.akshara.payroll.PayrollViews.RegisterDepartment;
import com.akshara.payroll.PayrollViews.RegisterRow;
import com.akshara.payroll.PayrollViews.RegisterTotals;
import com.akshara.payroll.PayrollViews.StatutoryMonth;
import com.akshara.payroll.PayrollViews.StatutorySummary;
import com.akshara.payroll.PayrollViews.YearToDate;
import com.akshara.payroll.PayrollViews.YtdRow;
import com.akshara.shared.ApiException;
import com.akshara.shared.TenantContext;
import com.akshara.staff.StaffPayrollInputs;
import com.akshara.staff.StaffService.DepartmentRef;

/**
 * The payroll reports of the reports hub: the payroll register of a month by department, year to date per member of
 * staff, and the statutory summary (PF, ESI, professional tax, TDS) by month. Year to date and the statutory summary
 * count approved and paid months only. Each has a CSV download (UTF-8 with a byte order mark, for Excel), audited as
 * payroll.exported with its filters and row count.
 */
@Service
@Transactional(readOnly = true)
public class PayrollReportService {

    private final PayrollRunRepository runs;
    private final PayslipRepository payslips;
    private final StaffPayrollInputs staff;
    private final AuditService audit;

    PayrollReportService(PayrollRunRepository runs, PayslipRepository payslips, StaffPayrollInputs staff,
            AuditService audit) {
        this.runs = runs;
        this.payslips = payslips;
        this.staff = staff;
        this.audit = audit;
    }

    // ------------------------------------------------------------------ register

    /**
     * The month's payslips by department (the latest month with a run by default), drafts included and marked as such.
     * 404 for another school's department.
     */
    public Register register(String monthText, UUID departmentId) {
        TenantContext.require();
        if (departmentId != null && staff.departments().stream().noneMatch(d -> d.id().equals(departmentId))) {
            throw ApiException.notFound("Department");
        }
        Optional<PayrollRun> run;
        YearMonth month;
        if (monthText == null || monthText.isBlank()) {
            run = runs.findAllNewestFirst().stream().findFirst();
            month = run.map(PayrollRun::yearMonth).orElse(PayrollSettingsService.thisMonth());
        } else {
            month = PayrollAccess.month(monthText, "month");
            run = runs.findByMonth(month.atDay(1));
        }
        List<Payslip> slips = run.map(r -> payslips.findByRun(r.getId())).orElse(List.of()).stream()
                .filter(s -> departmentId == null || departmentId.equals(s.getDepartmentId())).toList();
        Map<String, List<Payslip>> byDepartment = slips.stream().collect(Collectors.groupingBy(
                s -> s.getDepartmentName() == null ? "" : s.getDepartmentName(), LinkedHashMap::new,
                Collectors.toList()));
        List<RegisterDepartment> departments = byDepartment.entrySet().stream()
                .sorted(Comparator.comparing((Map.Entry<String, List<Payslip>> e) -> e.getKey().isEmpty())
                        .thenComparing(e -> e.getKey().toLowerCase(Locale.ROOT)))
                .map(e -> {
                    List<RegisterRow> rows = e.getValue().stream().map(PayrollReportService::registerRow)
                            .sorted(Comparator.comparing((RegisterRow r) -> r.name().toLowerCase(Locale.ROOT)))
                            .toList();
                    return new RegisterDepartment(e.getValue().getFirst().getDepartmentId(),
                            e.getKey().isEmpty() ? null : e.getKey(), rows, totals(rows));
                })
                .toList();
        List<RegisterRow> all = departments.stream().flatMap(d -> d.rows().stream()).toList();
        return new Register(month.toString(), run.map(PayrollRun::getId).orElse(null),
                run.map(PayrollRun::getStatus).orElse(null), departmentId, departments, totals(all));
    }

    @Transactional
    public Download registerCsv(String monthText, UUID departmentId, Actor actor) {
        Register report = register(monthText, departmentId);
        PayrollCsv csv = new PayrollCsv("Department", "Employee code", "Name", "Designation", "Payable days",
                "Loss of pay days", "Gross", "PF", "ESI", "Professional tax", "TDS", "Other deductions",
                "Total deductions", "Net pay", "Employer contributions");
        for (RegisterDepartment d : report.departments()) {
            for (RegisterRow r : d.rows()) {
                csv.row(d.departmentName(), r.employeeCode(), r.name(), r.designation(), r.payableDays(),
                        r.lopDays(), new Money(r.grossPaise()), new Money(r.pfEmployeePaise()),
                        new Money(r.esiEmployeePaise()), new Money(r.ptPaise()), new Money(r.tdsPaise()),
                        new Money(r.otherDeductionsPaise()), new Money(r.deductionsPaise()), new Money(r.netPaise()),
                        new Money(r.employerPaise()));
            }
        }
        int rows = csv.rows();
        RegisterTotals t = report.totals();
        csv.row("Total", null, t.count() + " staff", null, null, null, new Money(t.grossPaise()),
                new Money(t.pfEmployeePaise()), new Money(t.esiEmployeePaise()), new Money(t.ptPaise()),
                new Money(t.tdsPaise()), new Money(t.otherDeductionsPaise()), new Money(t.deductionsPaise()),
                new Money(t.netPaise()), new Money(t.employerPaise()));
        Map<String, Object> filters = new LinkedHashMap<>();
        filters.put("departmentId", departmentId == null ? "" : departmentId.toString());
        exported(actor, "REGISTER", "month", report.month(), filters, rows);
        return new Download("payroll-register-" + report.month() + ".csv", PayrollCsv.CONTENT_TYPE,
                csv.forSpreadsheet());
    }

    // ------------------------------------------------------------------ year to date

    /** Per member of staff over the approved and paid months of the financial year (the current one by default). */
    public YearToDate yearToDate(String financialYear) {
        TenantContext.require();
        YearMonth start = financialYear == null || financialYear.isBlank()
                ? PayrollMath.financialYearStart(PayrollSettingsService.thisMonth())
                : PayrollAccess.financialYearStart(financialYear, "financialYear");
        List<PayrollRun> locked = runs.findLockedBetween(start.atDay(1), start.plusMonths(11).atDay(1));
        Map<UUID, PayrollRun> runById = locked.stream().collect(Collectors.toMap(PayrollRun::getId, r -> r));
        List<Payslip> slips = locked.isEmpty() ? List.of() : payslips.findByRuns(runById.keySet());
        Map<UUID, List<Payslip>> byUser = slips.stream().collect(Collectors.groupingBy(Payslip::getUserId));
        List<YtdRow> rows = byUser.values().stream().map(list -> {
            Payslip latest = list.stream()
                    .max(Comparator.comparing(s -> runById.get(s.getRunId()).getMonth())).orElseThrow();
            return ytdRow(latest.getUserId(), latest.getEmployeeName(), latest.getEmployeeCode(),
                    latest.getDepartmentName(), list);
        }).sorted(Comparator.comparing((YtdRow r) -> r.name().toLowerCase(Locale.ROOT))).toList();
        return new YearToDate(PayrollMath.financialYear(start),
                locked.stream().map(r -> r.yearMonth().toString()).toList(), rows,
                ytdRow(null, "Total", null, null, slips));
    }

    @Transactional
    public Download yearToDateCsv(String financialYear, Actor actor) {
        YearToDate report = yearToDate(financialYear);
        PayrollCsv csv = new PayrollCsv("Employee code", "Name", "Department", "Months paid", "Gross", "PF", "ESI",
                "Professional tax", "TDS", "Other deductions", "Net pay", "Employer contributions");
        for (YtdRow r : report.rows()) {
            ytdCells(csv, r);
        }
        int rows = csv.rows();
        ytdCells(csv, report.totals());
        exported(actor, "YTD", "financialYear", report.financialYear(), Map.of(), rows);
        return new Download("payroll-year-to-date-" + report.financialYear() + ".csv", PayrollCsv.CONTENT_TYPE,
                csv.forSpreadsheet());
    }

    private static void ytdCells(PayrollCsv csv, YtdRow r) {
        csv.row(r.employeeCode(), r.name(), r.departmentName(), r.months(), new Money(r.grossPaise()),
                new Money(r.pfEmployeePaise()), new Money(r.esiEmployeePaise()), new Money(r.ptPaise()),
                new Money(r.tdsPaise()), new Money(r.otherDeductionsPaise()), new Money(r.netPaise()),
                new Money(r.employerPaise()));
    }

    // ------------------------------------------------------------------ statutory summary

    /** PF, ESI, professional tax and TDS by approved or paid month of the financial year (the current by default). */
    public StatutorySummary statutory(String financialYear) {
        TenantContext.require();
        YearMonth start = financialYear == null || financialYear.isBlank()
                ? PayrollMath.financialYearStart(PayrollSettingsService.thisMonth())
                : PayrollAccess.financialYearStart(financialYear, "financialYear");
        List<PayrollRun> locked = runs.findLockedBetween(start.atDay(1), start.plusMonths(11).atDay(1));
        Map<UUID, List<Payslip>> byRun = locked.isEmpty() ? Map.of()
                : payslips.findByRuns(locked.stream().map(PayrollRun::getId).toList()).stream()
                        .collect(Collectors.groupingBy(Payslip::getRunId));
        List<StatutoryMonth> months = new ArrayList<>();
        for (PayrollRun r : locked) {
            List<Payslip> slips = byRun.getOrDefault(r.getId(), List.of());
            PayrollRun.Totals t = r.totals();
            long eps = slips.stream().mapToLong(Payslip::getEpsPaise).sum();
            long epf = slips.stream().mapToLong(Payslip::getEpfEmployerPaise).sum();
            months.add(month(r.yearMonth().toString(), r.getStatus(), t.staffCount(), t.pfEmployee(), epf, eps,
                    t.edli(), t.pfAdmin(), t.esiEmployee(), t.esiEmployer(), t.pt(), t.tds()));
        }
        StatutoryMonth total = month(null, null, 0, sum(months, StatutoryMonth::pfEmployeePaise),
                sum(months, StatutoryMonth::epfEmployerPaise), sum(months, StatutoryMonth::epsPaise),
                sum(months, StatutoryMonth::edliPaise), sum(months, StatutoryMonth::pfAdminPaise),
                sum(months, StatutoryMonth::esiEmployeePaise), sum(months, StatutoryMonth::esiEmployerPaise),
                sum(months, StatutoryMonth::ptPaise), sum(months, StatutoryMonth::tdsPaise));
        return new StatutorySummary(PayrollMath.financialYear(start), months, total);
    }

    @Transactional
    public Download statutoryCsv(String financialYear, Actor actor) {
        StatutorySummary report = statutory(financialYear);
        PayrollCsv csv = new PayrollCsv("Month", "Staff", "PF employee", "PF employer (EPF)", "Pension (EPS)", "EDLI",
                "PF admin charges", "ESI employee", "ESI employer", "Professional tax", "TDS", "Total");
        for (StatutoryMonth m : report.months()) {
            statutoryCells(csv, m.month(), m.staffCount(), m);
        }
        int rows = csv.rows();
        statutoryCells(csv, "Total", null, report.totals());
        exported(actor, "STATUTORY", "financialYear", report.financialYear(), Map.of(), rows);
        return new Download("payroll-statutory-" + report.financialYear() + ".csv", PayrollCsv.CONTENT_TYPE,
                csv.forSpreadsheet());
    }

    private static void statutoryCells(PayrollCsv csv, String label, Integer staffCount, StatutoryMonth m) {
        csv.row(label, staffCount, new Money(m.pfEmployeePaise()), new Money(m.epfEmployerPaise()),
                new Money(m.epsPaise()), new Money(m.edliPaise()), new Money(m.pfAdminPaise()),
                new Money(m.esiEmployeePaise()), new Money(m.esiEmployerPaise()), new Money(m.ptPaise()),
                new Money(m.tdsPaise()), new Money(m.totalPaise()));
    }

    // ------------------------------------------------------------------ helpers

    /** The departments the register can be filtered by. */
    public List<DepartmentRef> departments() {
        TenantContext.require();
        return staff.departments();
    }

    private static StatutoryMonth month(String month, RunStatus status, int staffCount, long pfEmployee,
            long epfEmployer, long eps, long edli, long pfAdmin, long esiEmployee, long esiEmployer, long pt,
            long tds) {
        return new StatutoryMonth(month, status, staffCount, pfEmployee, epfEmployer, eps, edli, pfAdmin, esiEmployee,
                esiEmployer, pt, tds, pfEmployee + epfEmployer + eps + edli + pfAdmin + esiEmployee + esiEmployer + pt
                        + tds);
    }

    private static long sum(List<StatutoryMonth> months, java.util.function.ToLongFunction<StatutoryMonth> f) {
        return months.stream().mapToLong(f).sum();
    }

    static RegisterRow registerRow(Payslip s) {
        long other = s.getDeductionsPaise() - s.getPfEmployeePaise() - s.getEsiEmployeePaise() - s.getPtPaise()
                - s.getTdsPaise();
        return new RegisterRow(s.getUserId(), s.getEmployeeName(), s.getEmployeeCode(), s.getDesignation(),
                s.getPayableDays(), s.getLopDays(), s.getGrossPaise(), s.getPfEmployeePaise(), s.getEsiEmployeePaise(),
                s.getPtPaise(), s.getTdsPaise(), other, s.getDeductionsPaise(), s.getNetPaise(), s.getEmployerPaise());
    }

    static RegisterTotals totals(List<RegisterRow> rows) {
        return new RegisterTotals(rows.size(), rows.stream().mapToLong(RegisterRow::grossPaise).sum(),
                rows.stream().mapToLong(RegisterRow::pfEmployeePaise).sum(),
                rows.stream().mapToLong(RegisterRow::esiEmployeePaise).sum(),
                rows.stream().mapToLong(RegisterRow::ptPaise).sum(),
                rows.stream().mapToLong(RegisterRow::tdsPaise).sum(),
                rows.stream().mapToLong(RegisterRow::otherDeductionsPaise).sum(),
                rows.stream().mapToLong(RegisterRow::deductionsPaise).sum(),
                rows.stream().mapToLong(RegisterRow::netPaise).sum(),
                rows.stream().mapToLong(RegisterRow::employerPaise).sum());
    }

    private static YtdRow ytdRow(UUID userId, String name, String code, String department, List<Payslip> slips) {
        long gross = 0;
        long pf = 0;
        long esi = 0;
        long pt = 0;
        long tds = 0;
        long deductions = 0;
        long net = 0;
        long employer = 0;
        for (Payslip s : slips) {
            gross += s.getGrossPaise();
            pf += s.getPfEmployeePaise();
            esi += s.getEsiEmployeePaise();
            pt += s.getPtPaise();
            tds += s.getTdsPaise();
            deductions += s.getDeductionsPaise();
            net += s.getNetPaise();
            employer += s.getEmployerPaise();
        }
        int months = (int) slips.stream().map(Payslip::getRunId).filter(Objects::nonNull).distinct().count();
        return new YtdRow(userId, name, code, department, months, gross, pf, esi, pt, tds,
                deductions - pf - esi - pt - tds, net, employer);
    }

    private void exported(Actor actor, String file, String periodKey, String period, Map<String, Object> filters,
            int rows) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put(periodKey, period);
        details.put("file", file);
        details.putAll(filters);
        details.put("rows", rows);
        audit.record(actor, "payroll.exported", "payroll_report", null, details);
    }
}
