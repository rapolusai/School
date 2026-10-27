package com.akshara.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import com.akshara.support.BillingFixtures;
import com.akshara.support.FeeFixtures;
import com.akshara.support.IntegrationTest;
import com.akshara.support.TestApi;
import com.akshara.support.TestApi.School;
import com.akshara.support.TestApi.Session;
import com.jayway.jsonpath.JsonPath;

/**
 * A School Admin pays an open subscription invoice online through Akshara's own (sandbox) gateway: the payment is
 * recorded once as an ONLINE payment, whether the browser's verification or the webhook comes first; replays,
 * repeated clicks and two tabs never charge twice; bad signatures, other schools and other roles get nothing.
 */
@RecordApplicationEvents
class OnlineInvoicePaymentIT extends IntegrationTest {

    /** application-test.yml: akshara.billing.payments.sandbox-secret. */
    static final String BILLING_SANDBOX_SECRET = "test-only-billing-sandbox-secret-not-for-real-use";

    /** 100 students on Growth, yearly, in the seller's state: ₹20,000 + 18% GST. */
    static final long TOTAL = 23_600_00;

    @Autowired
    ApplicationEvents events;

    private BillingFixtures billing;
    private Session root;
    private School school;
    private Session admin;
    private String invoiceId;

    @BeforeEach
    void payingSchool() throws Exception {
        billing = new BillingFixtures(api);
        root = billing.root();
        school = api.signup();
        admin = api.login(school);
        invoiceId = billing.paying(root, school.tenantId(), "36");
    }

    @Test
    void aSchoolAdminPaysAnInvoiceOnlineAndItIsRecordedOnce() throws Exception {
        String created = api.post(orders(invoiceId), admin.accessToken(), "{}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.gateway").value("sandbox"))
                .andExpect(jsonPath("$.gatewayOrderId", startsWith("order_SBX")))
                .andExpect(jsonPath("$.amountPaise").value(TOTAL))
                .andExpect(jsonPath("$.status").value("CREATED"))
                .andExpect(jsonPath("$.checkoutKey").isEmpty())
                .andReturn().getResponse().getContentAsString();
        String orderId = JsonPath.read(created, "$.id");
        String gatewayOrderId = JsonPath.read(created, "$.gatewayOrderId");
        // A second click reuses the open order.
        api.post(orders(invoiceId), admin.accessToken(), "{}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(orderId));
        assertThat(count("billing.payment_order")).isEqualTo(1);

        api.get("/api/billing/sandbox/orders/" + gatewayOrderId, admin.accessToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.amountPaise").value(TOTAL))
                .andExpect(jsonPath("$.invoiceId").value(invoiceId))
                .andExpect(jsonPath("$.schoolName").value("Test School " + school.code()));
        String paid = api.post("/api/billing/sandbox/orders/" + gatewayOrderId + "/complete", admin.accessToken(), """
                {"outcome":"SUCCESS"}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andReturn().getResponse().getContentAsString();
        String paymentId = JsonPath.read(paid, "$.gatewayPaymentId");
        String signature = JsonPath.read(paid, "$.signature");
        assertThat(signature).isEqualTo(sign(gatewayOrderId + "|" + paymentId));
        // The fee gateway's secret is not Akshara's: a fee-sandbox signature is refused.
        assertThat(FeeFixtures.sign(gatewayOrderId + "|" + paymentId)).isNotEqualTo(signature);

        // A tampered signature records nothing.
        verify(orderId, paymentId, "0".repeat(64))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.signature").exists());
        verify(orderId, paymentId, FeeFixtures.sign(gatewayOrderId + "|" + paymentId))
                .andExpect(status().isBadRequest());
        assertThat(count("billing.invoice_payment")).isZero();

        verify(orderId, paymentId, signature)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAID"))
                .andExpect(jsonPath("$.balancePaise").value(0))
                .andExpect(jsonPath("$.payments.length()").value(1))
                .andExpect(jsonPath("$.payments[0].mode").value("ONLINE"))
                .andExpect(jsonPath("$.payments[0].reference").value(paymentId))
                .andExpect(jsonPath("$.payments[0].recordedByName").value("Online payment"));
        // A page reload verifies again: same result, nothing new.
        verify(orderId, paymentId, signature).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PAID"));
        // The webhook for the same payment arrives later, twice.
        String payload = webhookJson("evt_" + UUID.randomUUID(), "payment.captured", gatewayOrderId, paymentId, TOTAL);
        webhook(payload, sign(payload)).andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("ALREADY_RECORDED"));
        webhook(payload, sign(payload)).andExpect(status().isOk()).andExpect(jsonPath("$.outcome").value("DUPLICATE"));
        assertThat(count("billing.invoice_payment")).isEqualTo(1);
        assertThat(events.stream(SubscriptionInvoicePaid.class)).hasSize(1);

        // Nothing is left to pay.
        api.post(orders(invoiceId), admin.accessToken(), "{}").andExpect(status().isConflict());
        api.get("/api/billing/orders/" + orderId, admin.accessToken())
                .andExpect(jsonPath("$.status").value("PAID"));
        // The Super Admin sees the online payment on the school's account.
        api.get(BillingFixtures.school(school.tenantId()) + "/invoices/" + invoiceId, root.accessToken())
                .andExpect(jsonPath("$.status").value("PAID"))
                .andExpect(jsonPath("$.payments[0].mode").value("ONLINE"));
        api.get("/api/audit-events?limit=30", admin.accessToken())
                .andExpect(jsonPath("$[*].action", hasItem("invoice_order.created")))
                .andExpect(jsonPath("$[?(@.action == 'invoice.payment_recorded')].details.mode").value(hasItem("ONLINE")));
    }

    @Test
    void theWebhookCanRecordThePaymentFirstAndFailuresNeedANewOrder() throws Exception {
        String created = api.post(orders(invoiceId), admin.accessToken(), "{}")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String orderId = JsonPath.read(created, "$.id");
        String gatewayOrderId = JsonPath.read(created, "$.gatewayOrderId");

        // The payer cancels: a signed payment.failed webhook marks the order failed; the next click is a new order.
        api.post("/api/billing/sandbox/orders/" + gatewayOrderId + "/complete", admin.accessToken(), """
                {"outcome":"FAILURE"}""").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("FAILED"));
        api.get("/api/billing/orders/" + orderId, admin.accessToken()).andExpect(jsonPath("$.status").value("FAILED"));
        String again = api.post(orders(invoiceId), admin.accessToken(), "{}")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String secondId = JsonPath.read(again, "$.id");
        String secondGateway = JsonPath.read(again, "$.gatewayOrderId");
        assertThat(secondId).isNotEqualTo(orderId);

        String paymentId = "pay_SBXwebhookfirst01";
        String payload = webhookJson("evt_1_" + secondGateway, "payment.captured", secondGateway, paymentId, TOTAL);
        // Unsigned, mis-signed or tampered webhooks are refused and change nothing.
        webhook(payload, null).andExpect(status().isBadRequest());
        webhook(payload, sign(payload + " ")).andExpect(status().isBadRequest());
        webhook(payload.replace(String.valueOf(TOTAL), "1"), sign(payload)).andExpect(status().isBadRequest());
        // A fee-gateway signature is not Akshara's.
        webhook(payload, FeeFixtures.sign(payload)).andExpect(status().isBadRequest());
        assertThat(count("billing.invoice_payment")).isZero();

        webhook(payload, sign(payload)).andExpect(status().isOk()).andExpect(jsonPath("$.outcome").value("RECORDED"));
        api.get("/api/billing/invoices/" + invoiceId, admin.accessToken())
                .andExpect(jsonPath("$.status").value("PAID"))
                .andExpect(jsonPath("$.payments[0].reference").value(paymentId));
        // The browser's verification arrives afterwards: the same payment, nothing new.
        verify(secondId, paymentId, sign(secondGateway + "|" + paymentId))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PAID"));
        // Another payment on the paid order is refused.
        verify(secondId, "pay_SBXother", sign(secondGateway + "|pay_SBXother")).andExpect(status().isConflict());
        assertThat(count("billing.invoice_payment")).isEqualTo(1);
        assertThat(events.stream(SubscriptionInvoicePaid.class)).hasSize(1);
        // An event for an order nobody knows is ignored.
        String unknown = webhookJson("evt_unknown", "payment.captured", "order_SBXnobody", "pay_x", 100);
        webhook(unknown, sign(unknown)).andExpect(jsonPath("$.outcome").value("IGNORED"));
    }

    @Test
    void twoTabsClickingPayAtOnceShareOneOrder() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<Callable<String>> clicks = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                clicks.add(() -> JsonPath.read(api.post(orders(invoiceId), admin.accessToken(), "{}")
                        .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id"));
            }
            List<String> ids = new ArrayList<>();
            for (Future<String> f : pool.invokeAll(clicks)) {
                ids.add(f.get());
            }
            assertThat(ids).containsOnly(ids.getFirst());
        } finally {
            pool.shutdownNow();
        }
        assertThat(count("billing.payment_order")).isEqualTo(1);
    }

    @Test
    void otherSchoolsAndOtherRolesCannotPayOrSeeTheOrder() throws Exception {
        String orderId = TestApi.read(api.post(orders(invoiceId), admin.accessToken(), "{}")
                .andExpect(status().isCreated()), "$.id");
        String gatewayOrderId = TestApi.read(api.get("/api/billing/orders/" + orderId, admin.accessToken()),
                "$.gatewayOrderId");

        School other = api.signup();
        Session otherAdmin = api.login(other);
        api.post(orders(invoiceId), otherAdmin.accessToken(), "{}").andExpect(status().isNotFound());
        api.get("/api/billing/orders/" + orderId, otherAdmin.accessToken()).andExpect(status().isNotFound());
        api.get("/api/billing/sandbox/orders/" + gatewayOrderId, otherAdmin.accessToken())
                .andExpect(status().isNotFound());
        verify(otherAdmin, orderId, "pay_x", sign(gatewayOrderId + "|pay_x")).andExpect(status().isNotFound());

        String email = "accounts@" + school.code() + ".akshara.test";
        api.createUser(admin, "Anil Accountant", email, List.of("ACCOUNTANT"));
        Session accountant = api.login(school.code(), email, TestApi.PASSWORD);
        api.post(orders(invoiceId), accountant.accessToken(), "{}").andExpect(status().isForbidden());
        api.get("/api/billing/sandbox/orders/" + gatewayOrderId, accountant.accessToken())
                .andExpect(status().isForbidden());
        verify(accountant, orderId, "pay_x", sign(gatewayOrderId + "|pay_x")).andExpect(status().isForbidden());

        // The Super Admin records bank payments by hand, never "online" ones.
        api.post(BillingFixtures.school(school.tenantId()) + "/invoices/" + invoiceId + "/payments",
                root.accessToken(), """
                {"mode":"ONLINE","reference":"pay_fake","paidOn":"%s"}""".formatted(FeeFixtures.today()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.mode").exists());
        assertThat(count("billing.invoice_payment")).isZero();
    }

    @Test
    void aPaymentForAnInvoiceSettledMeanwhileIsNotRecordedTwice() throws Exception {
        String created = api.post(orders(invoiceId), admin.accessToken(), "{}")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String orderId = JsonPath.read(created, "$.id");
        String gatewayOrderId = JsonPath.read(created, "$.gatewayOrderId");
        // The school also paid by bank transfer, recorded by the Super Admin.
        billing.pay(root, school.tenantId(), invoiceId);

        verify(orderId, "pay_SBXlate", sign(gatewayOrderId + "|pay_SBXlate"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAID"))
                .andExpect(jsonPath("$.payments.length()").value(1))
                .andExpect(jsonPath("$.payments[0].mode").value("BANK_TRANSFER"));
        api.get("/api/billing/orders/" + orderId, admin.accessToken())
                .andExpect(jsonPath("$.status").value("PAID"))
                .andExpect(jsonPath("$.failureReason").value(org.hamcrest.Matchers.containsString("refund")));
        assertThat(events.stream(SubscriptionInvoicePaid.class)).hasSize(1);
    }

    // ------------------------------------------------------------------ helpers

    private static String orders(String invoiceId) {
        return "/api/billing/invoices/" + invoiceId + "/orders";
    }

    private ResultActions verify(String orderId, String paymentId, String signature) throws Exception {
        return verify(admin, orderId, paymentId, signature);
    }

    private ResultActions verify(Session who, String orderId, String paymentId, String signature) throws Exception {
        return api.post("/api/billing/orders/" + orderId + "/verify", who.accessToken(), """
                {"gatewayPaymentId":"%s","signature":"%s"}""".formatted(paymentId, signature));
    }

    private ResultActions webhook(String payload, String signature) throws Exception {
        var request = MockMvcRequestBuilders.post("/api/public/billing/webhook/sandbox")
                .contentType(MediaType.APPLICATION_JSON).content(payload);
        if (signature != null) {
            request.header("X-Sandbox-Signature", signature);
        }
        return mvc.perform(request);
    }

    static String sign(String data) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(BILLING_SANDBOX_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
    }

    static String webhookJson(String eventId, String event, String orderId, String paymentId, long amount) {
        return FeeFixtures.webhookJson(eventId, event, orderId, paymentId, amount);
    }

    private long count(String table) throws Exception {
        try (Connection c = ownerConnection();
                PreparedStatement ps = c.prepareStatement("select count(*) from " + table + " where tenant_id = ?")) {
            ps.setObject(1, school.tenantId());
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }
}
