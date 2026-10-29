package com.akshara.billing;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import com.akshara.support.BillingFixtures;
import com.akshara.support.IntegrationTest;
import com.akshara.support.SchoolFixtures;
import com.akshara.support.TestApi;
import com.akshara.support.TestApi.School;
import com.akshara.support.TestApi.Session;

/**
 * Negative sweep of 2026-10-29 (docs/testing/negative-sweep-2026-10-29.md): once the live gateways are switched from
 * the sandbox to Razorpay, a sandbox order left open from before (a subscription invoice or a school fee) can no longer
 * be "paid" through the sandbox checkout, which would record a payment no money stands behind. No request reaches
 * Razorpay: the keys are made up and nothing here creates an order there.
 */
@TestPropertySource(properties = {
        "akshara.payments.gateway=razorpay",
        "akshara.payments.razorpay.key-id=rzp_test_fake",
        "akshara.payments.razorpay.key-secret=fake-secret",
        "akshara.payments.razorpay.webhook-secret=fake-webhook-secret",
        "akshara.payments.razorpay.base-url=http://127.0.0.1:9",
        "akshara.billing.payments.gateway=razorpay",
        "akshara.billing.payments.razorpay.key-id=rzp_test_fake_billing",
        "akshara.billing.payments.razorpay.key-secret=fake-billing-secret",
        "akshara.billing.payments.razorpay.webhook-secret=fake-billing-webhook-secret",
        "akshara.billing.payments.razorpay.base-url=http://127.0.0.1:9"})
class SandboxSwitchedOffIT extends IntegrationTest {

    @Test
    void aSandboxOrderLeftOpenCannotBePaidAfterTheSwitchToRazorpay() throws Exception {
        BillingFixtures billing = new BillingFixtures(api);
        Session root = billing.root();
        School school = api.signup();
        Session admin = api.login(school);
        String invoiceId = billing.paying(root, school.tenantId(), "36");
        String adminId = TestApi.read(api.get("/api/me", admin.accessToken()), "$.id");

        // A subscription order made through the sandbox before the switch.
        String invoiceOrder = "order_SBXleftopen" + UUID.randomUUID().toString().substring(0, 8);
        try (Connection c = ownerConnection(); PreparedStatement ps = c.prepareStatement("""
                insert into billing.payment_order (id, tenant_id, invoice_id, gateway, gateway_order_id, amount_paise,
                    status, created_by, created_by_name, created_at, updated_at)
                values (?, ?, ?, 'sandbox', ?, 100, 'CREATED', ?, 'Asha Admin', ?, ?)""")) {
            ps.setObject(1, UUID.randomUUID());
            ps.setObject(2, school.tenantId());
            ps.setObject(3, UUID.fromString(invoiceId));
            ps.setString(4, invoiceOrder);
            ps.setObject(5, UUID.fromString(adminId));
            ps.setTimestamp(6, Timestamp.from(Instant.now()));
            ps.setTimestamp(7, Timestamp.from(Instant.now()));
            ps.executeUpdate();
        }
        api.get("/api/billing/sandbox/orders/" + invoiceOrder, admin.accessToken()).andExpect(status().isNotFound());
        api.post("/api/billing/sandbox/orders/" + invoiceOrder + "/complete", admin.accessToken(), """
                {"outcome":"SUCCESS"}""").andExpect(status().isNotFound());

        // A parent's fee order made through the sandbox before the switch.
        SchoolFixtures fixtures = new SchoolFixtures(api);
        fixtures.currentYear(admin);
        String section = fixtures.section(admin, fixtures.schoolClass(admin, "Class 5"), "A", 40);
        String student = fixtures.student(admin, section, "SW/1", "Asha", null, "Lata", "9876501001");
        String feeOrder = "order_SBXfeeleft" + UUID.randomUUID().toString().substring(0, 8);
        try (Connection c = ownerConnection(); PreparedStatement ps = c.prepareStatement("""
                insert into fees.payment_order (id, tenant_id, student_id, gateway, gateway_order_id, amount_paise,
                    instalment_ids, late_fee_as_of, status, created_by, created_by_name, created_at, updated_at)
                values (?, ?, ?, 'sandbox', ?, 100, '{}', ?, 'CREATED', ?, 'Asha Admin', ?, ?)""")) {
            ps.setObject(1, UUID.randomUUID());
            ps.setObject(2, school.tenantId());
            ps.setObject(3, UUID.fromString(student));
            ps.setString(4, feeOrder);
            ps.setObject(5, java.sql.Date.valueOf(LocalDate.now()));
            ps.setObject(6, UUID.fromString(adminId));
            ps.setTimestamp(7, Timestamp.from(Instant.now()));
            ps.setTimestamp(8, Timestamp.from(Instant.now()));
            ps.executeUpdate();
        }
        api.get("/api/payments/sandbox/orders/" + feeOrder, admin.accessToken()).andExpect(status().isNotFound());
        api.post("/api/payments/sandbox/orders/" + feeOrder + "/complete", admin.accessToken(), """
                {"outcome":"SUCCESS"}""").andExpect(status().isNotFound());

        // The invoice is still due.
        api.get("/api/billing/invoices/" + invoiceId, admin.accessToken())
                .andExpect(jsonPath("$.status").value("ISSUED"));
    }
}
