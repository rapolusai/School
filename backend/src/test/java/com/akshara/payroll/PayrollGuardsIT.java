package com.akshara.payroll;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;

/**
 * Guards found by negative testing: professional tax slabs that overlap are refused, and a deduction or a TDS
 * override larger than the month's gross pay is refused instead of being stored and silently capped.
 */
class PayrollGuardsIT extends PayrollIntegrationTest {

    static final String RAVI = "$.payslips[?(@.name == 'Ravi Kumar')]";

    @Test
    void overlappingProfessionalTaxSlabsAreRefused() throws Exception {
        PayrollSchool s = new PayrollSchool(api).build();
        String token = s.accountant.accessToken();
        api.put("/api/payroll/settings/pt-slabs/KA", token, """
                {"effectiveFrom":"2025-04-01","slabs":[
                  {"appliesTo":"ALL","fromPaise":0,"toPaise":2000000,"amountPaise":0},
                  {"appliesTo":"ALL","fromPaise":1000000,"toPaise":null,"amountPaise":20000}]}""")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors['slabs[1].fromPaise']").exists());
        // Slabs for everyone conflict with slabs for women; the same upper and lower limit is an overlap too.
        api.put("/api/payroll/settings/pt-slabs/MH", token, """
                {"effectiveFrom":"2025-04-01","slabs":[
                  {"appliesTo":"WOMEN","fromPaise":0,"toPaise":2500000,"amountPaise":0},
                  {"appliesTo":"ALL","fromPaise":2500000,"toPaise":null,"amountPaise":20000}]}""")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors['slabs[1].fromPaise']").exists());
        api.put("/api/payroll/settings/pt-slabs/TN", token, """
                {"effectiveFrom":"2025-04-01","slabs":[
                  {"appliesTo":"ALL","fromPaise":0,"toPaise":500000,"amountPaise":0,"months":[3,9]},
                  {"appliesTo":"ALL","fromPaise":400000,"toPaise":null,"amountPaise":18000,"months":[9]}]}""")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors['slabs[1].fromPaise']").exists());
        // What the published tables do stays allowed: a February slab on top of the every-month one, and separate
        // slabs for women and for everyone else.
        api.put("/api/payroll/settings/pt-slabs/KA", token, """
                {"effectiveFrom":"2025-04-01","slabs":[
                  {"appliesTo":"ALL","fromPaise":0,"toPaise":2499999,"amountPaise":0},
                  {"appliesTo":"ALL","fromPaise":2500000,"toPaise":null,"amountPaise":20000},
                  {"appliesTo":"ALL","fromPaise":2500000,"toPaise":null,"amountPaise":30000,"months":[2]}]}""")
                .andExpect(status().isOk()).andExpect(jsonPath("$.slabs.length()").value(3));
        api.put("/api/payroll/settings/pt-slabs/MH", token, """
                {"effectiveFrom":"2025-04-01","slabs":[
                  {"appliesTo":"OTHERS","fromPaise":0,"toPaise":750000,"amountPaise":0},
                  {"appliesTo":"OTHERS","fromPaise":750001,"toPaise":null,"amountPaise":20000},
                  {"appliesTo":"WOMEN","fromPaise":0,"toPaise":2500000,"amountPaise":0},
                  {"appliesTo":"WOMEN","fromPaise":2500001,"toPaise":null,"amountPaise":20000}]}""")
                .andExpect(status().isOk()).andExpect(jsonPath("$.slabs.length()").value(4));
    }

    @Test
    void deductionsAndTdsAboveGrossPayAreRefused() throws Exception {
        PayrollSchool s = new PayrollSchool(api).build();
        String run = s.lastMonthRun();
        String token = s.accountant.accessToken();
        String add = "/api/payroll/runs/" + run + "/adjustments";
        long gross = ((Number) PayrollSchool.first(api.get("/api/payroll/runs/" + run, token), RAVI + ".grossPaise"))
                .longValue();

        api.post(add, token, """
                {"userId":"%s","kind":"DEDUCTION","amountPaise":10000000000,"reason":"Typo"}""".formatted(s.ravi))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.amountPaise").exists());
        api.post(add, token, """
                {"userId":"%s","kind":"DEDUCTION","amountPaise":30000,"reason":"Library book lost"}"""
                .formatted(s.ravi)).andExpect(status().isCreated());
        // Together with the first, this one would take more than the gross.
        api.post(add, token, """
                {"userId":"%s","kind":"DEDUCTION","amountPaise":%d,"reason":"Advance"}""".formatted(s.ravi,
                gross - 30000 + 1))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.amountPaise").exists());
        api.post(add, token, """
                {"userId":"%s","kind":"DEDUCTION","amountPaise":%d,"reason":"Advance"}""".formatted(s.ravi,
                gross - 30000))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.adjustments.length()").value(2));

        String slip = (String) PayrollSchool.first(api.get("/api/payroll/runs/" + run, token), RAVI + ".id");
        String tds = "/api/payroll/runs/" + run + "/payslips/" + slip + "/tds";
        api.put(tds, token, """
                {"amountPaise":10000000000,"reason":"Typo"}""")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.amountPaise").exists());
        api.put(tds, token, """
                {"amountPaise":%d,"reason":"Other income declared"}""".formatted(gross + 1))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.amountPaise").exists());
        api.put(tds, token, """
                {"amountPaise":150000,"reason":"Other income declared"}""")
                .andExpect(status().isOk()).andExpect(jsonPath(RAVI + ".tdsPaise").value(150000));
    }
}
