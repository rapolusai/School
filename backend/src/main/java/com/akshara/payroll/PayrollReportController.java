package com.akshara.payroll;

import java.util.List;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.akshara.payroll.PayrollViews.Register;
import com.akshara.payroll.PayrollViews.StatutorySummary;
import com.akshara.payroll.PayrollViews.YearToDate;
import com.akshara.staff.StaffService.DepartmentRef;

/**
 * The payroll reports of the reports hub (payroll.read): the payroll register by department, year to date per member
 * of staff and the statutory summary, each as JSON and as a CSV for Excel (audited).
 */
@RestController
@RequestMapping("/api/payroll/reports")
@PreAuthorize(PayrollAccess.READ)
public class PayrollReportController {

    private final PayrollReportService reports;

    PayrollReportController(PayrollReportService reports) {
        this.reports = reports;
    }

    @GetMapping("/register")
    public Register register(@RequestParam(required = false) String month,
            @RequestParam(required = false) UUID departmentId) {
        return reports.register(month, departmentId);
    }

    @GetMapping("/register.csv")
    public ResponseEntity<byte[]> registerCsv(@RequestParam(required = false) String month,
            @RequestParam(required = false) UUID departmentId) {
        return PayrollRunController.file(reports.registerCsv(month, departmentId, PayrollAccess.actor()));
    }

    @GetMapping("/year-to-date")
    public YearToDate yearToDate(@RequestParam(required = false) String financialYear) {
        return reports.yearToDate(financialYear);
    }

    @GetMapping("/year-to-date.csv")
    public ResponseEntity<byte[]> yearToDateCsv(@RequestParam(required = false) String financialYear) {
        return PayrollRunController.file(reports.yearToDateCsv(financialYear, PayrollAccess.actor()));
    }

    @GetMapping("/statutory")
    public StatutorySummary statutory(@RequestParam(required = false) String financialYear) {
        return reports.statutory(financialYear);
    }

    @GetMapping("/statutory.csv")
    public ResponseEntity<byte[]> statutoryCsv(@RequestParam(required = false) String financialYear) {
        return PayrollRunController.file(reports.statutoryCsv(financialYear, PayrollAccess.actor()));
    }

    /** The departments the register can be filtered by. */
    @GetMapping("/departments")
    public List<DepartmentRef> departments() {
        return reports.departments();
    }
}
