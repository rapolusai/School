package com.akshara.payroll;

import static com.akshara.payroll.PayrollSchool.RAVI_ACCOUNT;
import static com.akshara.payroll.PayrollSchool.RAVI_PAN;
import static com.akshara.payroll.PayrollSchool.body;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasItems;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;

import com.akshara.support.TestApi;
import com.akshara.support.TestApi.Session;
import com.jayway.jsonpath.JsonPath;

/**
 * Payroll set-up: settings with the statutory figures, a state's professional tax slabs (published and the school's
 * own), salary components, structures with their revisions, and pay details with masking and the audited full view.
 */
class PayrollSetupIT extends PayrollIntegrationTest {

    static final String SETTINGS = """
            {"payDay":%d,"prorationBasis":"%s","pfEnabled":true,"pfOnFullWages":false,"esiEnabled":true,
             "ptEnabled":%s,"ptState":%s,"tan":%s,"bankAccountNumber":%s,"bankIfsc":%s}""";

    static String settings(int payDay, String basis, boolean pt, String state, String tan, String account,
            String ifsc) {
        return SETTINGS.formatted(payDay, basis, pt, PayrollSchool.json(state), PayrollSchool.json(tan),
                PayrollSchool.json(account), PayrollSchool.json(ifsc));
    }

    @Test
    void settingsShowTheStatutoryFiguresAndOnlyPayrollManagersChangeThem() throws Exception {
        PayrollSchool s = new PayrollSchool(api);
        String financialYear = PayrollMath.financialYear(s.thisMonth);
        api.get("/api/payroll/settings", s.admin.accessToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.saved").value(false))
                .andExpect(jsonPath("$.payDay").value(1))
                .andExpect(jsonPath("$.prorationBasis").value("CALENDAR_DAYS"))
                .andExpect(jsonPath("$.pfEnabled").value(true))
                .andExpect(jsonPath("$.pfOnFullWages").value(false))
                .andExpect(jsonPath("$.statutory.month").value(s.thisMonth.toString()))
                .andExpect(jsonPath("$.statutory.rate.pfEmployeePercent").value(12.0))
                .andExpect(jsonPath("$.statutory.rate.pfEmployerPercent").value(12.0))
                .andExpect(jsonPath("$.statutory.rate.pfWageCeilingPaise").value(15_000_00))
                .andExpect(jsonPath("$.statutory.rate.epsPercent").value(8.33))
                .andExpect(jsonPath("$.statutory.rate.epsMaxPaise").value(1_250_00))
                .andExpect(jsonPath("$.statutory.rate.esiEmployeePercent").value(0.75))
                .andExpect(jsonPath("$.statutory.rate.esiEmployerPercent").value(3.25))
                .andExpect(jsonPath("$.statutory.rate.esiWageCeilingPaise").value(21_000_00))
                .andExpect(jsonPath("$.statutory.financialYear").value(financialYear))
                .andExpect(jsonPath("$.statutory.taxRule.financialYear").value("2025-26"))
                .andExpect(jsonPath("$.statutory.taxRulesForThisYear").value(financialYear.equals("2025-26")))
                .andExpect(jsonPath("$.statutory.taxRule.slabs.length()").value(7))
                .andExpect(jsonPath("$.statutory.taxRule.slabs[6].ratePercent").value(30.0))
                .andExpect(jsonPath("$.statutory.taxRule.standardDeductionPaise").value(75_000_00))
                .andExpect(jsonPath("$.statutory.taxRule.rebateIncomeLimitPaise").value(12_00_000_00))
                .andExpect(jsonPath("$.statutory.taxRule.rebateMaxPaise").value(60_000_00))
                .andExpect(jsonPath("$.statutory.taxRule.cessPercent").value(4.0))
                .andExpect(jsonPath("$.statutory.ptStates", hasItems("AP", "KA", "MH", "TG", "TN", "WB")));

        // Professional tax needs a state; a TAN has a shape; the school's account goes with its IFSC.
        String put = "/api/payroll/settings";
        api.put(put, s.admin.accessToken(), settings(1, "CALENDAR_DAYS", true, null, null, null, null))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.ptState").exists());
        api.put(put, s.admin.accessToken(), settings(1, "CALENDAR_DAYS", false, null, "HYD123", null, null))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.tan").exists());
        api.put(put, s.admin.accessToken(), settings(1, "CALENDAR_DAYS", false, null, null, "000000900001", null))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.bankIfsc").exists());
        api.put(put, s.admin.accessToken(), settings(32, "CALENDAR_DAYS", false, null, null, null, null))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.payDay").exists());

        s.build();
        api.get(put, s.principal.accessToken()).andExpect(status().isForbidden());
        api.put(put, s.principal.accessToken(), settings(1, "CALENDAR_DAYS", true, "TG", null, null, null))
                .andExpect(status().isForbidden());
        api.get(put, s.raviSession.accessToken()).andExpect(status().isForbidden());
        api.get(put, s.accountant.accessToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.saved").value(true))
                .andExpect(jsonPath("$.ptState").value("TG"))
                .andExpect(jsonPath("$.tan").value("HYDA12345B"))
                .andExpect(jsonPath("$.statutory.ptSlabs.state").value("TG"))
                .andExpect(jsonPath("$.statutory.ptSlabs.custom").value(false))
                .andExpect(jsonPath("$.statutory.ptSlabs.slabs.length()").value(3));
        // The accountant manages payroll: lower-case codes are stored in capitals.
        api.put(put, s.accountant.accessToken(), settings(7, "WORKING_DAYS", true, "ka", "hyda12345b", null, null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.payDay").value(7))
                .andExpect(jsonPath("$.prorationBasis").value("WORKING_DAYS"))
                .andExpect(jsonPath("$.ptState").value("KA"))
                .andExpect(jsonPath("$.tan").value("HYDA12345B"))
                .andExpect(jsonPath("$.bankAccountNumber").doesNotExist());

        // The audit names what changed, never the values.
        List<Map<String, Object>> events = audit(s.admin, "payroll_settings.updated");
        assertThat(events).hasSize(2);
        @SuppressWarnings("unchecked")
        List<String> changed = (List<String>) details(events.getFirst()).get("changed");
        assertThat(changed).contains("payDay", "prorationBasis", "ptState", "bankAccountNumber")
                .doesNotContain("000000900001");
        assertThat(events.getFirst().toString()).doesNotContain("000000900001");
    }

    @Test
    void professionalTaxSlabsArePublishedPerStateAndASchoolCanUseItsOwn() throws Exception {
        PayrollSchool s = new PayrollSchool(api).build();
        String body = body(api.get("/api/payroll/settings/pt-slabs/mh", s.accountant.accessToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("MH"))
                .andExpect(jsonPath("$.custom").value(false))
                .andExpect(jsonPath("$.slabs.length()").value(7)));
        List<String> women = JsonPath.read(body, "$.slabs[?(@.appliesTo == 'WOMEN')].appliesTo");
        assertThat(women).hasSize(3);
        List<List<Integer>> months = JsonPath.read(body, "$.slabs[*].months");
        // Maharashtra's February rows: ₹300 instead of ₹200, for men and for women.
        assertThat(months.stream().filter(m -> m.equals(List.of(2))).count()).isEqualTo(2);
        api.get("/api/payroll/settings/pt-slabs/TN", s.accountant.accessToken())
                .andExpect(jsonPath("$.slabs[0].months", containsInAnyOrder(3, 9)));
        api.get("/api/payroll/settings/pt-slabs/ZZ", s.accountant.accessToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.slabs.length()").value(0));
        api.get("/api/payroll/settings/pt-slabs/T1", s.accountant.accessToken())
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.state").exists());

        // Last month's run with the published Telangana slabs: ₹200 on Ravi's ₹50,000.
        String run = s.lastMonthRun();
        api.get("/api/payroll/runs/" + run, s.admin.accessToken())
                .andExpect(jsonPath("$.payslips[?(@.name == 'Ravi Kumar')].ptPaise").value(200_00));

        // The school's own slabs replace the published ones for the state.
        String own = """
                {"effectiveFrom":"2025-04-01","slabs":[
                  {"appliesTo":"ALL","fromPaise":0,"toPaise":1500000,"amountPaise":0},
                  {"appliesTo":"ALL","fromPaise":1500001,"toPaise":null,"amountPaise":25000,"months":[]}]}""";
        api.put("/api/payroll/settings/pt-slabs/TG", s.principal.accessToken(), own)
                .andExpect(status().isForbidden());
        api.put("/api/payroll/settings/pt-slabs/TG", s.accountant.accessToken(), own)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.custom").value(true))
                .andExpect(jsonPath("$.effectiveFrom").value("2025-04-01"))
                .andExpect(jsonPath("$.slabs.length()").value(2));
        api.get("/api/payroll/settings", s.accountant.accessToken())
                .andExpect(jsonPath("$.statutory.ptSlabs.custom").value(true));
        api.post("/api/payroll/runs/" + run + "/recalculate", s.accountant.accessToken(), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.payslips[?(@.name == 'Ravi Kumar')].ptPaise").value(250_00));

        api.put("/api/payroll/settings/pt-slabs/TG", s.accountant.accessToken(), """
                {"effectiveFrom":"2025-04-01","slabs":[{"appliesTo":"ALL","fromPaise":500,"toPaise":100,
                 "amountPaise":0}]}""")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors['slabs[0].toPaise']").exists());
        api.put("/api/payroll/settings/pt-slabs/TG", s.accountant.accessToken(), """
                {"effectiveFrom":"2025-04-01","slabs":[]}""")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.slabs").exists());
        api.put("/api/payroll/settings/pt-slabs/TG", s.accountant.accessToken(), """
                {"effectiveFrom":"2025-04-01","slabs":[{"appliesTo":"ALL","fromPaise":0,"amountPaise":0,
                 "months":[13]}]}""")
                .andExpect(status().isBadRequest());

        // Back to the published slabs.
        api.delete("/api/payroll/settings/pt-slabs/TG", s.principal.accessToken()).andExpect(status().isForbidden());
        api.delete("/api/payroll/settings/pt-slabs/TG", s.accountant.accessToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.custom").value(false))
                .andExpect(jsonPath("$.slabs.length()").value(3));
        api.post("/api/payroll/runs/" + run + "/recalculate", s.accountant.accessToken(), null)
                .andExpect(jsonPath("$.payslips[?(@.name == 'Ravi Kumar')].ptPaise").value(200_00));
        assertThat(audit(s.admin, "payroll_pt_slabs.updated")).hasSize(1);
        assertThat(audit(s.admin, "payroll_pt_slabs.reset")).hasSize(1);
    }

    @Test
    void componentsAreAddedOnceAndCustomComponentsHaveUniqueCodesAndNames() throws Exception {
        PayrollSchool s = new PayrollSchool(api).build();
        String token = s.accountant.accessToken();
        api.get("/api/payroll/components", token)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(7))
                .andExpect(jsonPath("$[?(@.code == 'BASIC')].pfWage").value(true))
                .andExpect(jsonPath("$[?(@.code == 'BASIC')].inUse").value(true))
                .andExpect(jsonPath("$[?(@.code == 'DA')].pfWage").value(true))
                .andExpect(jsonPath("$[?(@.code == 'DA')].inUse").value(false))
                .andExpect(jsonPath("$[?(@.code == 'LOAN')].kind").value("DEDUCTION"))
                .andExpect(jsonPath("$[?(@.code == 'LOAN')].prorated").value(false));
        // Adding the standard set again adds nothing.
        api.post("/api/payroll/components/standard", token, null)
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(7));

        String transport = """
                {"code":"%s","kind":"EARNING","fields":{"name":"%s","pfWage":false,"esiWage":true,"taxable":true,
                 "prorated":true,"displayOrder":60,"active":true}}""";
        String id = TestApi.read(api.post("/api/payroll/components", token,
                transport.formatted("transport", "Transport allowance"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("TRANSPORT"))
                .andExpect(jsonPath("$.inUse").value(false)), "$.id");
        api.post("/api/payroll/components", token, transport.formatted("TRANSPORT", "Another name"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.errors.code").exists());
        api.post("/api/payroll/components", token, transport.formatted("TRAVEL", "TRANSPORT ALLOWANCE"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.errors.name").exists());
        api.post("/api/payroll/components", token, transport.formatted("1X", "Odd"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.code").exists());
        api.post("/api/payroll/components", s.principal.accessToken(), transport.formatted("X", "X"))
                .andExpect(status().isForbidden());

        // Switched off, it can no longer go into a structure.
        api.put("/api/payroll/components/" + id, token, """
                {"name":"Bus allowance","pfWage":false,"esiWage":true,"taxable":true,"prorated":true,
                 "displayOrder":60,"active":false}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Bus allowance"))
                .andExpect(jsonPath("$.code").value("TRANSPORT"))
                .andExpect(jsonPath("$.active").value(false));
        s.components.put("TRANSPORT", id);
        s.structureRequest(s.accountant, s.ravi, s.thisMonth, Map.of("TRANSPORT", 1_000_00L))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors['lines[0].componentId']").exists());
        api.put("/api/payroll/components/" + UUID.randomUUID(), token, """
                {"name":"Ghost","pfWage":false,"esiWage":true,"taxable":true,"prorated":true,
                 "displayOrder":60,"active":true}""").andExpect(status().isNotFound());
        assertThat(audit(s.admin, "salary_component.created")).hasSize(1);
        assertThat(audit(s.admin, "salary_component.updated")).hasSize(1);
        assertThat(audit(s.admin, "salary_component.standard_added")).hasSize(1);
    }

    @Test
    void structuresHaveRevisionsWithTheirMonthlyGrossAndCtc() throws Exception {
        PayrollSchool s = new PayrollSchool(api).build();
        String token = s.accountant.accessToken();
        // Ravi's ₹50,000: PF on the ₹15,000 ceiling, so the employer adds ₹1,800 PF, ₹75 EDLI and ₹75 charges.
        api.get("/api/payroll/staff", token)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.month").value(s.thisMonth.toString()))
                .andExpect(jsonPath("$.staff.length()").value(5))
                .andExpect(jsonPath("$.needStructure").value(2))
                .andExpect(jsonPath("$.needBankDetails").value(0))
                .andExpect(jsonPath("$.staff[?(@.name == 'Ravi Kumar')].grossPaise").value(50_000_00))
                .andExpect(jsonPath("$.staff[?(@.name == 'Ravi Kumar')].ctcPaise").value(51_950_00))
                .andExpect(jsonPath("$.staff[?(@.name == 'Ravi Kumar')].structureFrom")
                        .value(s.twoMonthsAgo.atDay(1).toString()))
                .andExpect(jsonPath("$.staff[?(@.name == 'Ravi Kumar')].departmentName").value("Primary"))
                // Sita's ₹18,000 is within ESI: 3.25% (₹585) on top of PF ₹1,080, EDLI ₹45 and charges ₹45.
                .andExpect(jsonPath("$.staff[?(@.name == 'Sita Devi')].ctcPaise").value(19_755_00))
                .andExpect(jsonPath("$.staff[?(@.name == 'Lakshmi Iyer')].grossPaise").value(0))
                .andExpect(jsonPath("$.staff[?(@.name == 'Lakshmi Iyer')].structureFrom").value((Object) null));

        // A revision from this month: the earlier structure stays as history.
        String revision = s.structure(s.ravi, s.thisMonth, Map.of("BASIC", 33_000_00L, "HRA", 13_200_00L,
                "SPECIAL", 8_800_00L));
        api.get("/api/payroll/staff/" + s.ravi, token)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Ravi Kumar"))
                .andExpect(jsonPath("$.employeeCode").value("T-1"))
                .andExpect(jsonPath("$.structures.length()").value(2))
                .andExpect(jsonPath("$.structures[0].id").value(revision))
                .andExpect(jsonPath("$.structures[0].effectiveFrom").value(s.thisMonth.toString()))
                .andExpect(jsonPath("$.structures[0].grossPaise").value(55_000_00))
                .andExpect(jsonPath("$.structures[0].current").value(true))
                .andExpect(jsonPath("$.structures[0].createdByName").value("Asha Admin"))
                .andExpect(jsonPath("$.structures[1].effectiveFrom").value(s.twoMonthsAgo.toString()))
                .andExpect(jsonPath("$.structures[1].grossPaise").value(50_000_00))
                .andExpect(jsonPath("$.structures[1].fixedDeductionsPaise").value(1_000_00))
                .andExpect(jsonPath("$.structures[1].current").value(false))
                .andExpect(jsonPath("$.structures[1].lines.length()").value(4));

        // One structure per month; at least one earning; each component once; the month as YYYY-MM.
        s.structureRequest(s.accountant, s.ravi, s.thisMonth, Map.of("BASIC", 1_00L))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.errors.effectiveFrom").exists());
        s.structureRequest(s.accountant, s.lakshmi, s.thisMonth, Map.of("LOAN", 1_00L))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.lines").exists());
        api.post("/api/payroll/staff/" + s.lakshmi + "/structures", token, """
                {"effectiveFrom":"%s","lines":[{"componentId":"%s","monthlyPaise":100},
                 {"componentId":"%s","monthlyPaise":200}]}""".formatted(s.thisMonth, s.components.get("BASIC"),
                s.components.get("BASIC")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors['lines[1].componentId']").exists());
        api.post("/api/payroll/staff/" + s.lakshmi + "/structures", token, """
                {"effectiveFrom":"2026-13","lines":[{"componentId":"%s","monthlyPaise":100}]}"""
                .formatted(s.components.get("BASIC")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.effectiveFrom").exists());
        api.post("/api/payroll/staff/" + s.lakshmi + "/structures", token, """
                {"effectiveFrom":"%s","lines":[{"componentId":"%s","monthlyPaise":0}]}"""
                .formatted(s.thisMonth, s.components.get("BASIC")))
                .andExpect(status().isBadRequest());
        api.post("/api/payroll/staff/" + s.lakshmi + "/structures", token, """
                {"effectiveFrom":"%s","lines":[{"componentId":"%s","monthlyPaise":100}]}"""
                .formatted(s.thisMonth, UUID.randomUUID()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors['lines[0].componentId']").exists());
        s.structureRequest(s.principal, s.lakshmi, s.thisMonth, Map.of("BASIC", 1_00L))
                .andExpect(status().isForbidden());

        // Only staff have salaries: a parent or an unknown id is 404.
        String parent = api.createUser(s.admin, "Lata Rao", s.email("lata"), List.of("PARENT"));
        s.structureRequest(s.accountant, parent, s.thisMonth, Map.of("BASIC", 1_00L))
                .andExpect(status().isNotFound());
        api.get("/api/payroll/staff/" + parent, token).andExpect(status().isNotFound());
        api.get("/api/payroll/staff/" + UUID.randomUUID(), token).andExpect(status().isNotFound());

        // A structure no approved month used can be deleted.
        api.delete("/api/payroll/structures/" + revision, s.principal.accessToken())
                .andExpect(status().isForbidden());
        api.delete("/api/payroll/structures/" + revision, token).andExpect(status().isNoContent());
        api.delete("/api/payroll/structures/" + revision, token).andExpect(status().isNotFound());
        api.get("/api/payroll/staff/" + s.ravi, token)
                .andExpect(jsonPath("$.structures.length()").value(1))
                .andExpect(jsonPath("$.structures[0].current").value(true));
        assertThat(audit(s.admin, "salary_structure.created")).hasSize(4);
        assertThat(details(audit(s.admin, "salary_structure.deleted").getFirst()))
                .containsEntry("userId", s.ravi).containsEntry("effectiveFrom", s.thisMonth.toString());
    }

    @Test
    void payDetailsAreMaskedAndTheFullViewIsForPayrollManagersAndAudited() throws Exception {
        PayrollSchool s = new PayrollSchool(api).build();
        s.principalCanReadPayroll();

        // Masked everywhere: the list, a person's page and the save response.
        for (Session who : List.of(s.accountant, s.principal, s.admin)) {
            ResultActions list = api.get("/api/payroll/staff", who.accessToken()).andExpect(status().isOk())
                    .andExpect(jsonPath("$.staff[?(@.name == 'Ravi Kumar')].bankLast4").value("3456"))
                    .andExpect(jsonPath("$.staff[?(@.name == 'Ravi Kumar')].panMasked").value("XXXXXX234F"))
                    .andExpect(jsonPath("$.staff[?(@.name == 'Ravi Kumar')].hasUan").value(true))
                    .andExpect(jsonPath("$.staff[?(@.name == 'Ravi Kumar')].hasEsiNumber").value(false));
            ResultActions person = api.get("/api/payroll/staff/" + s.ravi, who.accessToken())
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.payProfile.saved").value(true))
                    .andExpect(jsonPath("$.payProfile.bankAccountMasked").value("XXXXXXXX3456"))
                    .andExpect(jsonPath("$.payProfile.bankLast4").value("3456"))
                    .andExpect(jsonPath("$.payProfile.bankIfsc").value("AKSH0000001"))
                    .andExpect(jsonPath("$.payProfile.panMasked").value("XXXXXX234F"))
                    .andExpect(jsonPath("$.payProfile.uan").value("100000000001"));
            assertThat(body(list)).doesNotContain(RAVI_ACCOUNT).doesNotContain(RAVI_PAN);
            assertThat(body(person)).doesNotContain(RAVI_ACCOUNT).doesNotContain(RAVI_PAN);
        }

        // In full only for payroll managers, and every look is audited (the person, never the values).
        api.get("/api/payroll/staff/" + s.ravi + "/pay-profile/full", s.principal.accessToken())
                .andExpect(status().isForbidden());
        api.get("/api/payroll/staff/" + s.ravi + "/pay-profile/full", s.raviSession.accessToken())
                .andExpect(status().isForbidden());
        api.get("/api/payroll/staff/" + s.ravi + "/pay-profile/full", s.accountant.accessToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bankAccountNumber").value(RAVI_ACCOUNT))
                .andExpect(jsonPath("$.pan").value(RAVI_PAN))
                .andExpect(jsonPath("$.uan").value("100000000001"));
        List<Map<String, Object>> viewed = audit(s.admin, "payroll_pay_profile.viewed");
        assertThat(viewed).hasSize(1);
        assertThat(viewed.getFirst()).containsEntry("actorName", "Meena Reddy");
        assertThat(details(viewed.getFirst())).isEqualTo(Map.of("userId", s.ravi));
        assertThat(viewed.getFirst().toString()).doesNotContain(RAVI_ACCOUNT).doesNotContain(RAVI_PAN);

        // Saving: shapes are checked, codes are stored in capitals, the account goes with its IFSC.
        String put = "/api/payroll/staff/" + s.lakshmi + "/pay-profile";
        api.put(put, s.accountant.accessToken(), """
                {"bankAccountNumber":"000000777888","bankIfsc":null,"pfMember":true,"ptWomenSlab":true}""")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.bankIfsc").exists());
        api.put(put, s.accountant.accessToken(), """
                {"pan":"ABC123","pfMember":true,"ptWomenSlab":true}""")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.pan").exists());
        api.put(put, s.accountant.accessToken(), """
                {"uan":"12345","pfMember":true,"ptWomenSlab":true}""")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.uan").exists());
        api.put(put, s.accountant.accessToken(), """
                {"bankAccountNumber":"12AB","bankIfsc":"AKSH0000001","pfMember":true,"ptWomenSlab":true}""")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.bankAccountNumber").exists());
        api.put(put, s.principal.accessToken(), """
                {"pfMember":true,"ptWomenSlab":true}""").andExpect(status().isForbidden());
        String saved = body(api.put(put, s.accountant.accessToken(), """
                {"bankAccountNumber":"000000777888","bankIfsc":"aksh0000002","accountHolderName":"L Iyer",
                 "pan":"lmnop4321q","pfMember":false,"ptWomenSlab":true}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bankAccountMasked").value("XXXXXXXX7888"))
                .andExpect(jsonPath("$.bankIfsc").value("AKSH0000002"))
                .andExpect(jsonPath("$.accountHolderName").value("L Iyer"))
                .andExpect(jsonPath("$.panMasked").value("XXXXXX321Q"))
                .andExpect(jsonPath("$.pfMember").value(false))
                .andExpect(jsonPath("$.ptWomenSlab").value(true)));
        assertThat(saved).doesNotContain("000000777888").doesNotContain("LMNOP4321Q");
        Map<String, Object> updated = audit(s.admin, "payroll_pay_profile.updated").getFirst();
        assertThat(details(updated)).containsEntry("userId", s.lakshmi);
        assertThat(details(updated).get("changed").toString()).contains("bankAccount", "pan", "pfMember");
        assertThat(updated.toString()).doesNotContain("000000777888").doesNotContain("LMNOP4321Q");
        // There is no Aadhaar anywhere in payroll.
        assertThat(body(api.get("/api/payroll/staff/" + s.lakshmi + "/pay-profile/full",
                s.accountant.accessToken()))).doesNotContainIgnoringCase("aadhaar");
    }
}
