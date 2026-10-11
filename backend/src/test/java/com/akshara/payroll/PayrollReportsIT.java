package com.akshara.payroll;

import static com.akshara.payroll.PayrollSchool.body;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;

import com.jayway.jsonpath.JsonPath;

/**
 * The payroll reports of the reports hub: the register of a month by department, year to date per member of staff
 * and the statutory summary, as JSON and as Excel-ready CSV (audited), for payroll readers only.
 */
class PayrollReportsIT extends PayrollIntegrationTest {

    @Test
    void theRegisterYearToDateAndStatutorySummaryFollowTheApprovedMonths() throws Exception {
        PayrollSchool s = new PayrollSchool(api).build();
        String run = s.lastMonthRun();
        s.approve(run);
        api.post("/api/payroll/runs/" + run + "/paid", s.accountant.accessToken(), """
                {"paidOn":"%s"}""".formatted(s.today)).andExpect(status().isOk());
        // This month's draft is in the register but not in the year's figures.
        String draft = s.run(s.thisMonth);
        String financialYear = PayrollMath.financialYear(s.lastMonth);
        String totals = body(api.get("/api/payroll/runs/" + run, s.admin.accessToken()));
        Object net = JsonPath.read(totals, "$.totals.netPaise");
        Object gross = JsonPath.read(totals, "$.totals.grossPaise");

        api.get("/api/payroll/reports/register", s.principal.accessToken()).andExpect(status().isForbidden());
        api.get("/api/payroll/reports/register", s.raviSession.accessToken()).andExpect(status().isForbidden());
        s.principalCanReadPayroll();
        String token = s.principal.accessToken();

        api.get("/api/payroll/reports/register?month=" + s.lastMonth, token)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.month").value(s.lastMonth.toString()))
                .andExpect(jsonPath("$.runId").value(run))
                .andExpect(jsonPath("$.status").value("PAID"))
                .andExpect(jsonPath("$.departments[*].departmentName", contains("Office", "Primary")))
                .andExpect(jsonPath("$.departments[1].rows[*].name", contains("Ravi Kumar", "Sita Devi")))
                .andExpect(jsonPath("$.departments[0].totals.count").value(1))
                .andExpect(jsonPath("$.departments[0].totals.netPaise").value(34_000_00))
                .andExpect(jsonPath("$.totals.count").value(3))
                .andExpect(jsonPath("$.totals.netPaise").value(net))
                .andExpect(jsonPath("$.totals.grossPaise").value(gross));
        // The latest month with a run by default; a department filter; another school's or unknown department 404.
        api.get("/api/payroll/reports/register", token)
                .andExpect(jsonPath("$.month").value(s.thisMonth.toString()))
                .andExpect(jsonPath("$.runId").value(draft))
                .andExpect(jsonPath("$.status").value("DRAFT"));
        api.get("/api/payroll/reports/register?month=" + s.lastMonth + "&departmentId=" + s.office, token)
                .andExpect(jsonPath("$.departments.length()").value(1))
                .andExpect(jsonPath("$.departments[0].rows[0].name").value("Meena Reddy"))
                .andExpect(jsonPath("$.totals.count").value(1));
        api.get("/api/payroll/reports/register?departmentId=" + UUID.randomUUID(), token)
                .andExpect(status().isNotFound());
        api.get("/api/payroll/reports/register?month=2026-13", token)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.month").exists());
        api.get("/api/payroll/reports/register?month=2020-01", token)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.runId").value((Object) null))
                .andExpect(jsonPath("$.departments.length()").value(0))
                .andExpect(jsonPath("$.totals.count").value(0));
        api.get("/api/payroll/reports/departments", token)
                .andExpect(jsonPath("$[*].name", contains("Office", "Primary")));

        ResultActions register = api.get("/api/payroll/reports/register.csv?month=" + s.lastMonth, token)
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition",
                        "attachment; filename=\"payroll-register-" + s.lastMonth + ".csv\""));
        assertThat(PayslipsAndFilesIT.startsWithBom(PayslipsAndFilesIT.bytes(register))).isTrue();
        List<String> lines = PayslipsAndFilesIT.text(register).lines().toList();
        assertThat(lines.getFirst()).startsWith("Department,Employee code,Name,Designation,Payable days");
        assertThat(lines).hasSize(5);
        assertThat(lines.get(2)).startsWith("Primary,T-1,Ravi Kumar,Teacher,");
        assertThat(lines.getLast()).startsWith("Total,,3 staff,");

        // Year to date: approved and paid months only.
        api.get("/api/payroll/reports/year-to-date?financialYear=" + financialYear, token)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.financialYear").value(financialYear))
                .andExpect(jsonPath("$.months", contains(s.lastMonth.toString())))
                .andExpect(jsonPath("$.rows[*].name", contains("Meena Reddy", "Ravi Kumar", "Sita Devi")))
                .andExpect(jsonPath("$.rows[?(@.name == 'Ravi Kumar')].months").value(1))
                .andExpect(jsonPath("$.rows[?(@.name == 'Ravi Kumar')].grossPaise").value(50_000_00))
                .andExpect(jsonPath("$.rows[?(@.name == 'Ravi Kumar')].otherDeductionsPaise").value(1_000_00))
                .andExpect(jsonPath("$.totals.netPaise").value(net));
        api.get("/api/payroll/reports/year-to-date?financialYear=2026-28", token)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.financialYear").exists());
        api.get("/api/payroll/reports/year-to-date?financialYear=last", token)
                .andExpect(status().isBadRequest());
        api.get("/api/payroll/reports/year-to-date?financialYear=2019-20", token)
                .andExpect(jsonPath("$.rows.length()").value(0))
                .andExpect(jsonPath("$.totals.grossPaise").value(0));
        ResultActions ytd = api.get("/api/payroll/reports/year-to-date.csv?financialYear=" + financialYear, token)
                .andExpect(status().isOk());
        assertThat(PayslipsAndFilesIT.text(ytd).lines().toList()).hasSize(5);

        // The statutory summary by month.
        api.get("/api/payroll/reports/statutory?financialYear=" + financialYear, token)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.months.length()").value(1))
                .andExpect(jsonPath("$.months[0].month").value(s.lastMonth.toString()))
                .andExpect(jsonPath("$.months[0].status").value("PAID"))
                .andExpect(jsonPath("$.months[0].staffCount").value(3))
                .andExpect(jsonPath("$.months[0].ptPaise").value(400_00))
                .andExpect(jsonPath("$.months[0].pfAdminPaise").value(500_00))
                .andExpect(jsonPath("$.months[0].epsPaise").value(n(1_250_00 * 2 + eps(s))))
                .andExpect(jsonPath("$.totals.ptPaise").value(400_00));
        ResultActions statutory = api.get("/api/payroll/reports/statutory.csv?financialYear=" + financialYear,
                token).andExpect(status().isOk());
        assertThat(PayslipsAndFilesIT.text(statutory).lines().toList()).hasSize(3);

        // Each download is audited with the period, the file and the row count.
        List<Map<String, Object>> exported = audit(s.admin, "payroll.exported");
        assertThat(exported).hasSize(3);
        assertThat(exported).allSatisfy(e -> {
            assertThat(e).containsEntry("entityType", "payroll_report").containsEntry("actorName", "Lakshmi Iyer");
            assertThat(e.toString()).doesNotContain("Ravi").doesNotContain("50000");
        });
        assertThat(details(exported.getLast())).containsEntry("file", "REGISTER")
                .containsEntry("month", s.lastMonth.toString()).containsEntry("rows", 3);
        assertThat(details(exported.getFirst())).containsEntry("file", "STATUTORY")
                .containsEntry("financialYear", financialYear).containsEntry("rows", 1);
    }

    /** Sita's EPS: 8.33% of her prorated basic, to the nearest rupee. */
    static long eps(PayrollSchool s) {
        long days = s.lastMonth.lengthOfMonth();
        long basic = PayrollRunIT.prorate(9_000_00, days - 15, days);
        return PayrollMath.pf(basic, false, PayrollMathTest.PF).eps();
    }

    static Object n(long value) {
        return PayrollRunIT.n(value);
    }
}
