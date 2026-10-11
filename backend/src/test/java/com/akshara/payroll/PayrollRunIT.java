package com.akshara.payroll;

import static com.akshara.payroll.PayrollMathTest.ESI_EE;
import static com.akshara.payroll.PayrollMathTest.ESI_ER;
import static com.akshara.payroll.PayrollMathTest.PF;
import static com.akshara.payroll.PayrollSchool.body;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.ResultActions;

import com.akshara.support.TestApi;
import com.jayway.jsonpath.JsonPath;

/**
 * Monthly runs: who is paid and for how many days (loss of pay from approved leave, joiners and leavers, calendar or
 * working days), adjustments and TDS overrides with reasons, approval (which locks the month and publishes
 * PayrollApproved), marking a month paid, and corrections in the next month.
 */
@RecordApplicationEvents
class PayrollRunIT extends PayrollIntegrationTest {

    static final String RAVI = "$.payslips[?(@.name == 'Ravi Kumar')]";
    static final String MEENA = "$.payslips[?(@.name == 'Meena Reddy')]";
    static final String SITA = "$.payslips[?(@.name == 'Sita Devi')]";
    static final String KIRAN = "$.payslips[?(@.name == 'Kiran Rao')]";

    @Autowired
    ApplicationEvents events;

    static String month(Object month) {
        return """
                {"month":"%s"}""".formatted(month);
    }

    /** JSON numbers within an int are read back as Integer. */
    static Object n(long value) {
        return value >= Integer.MIN_VALUE && value <= Integer.MAX_VALUE ? (Object) (int) value : value;
    }

    static long prorate(long paise, long days, long basis) {
        return PayrollMath.prorate(paise, BigDecimal.valueOf(days), BigDecimal.valueOf(basis));
    }

    static long prorate(long paise, double days, long basis) {
        return PayrollMath.prorate(paise, BigDecimal.valueOf(days), BigDecimal.valueOf(basis));
    }

    static long workingDays(LocalDate from, LocalDate to) {
        return from.datesUntil(to.plusDays(1)).filter(d -> d.getDayOfWeek() != DayOfWeek.SUNDAY).count();
    }

    @Test
    void aDraftPaysEveryoneOnTheRollWithAStructureForTheirDays() throws Exception {
        PayrollSchool s = new PayrollSchool(api).build();
        s.lossOfPayForRavi();
        String month = s.lastMonth.toString();
        api.post("/api/payroll/runs", s.principal.accessToken(), month(month)).andExpect(status().isForbidden());
        api.post("/api/payroll/runs", s.raviSession.accessToken(), month(month)).andExpect(status().isForbidden());
        api.get("/api/payroll/runs", s.raviSession.accessToken()).andExpect(status().isForbidden());

        long days = s.lastMonth.lengthOfMonth();
        // Ravi: two days of approved loss-of-pay leave. Sita: joined on the 16th.
        long raviGross = prorate(30_000_00, days - 2, days) + prorate(12_000_00, days - 2, days)
                + prorate(8_000_00, days - 2, days);
        long sitaBasic = prorate(9_000_00, days - 15, days);
        long sitaGross = sitaBasic + 2 * prorate(4_500_00, days - 15, days);
        long sitaPf = PayrollMath.pf(sitaBasic, false, PF).employee();
        long sitaEsi = PayrollMath.esi(true, sitaGross, ESI_EE, ESI_ER).employee();
        String financialYear = PayrollMath.financialYear(s.lastMonth);

        ResultActions run = api.post("/api/payroll/runs", s.accountant.accessToken(), month(month))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.month").value(month))
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.prorationBasis").value("CALENDAR_DAYS"))
                .andExpect(jsonPath("$.daysInMonth").value(n(days)))
                .andExpect(jsonPath("$.createdByName").value("Meena Reddy"))
                .andExpect(jsonPath("$.taxFinancialYear").value("2025-26"))
                .andExpect(jsonPath("$.payslips[*].name", contains("Meena Reddy", "Ravi Kumar", "Sita Devi")))
                .andExpect(jsonPath(RAVI + ".lopDays").value(2.0))
                .andExpect(jsonPath(RAVI + ".payableDays").value((double) (days - 2)))
                .andExpect(jsonPath(RAVI + ".grossPaise").value(n(raviGross)))
                // PF on the ₹15,000 ceiling, ₹200 Telangana professional tax and the ₹1,000 loan recovery.
                .andExpect(jsonPath(RAVI + ".pfEmployeePaise").value(1_800_00))
                .andExpect(jsonPath(RAVI + ".esiEmployeePaise").value(0))
                .andExpect(jsonPath(RAVI + ".ptPaise").value(200_00))
                .andExpect(jsonPath(RAVI + ".tdsPaise").value(0))
                .andExpect(jsonPath(RAVI + ".otherDeductionsPaise").value(1_000_00))
                .andExpect(jsonPath(RAVI + ".netPaise").value(n(raviGross - 3_000_00)))
                .andExpect(jsonPath(RAVI + ".employeeCode").value("T-1"))
                .andExpect(jsonPath(RAVI + ".departmentName").value("Primary"))
                .andExpect(jsonPath(RAVI + ".hasBank").value(true))
                .andExpect(jsonPath(SITA + ".lopDays").value(0.0))
                .andExpect(jsonPath(SITA + ".payableDays").value((double) (days - 15)))
                .andExpect(jsonPath(SITA + ".grossPaise").value(n(sitaGross)))
                .andExpect(jsonPath(SITA + ".pfEmployeePaise").value(n(sitaPf)))
                .andExpect(jsonPath(SITA + ".esiEmployeePaise").value(n(sitaEsi)))
                .andExpect(jsonPath(SITA + ".ptPaise").value(0))
                .andExpect(jsonPath(SITA + ".netPaise").value(n(sitaGross - sitaPf - sitaEsi)))
                .andExpect(jsonPath(MEENA + ".grossPaise").value(36_000_00))
                .andExpect(jsonPath(MEENA + ".netPaise").value(34_000_00))
                .andExpect(jsonPath("$.totals.staffCount").value(3))
                .andExpect(jsonPath("$.totals.grossPaise").value(n(raviGross + sitaGross + 36_000_00)))
                .andExpect(jsonPath("$.totals.pfEmployeePaise").value(n(3_600_00 + sitaPf)))
                .andExpect(jsonPath("$.totals.esiEmployeePaise").value(n(sitaEsi)))
                .andExpect(jsonPath("$.totals.ptPaise").value(400_00))
                // EPF administration charges are at least ₹500 a month.
                .andExpect(jsonPath("$.totals.pfAdminPaise").value(500_00))
                .andExpect(jsonPath("$.warnings[?(@.code == 'NO_STRUCTURE')].name",
                        containsInAnyOrder("Asha Admin", "Lakshmi Iyer")))
                .andExpect(jsonPath("$.warnings[?(@.code == 'NO_UAN')].name", contains("Meena Reddy")))
                .andExpect(jsonPath("$.warnings[*].code", not(hasItem("NO_BANK"))))
                .andExpect(jsonPath("$.warnings[*].code", financialYear.equals("2025-26")
                        ? not(hasItem("TAX_RULES_EARLIER_YEAR")) : hasItem("TAX_RULES_EARLIER_YEAR")));
        String id = TestApi.read(run, "$.id");
        long net = ((Number) JsonPath.read(body(run), "$.totals.netPaise")).longValue();
        assertThat(net).isEqualTo(raviGross - 3_000_00 + sitaGross - sitaPf - sitaEsi + 34_000_00);

        // One run per month, up to the current month.
        api.post("/api/payroll/runs", s.accountant.accessToken(), month(month))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.errors.month").exists());
        api.post("/api/payroll/runs", s.accountant.accessToken(), month(s.thisMonth.plusMonths(1)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.month").exists());
        api.post("/api/payroll/runs", s.accountant.accessToken(), month("2026-13"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.month").exists());
        api.post("/api/payroll/runs", s.accountant.accessToken(), month(""))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.month").exists());

        api.get("/api/payroll/runs", s.accountant.accessToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.runs.length()").value(1))
                .andExpect(jsonPath("$.runs[0].id").value(id))
                .andExpect(jsonPath("$.runs[0].status").value("DRAFT"))
                .andExpect(jsonPath("$.runs[0].totals.staffCount").value(3))
                .andExpect(jsonPath("$.suggestedMonth").value(s.thisMonth.toString()))
                .andExpect(jsonPath("$.needStructure").value(2));
        api.get("/api/payroll/runs/" + id, s.admin.accessToken()).andExpect(status().isOk())
                .andExpect(jsonPath("$.version").exists());
        api.get("/api/payroll/runs/" + UUID.randomUUID(), s.admin.accessToken()).andExpect(status().isNotFound());
        api.post("/api/payroll/runs/" + id + "/recalculate", s.principal.accessToken(), null)
                .andExpect(status().isForbidden());

        Map<String, Object> created = audit(s.admin, "payroll_run.created").getFirst();
        assertThat(created).containsEntry("actorName", "Meena Reddy").containsEntry("entityType", "payroll_run");
        assertThat(details(created)).isEqualTo(Map.of("month", month, "staffCount", 3));
    }

    @Test
    void joinersAndLeaversArePaidForTheirWorkingDaysWhenTheSchoolProratesOnWorkingDays() throws Exception {
        PayrollSchool s = new PayrollSchool(api).build();
        s.leaver();
        api.put("/api/payroll/settings", s.admin.accessToken(),
                PayrollSetupIT.settings(1, "WORKING_DAYS", true, "TG", null, null, null)).andExpect(status().isOk());
        LocalDate start = s.lastMonth.atDay(1);
        LocalDate end = s.lastMonth.atEndOfMonth();
        long working = workingDays(start, end);
        long sitaDays = workingDays(s.lastMonth.atDay(16), end);
        long kiranDays = workingDays(start, s.lastMonth.atDay(10));
        String id = TestApi.read(api.post("/api/payroll/runs", s.accountant.accessToken(), month(s.lastMonth))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.prorationBasis").value("WORKING_DAYS"))
                .andExpect(jsonPath("$.workingDays").value(n(working)))
                .andExpect(jsonPath("$.payslips.length()").value(4))
                .andExpect(jsonPath(RAVI + ".payableDays").value((double) working))
                .andExpect(jsonPath(RAVI + ".grossPaise").value(50_000_00))
                .andExpect(jsonPath(SITA + ".payableDays").value((double) sitaDays))
                .andExpect(jsonPath(SITA + ".grossPaise").value(n(prorate(9_000_00, sitaDays, working)
                        + 2 * prorate(4_500_00, sitaDays, working))))
                .andExpect(jsonPath(KIRAN + ".payableDays").value((double) kiranDays))
                .andExpect(jsonPath(KIRAN + ".grossPaise").value(n(prorate(8_000_00, kiranDays, working)
                        + prorate(4_000_00, kiranDays, working) + prorate(3_000_00, kiranDays, working)))),
                "$.id");
        s.approve(id);
        // This month: Kiran has left, Sita is on the roll for the whole month.
        api.post("/api/payroll/runs", s.accountant.accessToken(), month(s.thisMonth))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.payslips[*].name", contains("Meena Reddy", "Ravi Kumar", "Sita Devi")))
                .andExpect(jsonPath(SITA + ".grossPaise").value(18_000_00));
    }

    @Test
    void adjustmentsAndTdsOverridesChangeADraftAndCarryTheirReasons() throws Exception {
        PayrollSchool s = new PayrollSchool(api).build();
        String run = s.lastMonthRun();
        String token = s.accountant.accessToken();
        String add = "/api/payroll/runs/" + run + "/adjustments";
        long days = s.lastMonth.lengthOfMonth();

        String bonus = TestApi.read(api.post(add, token, """
                {"userId":"%s","kind":"BONUS","amountPaise":500000,"reason":"Annual day bonus"}""".formatted(s.ravi))
                .andExpect(status().isCreated())
                .andExpect(jsonPath(RAVI + ".grossPaise").value(55_000_00))
                .andExpect(jsonPath("$.adjustments.length()").value(1))
                .andExpect(jsonPath("$.adjustments[0].name").value("Ravi Kumar"))
                .andExpect(jsonPath("$.adjustments[0].kind").value("BONUS"))
                .andExpect(jsonPath("$.adjustments[0].amountPaise").value(5_000_00))
                .andExpect(jsonPath("$.adjustments[0].reason").value("Annual day bonus"))
                .andExpect(jsonPath("$.adjustments[0].createdByName").value("Meena Reddy")), "$.adjustments[0].id");
        api.post(add, token, """
                {"userId":"%s","kind":"LOP_CORRECTION","days":1.5,"reason":"Absent without leave"}"""
                .formatted(s.ravi))
                .andExpect(status().isCreated())
                .andExpect(jsonPath(RAVI + ".lopDays").value(1.5))
                .andExpect(jsonPath(RAVI + ".payableDays").value(days - 1.5));
        api.post(add, token, """
                {"userId":"%s","kind":"DEDUCTION","amountPaise":30000,"reason":"Library book lost"}"""
                .formatted(s.ravi))
                .andExpect(status().isCreated())
                .andExpect(jsonPath(RAVI + ".otherDeductionsPaise").value(1_300_00));
        api.post(add, token, """
                {"userId":"%s","kind":"ARREARS","amountPaise":200000,"reason":"Increment from April"}"""
                .formatted(s.meena))
                .andExpect(status().isCreated())
                .andExpect(jsonPath(MEENA + ".grossPaise").value(38_000_00))
                .andExpect(jsonPath("$.adjustments.length()").value(4));

        // Each adjustment is either money or days, for someone in this run, with a reason.
        String[] refused = {
            "{\"userId\":\"%s\",\"kind\":\"BONUS\",\"amountPaise\":100,\"reason\":\"x\"}".formatted(s.lakshmi),
            "{\"userId\":\"%s\",\"kind\":\"BONUS\",\"amountPaise\":100,\"reason\":\"x\"}".formatted(UUID.randomUUID()),
            "{\"userId\":\"%s\",\"kind\":\"LOP_CORRECTION\",\"reason\":\"x\"}".formatted(s.ravi),
            "{\"userId\":\"%s\",\"kind\":\"LOP_CORRECTION\",\"days\":0.3,\"reason\":\"x\"}".formatted(s.ravi),
            "{\"userId\":\"%s\",\"kind\":\"LOP_CORRECTION\",\"days\":0,\"reason\":\"x\"}".formatted(s.ravi),
            "{\"userId\":\"%s\",\"kind\":\"LOP_CORRECTION\",\"days\":32,\"reason\":\"x\"}".formatted(s.ravi),
            "{\"userId\":\"%s\",\"kind\":\"BONUS\",\"reason\":\"x\"}".formatted(s.ravi),
            "{\"userId\":\"%s\",\"kind\":\"BONUS\",\"amountPaise\":-5,\"reason\":\"x\"}".formatted(s.ravi),
            "{\"userId\":\"%s\",\"kind\":\"BONUS\",\"amountPaise\":100,\"reason\":\" \"}".formatted(s.ravi)};
        String[] fields = {"userId", "userId", "days", "days", "days", "days", "amountPaise", "amountPaise",
            "reason"};
        for (int i = 0; i < refused.length; i++) {
            api.post(add, token, refused[i]).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors." + fields[i]).exists());
        }
        api.post(add, token, """
                {"userId":"%s","kind":"LOP_CORRECTION","days":1,"amountPaise":100,"reason":"x"}""".formatted(s.ravi))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.amountPaise").exists());
        api.post(add, token, """
                {"userId":"%s","kind":"BONUS","amountPaise":100,"days":1,"reason":"x"}""".formatted(s.ravi))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.days").exists());
        api.post(add, token, """
                {"userId":"%s","kind":"SALARY","amountPaise":100,"reason":"x"}""".formatted(s.ravi))
                .andExpect(status().isBadRequest());
        api.post(add, s.principal.accessToken(), """
                {"userId":"%s","kind":"BONUS","amountPaise":100,"reason":"x"}""".formatted(s.ravi))
                .andExpect(status().isForbidden());
        api.post("/api/payroll/runs/" + UUID.randomUUID() + "/adjustments", token, """
                {"userId":"%s","kind":"BONUS","amountPaise":100,"reason":"x"}""".formatted(s.ravi))
                .andExpect(status().isNotFound());

        api.delete(add + "/" + bonus, token)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.adjustments.length()").value(3))
                .andExpect(jsonPath(RAVI + ".grossPaise").value(n(prorate(30_000_00, days - 1.5, days)
                        + prorate(12_000_00, days - 1.5, days) + prorate(8_000_00, days - 1.5, days))));
        api.delete(add + "/" + bonus, token).andExpect(status().isNotFound());

        // A TDS override replaces the calculated figure until it is cleared; the calculation is kept beside it.
        String slip = (String) PayrollSchool.first(api.get("/api/payroll/runs/" + run, token), RAVI + ".id");
        String tds = "/api/payroll/runs/" + run + "/payslips/" + slip + "/tds";
        api.put(tds, s.principal.accessToken(), """
                {"amountPaise":150000,"reason":"Other income declared"}""").andExpect(status().isForbidden());
        api.put(tds, token, """
                {"amountPaise":-1,"reason":"Other income declared"}""").andExpect(status().isBadRequest());
        api.put(tds, token, """
                {"amountPaise":150000,"reason":""}""").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.reason").exists());
        api.put("/api/payroll/runs/" + run + "/payslips/" + UUID.randomUUID() + "/tds", token, """
                {"amountPaise":150000,"reason":"Other income declared"}""").andExpect(status().isNotFound());
        api.put(tds, token, """
                {"amountPaise":150000,"reason":"Other income declared"}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath(RAVI + ".tdsPaise").value(1_500_00))
                .andExpect(jsonPath(RAVI + ".tdsOverridden").value(true))
                .andExpect(jsonPath(RAVI + ".tdsOverrideReason").value("Other income declared"))
                .andExpect(jsonPath(RAVI + ".tdsCalculatedPaise").value(0));
        api.post("/api/payroll/runs/" + run + "/recalculate", token, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath(RAVI + ".tdsPaise").value(1_500_00))
                .andExpect(jsonPath(RAVI + ".tdsOverridden").value(true));
        api.delete(tds, token)
                .andExpect(status().isOk())
                .andExpect(jsonPath(RAVI + ".tdsPaise").value(0))
                .andExpect(jsonPath(RAVI + ".tdsOverridden").value(false));

        Map<String, Object> added = audit(s.admin, "payroll_adjustment.added").getLast();
        assertThat(details(added)).containsEntry("kind", "BONUS").containsEntry("reason", "Annual day bonus")
                .containsEntry("userId", s.ravi).containsEntry("month", s.lastMonth.toString())
                .doesNotContainKey("amountPaise");
        assertThat(audit(s.admin, "payroll_adjustment.added")).hasSize(4);
        assertThat(audit(s.admin, "payroll_adjustment.removed")).hasSize(1);
        assertThat(details(audit(s.admin, "payroll_tds.overridden").getFirst()))
                .containsEntry("reason", "Other income declared").containsEntry("userId", s.ravi);
        assertThat(audit(s.admin, "payroll_tds.override_cleared")).hasSize(1);

        // A draft can be thrown away.
        api.delete("/api/payroll/runs/" + run, s.principal.accessToken()).andExpect(status().isForbidden());
        api.delete("/api/payroll/runs/" + run, token).andExpect(status().isNoContent());
        api.get("/api/payroll/runs/" + run, token).andExpect(status().isNotFound());
        api.get("/api/payroll/runs", token).andExpect(jsonPath("$.runs.length()").value(0));
        assertThat(details(audit(s.admin, "payroll_run.deleted").getFirst()))
                .isEqualTo(Map.of("month", s.lastMonth.toString()));
    }

    @Test
    void approvalLocksTheMonthPublishesTheEventAndCorrectionsGoInTheNextMonth() throws Exception {
        PayrollSchool s = new PayrollSchool(api).build();
        String run = s.lastMonthRun();
        String approve = "/api/payroll/runs/" + run + "/approve";
        api.post(approve, s.accountant.accessToken(), null).andExpect(status().isForbidden());
        s.principalCanReadPayroll();
        api.post(approve, s.principal.accessToken(), null).andExpect(status().isForbidden());

        // Meena's loss-of-pay leave is approved after the run was calculated: approving refuses until recalculated.
        String lop = (String) PayrollSchool.first(api.post("/api/leave/types/standard", s.admin.accessToken(), null),
                "$[?(@.code == 'LOP')].id");
        LocalDate wednesday = s.lastMonth.atDay(1).with(TemporalAdjusters.firstInMonth(DayOfWeek.WEDNESDAY));
        String leave = TestApi.read(api.post("/api/leave/requests", s.accountant.accessToken(), """
                {"leaveTypeId":"%s","fromDate":"%s","toDate":"%s","halfDay":false,"reason":"Unwell"}"""
                .formatted(lop, wednesday, wednesday)).andExpect(status().isCreated()), "$.id");
        api.post("/api/leave/requests/" + leave + "/approve", s.admin.accessToken(), null)
                .andExpect(status().isOk());
        events.clear();
        api.post(approve, s.admin.accessToken(), null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Figures changed"));
        api.get("/api/payroll/runs/" + run, s.admin.accessToken())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath(MEENA + ".lopDays").value(0.0));
        assertThat(events.stream(PayrollApproved.class)).isEmpty();
        api.post("/api/payroll/runs/" + run + "/recalculate", s.accountant.accessToken(), null)
                .andExpect(jsonPath(MEENA + ".lopDays").value(1.0));

        ResultActions approved = api.post(approve, s.admin.accessToken(), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.approvedByName").value("Asha Admin"))
                .andExpect(jsonPath("$.approvedAt").exists());
        String json = body(approved);
        List<PayrollApproved> published = events.stream(PayrollApproved.class).toList();
        assertThat(published).hasSize(1);
        PayrollApproved e = published.getFirst();
        assertThat(e.tenantId()).isEqualTo(s.school.tenantId());
        assertThat(e.runId()).isEqualTo(UUID.fromString(run));
        assertThat(e.month()).isEqualTo(s.lastMonth);
        assertThat(e.staffCount()).isEqualTo(3);
        assertThat(e.grossPaise()).isEqualTo(((Number) JsonPath.read(json, "$.totals.grossPaise")).longValue());
        assertThat(e.netPaise()).isEqualTo(((Number) JsonPath.read(json, "$.totals.netPaise")).longValue());
        assertThat(e.deductionsPaise()).isEqualTo(e.grossPaise() - e.netPaise());
        assertThat(e.pfAdminPaise()).isEqualTo(500_00);
        assertThat(e.professionalTaxPaise()).isEqualTo(400_00);
        assertThat(e.approvedById().toString()).isEqualTo(TestApi.read(api.get("/api/me", s.admin.accessToken()),
                "$.id"));
        assertThat(details(audit(s.admin, "payroll_run.approved").getFirst()))
                .isEqualTo(Map.of("month", s.lastMonth.toString(), "staffCount", 3));

        // Locked: nothing about the month changes any more.
        String token = s.accountant.accessToken();
        String slip = (String) PayrollSchool.first(approved, RAVI + ".id");
        api.post("/api/payroll/runs/" + run + "/recalculate", token, null)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.title").value("Payroll locked"));
        api.post("/api/payroll/runs/" + run + "/adjustments", token, """
                {"userId":"%s","kind":"BONUS","amountPaise":100,"reason":"Late"}""".formatted(s.ravi))
                .andExpect(status().isConflict());
        api.put("/api/payroll/runs/" + run + "/payslips/" + slip + "/tds", token, """
                {"amountPaise":100,"reason":"Late"}""").andExpect(status().isConflict());
        api.delete("/api/payroll/runs/" + run + "/payslips/" + slip + "/tds", token)
                .andExpect(status().isConflict());
        api.delete("/api/payroll/runs/" + run, token).andExpect(status().isConflict());
        api.post(approve, s.admin.accessToken(), null).andExpect(status().isConflict());
        // Structures for the approved month cannot change either: a revision starts in a later month.
        s.structureRequest(s.accountant, s.ravi, s.lastMonth, Map.of("BASIC", 1_00L))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.errors.effectiveFrom").exists());
        String used = TestApi.read(api.get("/api/payroll/staff/" + s.ravi, token), "$.structures[0].id");
        api.get("/api/payroll/staff/" + s.ravi, token)
                .andExpect(jsonPath("$.structures[0].locked").value(true))
                .andExpect(jsonPath("$.payslips[0].month").value(s.lastMonth.toString()))
                .andExpect(jsonPath("$.payslips[0].status").value("APPROVED"));
        api.delete("/api/payroll/structures/" + used, token)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.title").value("Structure in use"));
        s.structure(s.ravi, s.thisMonth, Map.of("BASIC", 33_000_00L, "HRA", 13_200_00L, "SPECIAL", 8_800_00L));

        // Marking it paid: on or after the month starts, not in the future, once.
        String paid = "/api/payroll/runs/" + run + "/paid";
        api.post(paid, token, """
                {"paidOn":"%s"}""".formatted(s.lastMonth.atDay(1).minusDays(1)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.paidOn").exists());
        api.post(paid, token, """
                {"paidOn":"%s"}""".formatted(s.today.plusDays(1)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.paidOn").exists());
        api.post(paid, token, "{}").andExpect(status().isBadRequest());
        api.post(paid, s.principal.accessToken(), """
                {"paidOn":"%s"}""".formatted(s.today)).andExpect(status().isForbidden());
        api.post(paid, token, """
                {"paidOn":"%s","reference":" NEFT-0001 "}""".formatted(s.today))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAID"))
                .andExpect(jsonPath("$.paidOn").value(s.today.toString()))
                .andExpect(jsonPath("$.paymentReference").value("NEFT-0001"))
                .andExpect(jsonPath("$.paidByName").value("Meena Reddy"));
        api.post(paid, token, """
                {"paidOn":"%s"}""".formatted(s.today))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.title").value("Already paid"));
        assertThat(details(audit(s.admin, "payroll_run.paid").getFirst()))
                .containsEntry("month", s.lastMonth.toString()).containsEntry("paidOn", s.today.toString());

        // The correction for last month goes into this month as arrears; a draft cannot be marked paid.
        String next = s.run(s.thisMonth);
        api.post("/api/payroll/runs/" + next + "/adjustments", token, """
                {"userId":"%s","kind":"ARREARS","amountPaise":100000,"reason":"Short paid last month"}"""
                .formatted(s.ravi))
                .andExpect(status().isCreated())
                .andExpect(jsonPath(RAVI + ".grossPaise").value(56_000_00));
        api.post("/api/payroll/runs/" + next + "/paid", token, """
                {"paidOn":"%s"}""".formatted(s.today))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.title").value("Not approved"));
        api.get("/api/payroll/runs", token)
                .andExpect(jsonPath("$.runs[*].month", contains(s.thisMonth.toString(), s.lastMonth.toString())))
                .andExpect(jsonPath("$.runs[1].status").value("PAID"))
                .andExpect(jsonPath("$.suggestedMonth").value(s.thisMonth.toString()));
    }

    @Test
    void aMonthWithNobodyToPayCannotBeApproved() throws Exception {
        PayrollSchool s = new PayrollSchool(api);
        YearMonth month = YearMonth.now(PayrollSchool.INDIA);
        String run = TestApi.read(api.post("/api/payroll/runs", s.admin.accessToken(), month(month))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.payslips.length()").value(0))
                .andExpect(jsonPath("$.totals.staffCount").value(0))
                .andExpect(jsonPath("$.totals.pfAdminPaise").value(0))
                // Settings were never saved: professional tax is on by default and needs a state.
                .andExpect(jsonPath("$.warnings[*].code", hasItem("NO_PT_STATE")))
                .andExpect(jsonPath("$.warnings[?(@.code == 'NO_STRUCTURE')].name", contains("Asha Admin"))),
                "$.id");
        api.post("/api/payroll/runs/" + run + "/approve", s.admin.accessToken(), null)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.title").value("Nobody to pay"));
        api.get("/api/payroll/runs/" + run + "/bank-file.csv", s.admin.accessToken())
                .andExpect(status().isConflict());
    }
}
