package com.akshara.payroll;

import static com.akshara.payroll.PayrollSchool.RAVI_ACCOUNT;
import static com.akshara.payroll.PayrollSchool.RAVI_PAN;
import static com.akshara.payroll.PayrollSchool.body;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;

import com.akshara.support.TestApi.Session;
import com.jayway.jsonpath.JsonPath;

/**
 * Payslips as printed, "My payslips" (own, approved months only), and the files of an approved month: the bank
 * transfer file and its covering letter, the EPFO ECR, the ESI file and the professional tax and TDS summaries. Every
 * file is audited without amounts or names.
 */
class PayslipsAndFilesIT extends PayrollIntegrationTest {

    static final byte[] BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};

    static byte[] bytes(ResultActions result) throws Exception {
        return result.andReturn().getResponse().getContentAsByteArray();
    }

    static String text(ResultActions result) throws Exception {
        byte[] b = bytes(result);
        int skip = startsWithBom(b) ? 3 : 0;
        return new String(b, skip, b.length - skip, StandardCharsets.UTF_8);
    }

    static boolean startsWithBom(byte[] b) {
        return b.length >= 3 && Arrays.equals(Arrays.copyOf(b, 3), BOM);
    }

    @Test
    void payslipsPrintWithMaskedDetailsAndStaffSeeOnlyTheirOwnApprovedOnes() throws Exception {
        PayrollSchool s = new PayrollSchool(api).build();
        String run = s.lastMonthRun();
        String days = String.valueOf((double) s.lastMonth.lengthOfMonth());
        String all = body(api.get("/api/payroll/runs/" + run + "/payslips", s.accountant.accessToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].person.name", contains("Meena Reddy", "Ravi Kumar", "Sita Devi"))));
        List<String> ravis = JsonPath.read(all, "$[?(@.person.name == 'Ravi Kumar')].id");
        List<String> meenas = JsonPath.read(all, "$[?(@.person.name == 'Meena Reddy')].id");
        String raviSlip = ravis.getFirst();
        String meenaSlip = meenas.getFirst();

        // Staff see nothing of a draft month.
        api.get("/api/me/payslips", s.raviSession.accessToken())
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
        api.get("/api/me/payslips/" + raviSlip, s.raviSession.accessToken()).andExpect(status().isNotFound());

        ResultActions payslip = api.get("/api/payroll/payslips/" + raviSlip, s.accountant.accessToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.month").value(s.lastMonth.toString()))
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.runId").value(run))
                .andExpect(jsonPath("$.school.name").value("Test School " + s.school.code()))
                .andExpect(jsonPath("$.school.tan").value("HYDA12345B"))
                .andExpect(jsonPath("$.school.pfEstablishmentCode").value("TSHYD0012345000"))
                .andExpect(jsonPath("$.person.name").value("Ravi Kumar"))
                .andExpect(jsonPath("$.person.employeeCode").value("T-1"))
                .andExpect(jsonPath("$.person.designation").value("Teacher"))
                .andExpect(jsonPath("$.person.departmentName").value("Primary"))
                .andExpect(jsonPath("$.person.panMasked").value("XXXXXX234F"))
                .andExpect(jsonPath("$.person.bankLast4").value("3456"))
                .andExpect(jsonPath("$.person.uan").value("100000000001"))
                .andExpect(jsonPath("$.prorationBasis").value("CALENDAR_DAYS"))
                .andExpect(jsonPath("$.basisDays").value(Double.valueOf(days)))
                .andExpect(jsonPath("$.payableDays").value(Double.valueOf(days)))
                .andExpect(jsonPath("$.lopDays").value(0.0))
                .andExpect(jsonPath("$.earnings[*].code", contains("BASIC", "HRA", "SPECIAL")))
                .andExpect(jsonPath("$.earnings[0].fullPaise").value(30_000_00))
                .andExpect(jsonPath("$.earnings[0].amountPaise").value(30_000_00))
                .andExpect(jsonPath("$.deductions[*].code", contains("PF", "PT", "LOAN")))
                .andExpect(jsonPath("$.deductions[*].amountPaise", contains(1_800_00, 200_00, 1_000_00)))
                .andExpect(jsonPath("$.employer[*].code", contains("EPF_ER", "EPS", "EDLI", "PF_ADMIN")))
                .andExpect(jsonPath("$.employer[*].amountPaise", contains(550_00, 1_250_00, 75_00, 75_00)))
                .andExpect(jsonPath("$.grossPaise").value(50_000_00))
                .andExpect(jsonPath("$.deductionsPaise").value(3_000_00))
                .andExpect(jsonPath("$.netPaise").value(47_000_00))
                .andExpect(jsonPath("$.employerPaise").value(1_950_00))
                .andExpect(jsonPath("$.netInWords").value("Rupees Forty Seven Thousand Only"));
        assertThat(body(payslip)).doesNotContain(RAVI_ACCOUNT).doesNotContain(RAVI_PAN);
        api.get("/api/payroll/payslips/" + UUID.randomUUID(), s.accountant.accessToken())
                .andExpect(status().isNotFound());
        // Payslips of everyone are for payroll readers; a teacher has only "My payslips".
        api.get("/api/payroll/payslips/" + raviSlip, s.raviSession.accessToken()).andExpect(status().isForbidden());
        api.get("/api/payroll/runs/" + run + "/payslips", s.raviSession.accessToken())
                .andExpect(status().isForbidden());

        s.approve(run);
        api.get("/api/me/payslips", s.raviSession.accessToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(raviSlip))
                .andExpect(jsonPath("$[0].month").value(s.lastMonth.toString()))
                .andExpect(jsonPath("$[0].status").value("APPROVED"))
                .andExpect(jsonPath("$[0].netPaise").value(47_000_00));
        String mine = body(api.get("/api/me/payslips/" + raviSlip, s.raviSession.accessToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.person.name").value("Ravi Kumar"))
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.netInWords").value("Rupees Forty Seven Thousand Only")));
        assertThat(mine).doesNotContain(RAVI_ACCOUNT).doesNotContain(RAVI_PAN);
        // Someone else's payslip, or an unknown one, is simply not there.
        api.get("/api/me/payslips/" + meenaSlip, s.raviSession.accessToken()).andExpect(status().isNotFound());
        api.get("/api/me/payslips/" + UUID.randomUUID(), s.raviSession.accessToken())
                .andExpect(status().isNotFound());
        // Staff without payslips have an empty list; parents have no payslips at all.
        api.get("/api/me/payslips", s.admin.accessToken()).andExpect(jsonPath("$.length()").value(0));
        api.createUser(s.admin, "Lata Rao", s.email("lata"), List.of("PARENT"));
        Session parent = s.login("lata");
        api.get("/api/me/payslips", parent.accessToken()).andExpect(status().isForbidden());
        api.get("/api/me/payslips/" + raviSlip, parent.accessToken()).andExpect(status().isForbidden());
        api.get("/api/me/payslips", null).andExpect(status().isUnauthorized());
    }

    @Test
    void filesAreMadeFromAnApprovedMonthForPayrollManagersAndAudited() throws Exception {
        PayrollSchool s = new PayrollSchool(api).build();
        s.leaver();
        s.principalCanReadPayroll();
        String run = s.lastMonthRun();
        String base = "/api/payroll/runs/" + run;
        String token = s.accountant.accessToken();
        List<String> files = List.of("/bank-file.csv", "/bank-advice", "/ecr.txt", "/esi.csv",
                "/professional-tax.csv", "/tds.csv");
        for (String file : files) {
            api.get(base + file, token).andExpect(status().isConflict())
                    .andExpect(jsonPath("$.title").value("Not approved"));
        }
        // The summaries can be reviewed while the month is a draft.
        api.get(base + "/professional-tax", s.principal.accessToken()).andExpect(status().isOk());
        api.get(base + "/tds", s.principal.accessToken()).andExpect(status().isOk());
        s.approve(run);
        for (String file : files) {
            api.get(base + file, s.principal.accessToken()).andExpect(status().isForbidden());
            api.get(base + file, s.raviSession.accessToken()).andExpect(status().isForbidden());
        }
        api.get("/api/payroll/runs/" + UUID.randomUUID() + "/bank-file.csv", token)
                .andExpect(status().isNotFound());
        long days = s.lastMonth.lengthOfMonth();
        String narration = "Salary " + DateTimeFormatter.ofPattern("MMM yyyy", Locale.ENGLISH).format(s.lastMonth);

        // The bank file: full account numbers, plain UTF-8 for the bank's upload screen.
        ResultActions bank = api.get(base + "/bank-file.csv", token)
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/csv"))
                .andExpect(header().string("Content-Disposition",
                        "attachment; filename=\"bank-transfer-" + s.lastMonth + ".csv\""))
                .andExpect(header().string("Cache-Control", "no-store"));
        assertThat(startsWithBom(bytes(bank))).isFalse();
        List<String> lines = text(bank).lines().toList();
        assertThat(lines.getFirst()).isEqualTo("Beneficiary name,Account number,IFSC,Amount,Narration");
        assertThat(lines).hasSize(5).contains("Ravi Kumar," + RAVI_ACCOUNT + ",AKSH0000001,47000.00," + narration,
                "Meena Reddy,000000222333,AKSH0000001,34000.00," + narration);

        // The covering letter: totals in words, accounts masked.
        String advice = body(api.get(base + "/bank-advice", token)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(4))
                .andExpect(jsonPath("$.bankName").value("Akshara Test Bank"))
                .andExpect(jsonPath("$.debitAccountNumber").value("000000900001"))
                .andExpect(jsonPath("$.rows[?(@.name == 'Ravi Kumar')].accountMasked").value("XXXXXXXX3456"))
                .andExpect(jsonPath("$.rows[?(@.name == 'Ravi Kumar')].amountPaise").value(47_000_00))
                .andExpect(jsonPath("$.totalInWords", startsWith("Rupees ")))
                .andExpect(jsonPath("$.withoutBank.length()").value(0)));
        assertThat(advice).doesNotContain(RAVI_ACCOUNT);
        long total = ((Number) JsonPath.read(advice, "$.totalPaise")).longValue();
        long net = ((Number) JsonPath.read(body(api.get(base, token)), "$.totals.netPaise")).longValue();
        assertThat(total).isEqualTo(net);

        // ECR 2.0: PF members with a UAN (Meena has none), whole rupees, #~# between the eleven fields.
        ResultActions ecr = api.get(base + "/ecr.txt", token)
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition",
                        "attachment; filename=\"ecr-" + s.lastMonth + ".txt\""));
        List<String> members = text(ecr).lines().toList();
        assertThat(members).hasSize(3);
        assertThat(members).contains(
                "100000000001#~#Ravi Kumar#~#50000#~#15000#~#15000#~#15000#~#1800#~#1250#~#550#~#0#~#0");
        assertThat(members).allSatisfy(l -> assertThat(l.split("#~#", -1)).hasSize(11));
        assertThat(members).noneMatch(l -> l.contains("Meena"));

        // ESI: those covered with an insurance number. Kiran left on the 10th: reason 2 and the last working day.
        ResultActions esi = api.get(base + "/esi.csv", token).andExpect(status().isOk());
        assertThat(startsWithBom(bytes(esi))).isTrue();
        List<String> esiLines = text(esi).lines().toList();
        assertThat(esiLines.getFirst()).startsWith("IP Number,IP Name,No of Days for which wages paid/payable");
        assertThat(esiLines).hasSize(3);
        long kiranGross = PayrollRunIT.prorate(8_000_00, 10, days) + PayrollRunIT.prorate(4_000_00, 10, days)
                + PayrollRunIT.prorate(3_000_00, 10, days);
        assertThat(esiLines).contains("1000000004,Kiran Rao,10," + new PayrollCsv.Money(kiranGross) + ",2,"
                + s.lastMonth.atDay(10).format(DateTimeFormatter.ofPattern("dd/MM/yyyy")));
        assertThat(esiLines.stream().filter(l -> l.startsWith("1000000003,Sita Devi," + (days - 15) + ",")))
                .singleElement().satisfies(l -> assertThat(l).endsWith(",0,"));

        // Professional tax: ₹200 each for Ravi and Meena, nothing below ₹15,000.
        api.get(base + "/professional-tax", s.principal.accessToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("TG"))
                .andExpect(jsonPath("$.totalPaise").value(400_00))
                .andExpect(jsonPath("$.slabs[?(@.amountPaise == 20000)].count").value(2))
                .andExpect(jsonPath("$.slabs[?(@.amountPaise == 0)].count").value(2))
                .andExpect(jsonPath("$.rows.length()").value(4));
        ResultActions pt = api.get(base + "/professional-tax.csv", token).andExpect(status().isOk());
        assertThat(startsWithBom(bytes(pt))).isTrue();
        assertThat(text(pt).lines().toList()).hasSize(6).last().isEqualTo(",Total,,400.00");

        // TDS with the PAN masked.
        api.get(base + "/tds", s.principal.accessToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.financialYear").value(PayrollMath.financialYear(s.lastMonth)))
                .andExpect(jsonPath("$.rows[?(@.name == 'Ravi Kumar')].panMasked").value("XXXXXX234F"))
                .andExpect(jsonPath("$.totalPaise").value(0));
        ResultActions tds = api.get(base + "/tds.csv", token).andExpect(status().isOk());
        assertThat(text(tds)).contains("XXXXXX234F").doesNotContain(RAVI_PAN);

        // Every file is audited: who, the month, which file and how many rows; never amounts or names.
        List<Map<String, Object>> exported = audit(s.admin, "payroll.exported");
        assertThat(exported).hasSize(6);
        assertThat(exported).allSatisfy(e -> {
            assertThat(e).containsEntry("actorName", "Meena Reddy").containsEntry("entityType", "payroll_run");
            assertThat(details(e)).containsOnlyKeys("month", "file", "rows")
                    .containsEntry("month", s.lastMonth.toString());
            assertThat(e.toString()).doesNotContain("Ravi").doesNotContain("47000").doesNotContain(RAVI_ACCOUNT);
        });
        Map<Object, Object> rows = new java.util.HashMap<>();
        exported.forEach(e -> rows.put(details(e).get("file"), details(e).get("rows")));
        assertThat(rows).containsEntry("BANK", 4).containsEntry("BANK_ADVICE", 4).containsEntry("ECR", 3)
                .containsEntry("ESI", 2).containsEntry("PT", 4).containsEntry("TDS", 4);
    }

    @Test
    void theBankFileUsesTheAccountHolderNameAndLeavesOutPeopleWithoutAnAccount() throws Exception {
        PayrollSchool s = new PayrollSchool(api).build();
        api.put("/api/payroll/staff/" + s.meena + "/pay-profile", s.accountant.accessToken(), """
                {"pan":"PQRST6789K","pfMember":true,"ptWomenSlab":false}""")
                .andExpect(status().isOk());
        api.put("/api/payroll/staff/" + s.ravi + "/pay-profile", s.accountant.accessToken(), """
                {"bankAccountNumber":"%s","bankIfsc":"AKSH0000001","accountHolderName":"=R Kumar","pan":"%s",
                 "uan":"100000000001","pfMember":true,"ptWomenSlab":false}""".formatted(RAVI_ACCOUNT, RAVI_PAN))
                .andExpect(status().isOk());
        api.put("/api/payroll/staff/" + s.sita + "/pay-profile", s.accountant.accessToken(), """
                {"bankAccountNumber":"000000444555","bankIfsc":"AKSH0000001","accountHolderName":"Sita, Devi",
                 "uan":"100000000003","esiNumber":"1000000003","pfMember":true,"ptWomenSlab":false}""")
                .andExpect(status().isOk());
        String run = s.lastMonthRun();
        api.get("/api/payroll/runs/" + run, s.accountant.accessToken())
                .andExpect(jsonPath("$.warnings[?(@.code == 'NO_BANK')].name", contains("Meena Reddy")));
        s.approve(run);
        String csv = text(api.get("/api/payroll/runs/" + run + "/bank-file.csv", s.accountant.accessToken()));
        // A name with a comma is quoted and one that looks like a formula is defused; Meena has no account, so she
        // is not in the file but listed on the letter.
        assertThat(csv.lines().toList()).hasSize(3);
        assertThat(csv).contains("\"Sita, Devi\",000000444555", "'=R Kumar," + RAVI_ACCOUNT)
                .doesNotContain("Reddy");
        api.get("/api/payroll/runs/" + run + "/bank-advice", s.accountant.accessToken())
                .andExpect(jsonPath("$.count").value(2))
                .andExpect(jsonPath("$.withoutBank[*].name", contains("Meena Reddy")));
    }
}
