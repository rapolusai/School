package com.akshara.payroll;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;

import org.junit.jupiter.api.Test;

import com.akshara.support.TestApi;
import com.akshara.support.TestApi.Session;

/**
 * Another school's payroll is invisible: its runs, payslips, staff, structures, components and files are 404 in
 * paths and its ids are refused in request bodies; its staff never see this school's payslips.
 */
class PayrollIsolationIT extends PayrollIntegrationTest {

    @Test
    void anotherSchoolsPayrollIsInvisible() throws Exception {
        PayrollSchool a = new PayrollSchool(api).build();
        String run = a.lastMonthRun();
        String slip = (String) PayrollSchool.first(api.get("/api/payroll/runs/" + run, a.admin.accessToken()),
                "$.payslips[?(@.name == 'Ravi Kumar')].id");
        a.approve(run);
        String structure = TestApi.read(api.get("/api/payroll/staff/" + a.ravi, a.admin.accessToken()),
                "$.structures[0].id");

        PayrollSchool b = new PayrollSchool(api).build();
        Session admin = b.admin;
        String token = admin.accessToken();
        String bRun = b.run(b.lastMonth);

        // Nothing of school A shows in school B's lists.
        api.get("/api/payroll/runs", token).andExpect(jsonPath("$.runs.length()").value(1))
                .andExpect(jsonPath("$.runs[0].id").value(bRun));
        api.get("/api/payroll/staff", token).andExpect(jsonPath("$.staff[?(@.userId == '" + a.ravi + "')]")
                .isEmpty());
        assertThat(PayrollSchool.body(api.get("/api/payroll/components", token))).doesNotContain(
                a.components.get("BASIC"));

        // School A's ids in paths are 404.
        String[] gets = {"/api/payroll/runs/" + run, "/api/payroll/runs/" + run + "/payslips",
            "/api/payroll/payslips/" + slip, "/api/payroll/staff/" + a.ravi,
            "/api/payroll/staff/" + a.ravi + "/pay-profile/full", "/api/payroll/runs/" + run + "/bank-file.csv",
            "/api/payroll/runs/" + run + "/bank-advice", "/api/payroll/runs/" + run + "/ecr.txt",
            "/api/payroll/runs/" + run + "/esi.csv", "/api/payroll/runs/" + run + "/professional-tax",
            "/api/payroll/runs/" + run + "/professional-tax.csv", "/api/payroll/runs/" + run + "/tds",
            "/api/payroll/runs/" + run + "/tds.csv",
            "/api/payroll/reports/register?departmentId=" + a.primary};
        for (String path : gets) {
            api.get(path, token).andExpect(status().isNotFound());
        }
        api.post("/api/payroll/runs/" + run + "/recalculate", token, null).andExpect(status().isNotFound());
        api.post("/api/payroll/runs/" + run + "/approve", token, null).andExpect(status().isNotFound());
        api.post("/api/payroll/runs/" + run + "/paid", token, """
                {"paidOn":"%s"}""".formatted(b.today)).andExpect(status().isNotFound());
        api.delete("/api/payroll/runs/" + run, token).andExpect(status().isNotFound());
        api.post("/api/payroll/runs/" + run + "/adjustments", token, """
                {"userId":"%s","kind":"BONUS","amountPaise":100,"reason":"x"}""".formatted(a.ravi))
                .andExpect(status().isNotFound());
        api.put("/api/payroll/runs/" + run + "/payslips/" + slip + "/tds", token, """
                {"amountPaise":100,"reason":"x"}""").andExpect(status().isNotFound());
        api.put("/api/payroll/staff/" + a.ravi + "/pay-profile", token, """
                {"pfMember":true,"ptWomenSlab":false}""").andExpect(status().isNotFound());
        b.structureRequest(admin, a.ravi, b.thisMonth, Map.of("BASIC", 1_00L)).andExpect(status().isNotFound());
        api.delete("/api/payroll/structures/" + structure, token).andExpect(status().isNotFound());
        api.put("/api/payroll/components/" + a.components.get("BASIC"), token, """
                {"name":"Basic pay","pfWage":true,"esiWage":true,"taxable":true,"prorated":true,
                 "displayOrder":10,"active":true}""").andExpect(status().isNotFound());

        // School A's ids in bodies are refused as field errors.
        api.post("/api/payroll/runs/" + bRun + "/adjustments", token, """
                {"userId":"%s","kind":"BONUS","amountPaise":100,"reason":"x"}""".formatted(a.ravi))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.userId").exists());
        api.post("/api/payroll/staff/" + b.lakshmi + "/structures", token, """
                {"effectiveFrom":"%s","lines":[{"componentId":"%s","monthlyPaise":100}]}"""
                .formatted(b.thisMonth, a.components.get("BASIC")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors['lines[0].componentId']").exists());

        // School B's teacher cannot open school A's payslip, and school A's figures are untouched.
        api.get("/api/me/payslips/" + slip, b.raviSession.accessToken()).andExpect(status().isNotFound());
        api.get("/api/me/payslips", b.raviSession.accessToken()).andExpect(jsonPath("$.length()").value(0));
        api.get("/api/me/payslips/" + slip, a.raviSession.accessToken()).andExpect(status().isOk());
        api.get("/api/payroll/runs/" + run, a.admin.accessToken())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.payslips.length()").value(3));
    }
}
