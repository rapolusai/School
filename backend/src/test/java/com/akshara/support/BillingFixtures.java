package com.akshara.support;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import com.akshara.billing.Gstin;
import com.akshara.support.TestApi.Session;

/** Drives the Super Admin's billing console the way its pages do. */
public class BillingFixtures {

    private final TestApi api;

    public BillingFixtures(TestApi api) {
        this.api = api;
    }

    public static String school(UUID tenantId) {
        return "/api/platform/billing/schools/" + tenantId;
    }

    /** A made-up GSTIN with a correct check character, registered in the given state. Test data only. */
    public static String gstin(String stateCode) {
        String first14 = stateCode + "ABCDE1234F1Z";
        return first14 + Gstin.checkCharacter(first14);
    }

    public Session root() throws Exception {
        return api.platformLogin(IntegrationTest.PLATFORM_EMAIL, IntegrationTest.PLATFORM_PASSWORD);
    }

    public void details(Session root, UUID tenantId, String stateCode, String gstin) throws Exception {
        api.put(school(tenantId) + "/details", root.accessToken(), """
                {"legalName":"Test School Trust","address":"1 Test Road","stateCode":"%s","gstin":%s}"""
                .formatted(stateCode, gstin == null ? "null" : "\"" + gstin + "\""))
                .andExpect(status().isOk());
    }

    /** Converts the school to a paid subscription and returns the id of its first invoice. */
    public String start(Session root, UUID tenantId, String plan, String cycle, int students, String periodStart)
            throws Exception {
        return TestApi.read(api.post(school(tenantId) + "/subscription", root.accessToken(), """
                {"plan":"%s","billingCycle":"%s","billedStudents":%d,"periodStart":%s}"""
                .formatted(plan, cycle, students, periodStart == null ? "null" : "\"" + periodStart + "\""))
                .andExpect(status().isCreated()), "$.invoices[0].id");
    }

    /** Billing details in the given state, then a paid subscription; returns the first invoice's id. */
    public String paying(Session root, UUID tenantId, String stateCode) throws Exception {
        details(root, tenantId, stateCode, null);
        return start(root, tenantId, "GROWTH", "YEARLY", 100, null);
    }

    public void pay(Session root, UUID tenantId, String invoiceId) throws Exception {
        api.post(school(tenantId) + "/invoices/" + invoiceId + "/payments", root.accessToken(), """
                {"mode":"BANK_TRANSFER","reference":"UTR-TEST-1","paidOn":"%s"}"""
                .formatted(FeeFixtures.today()))
                .andExpect(status().isOk());
    }
}
