package com.akshara.fees;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;

import com.akshara.support.FeeFixtures;
import com.akshara.support.FeeFixtures.Heads;
import com.akshara.support.IntegrationTest;
import com.akshara.support.SchoolFixtures;
import com.akshara.support.TestApi;
import com.akshara.support.TestApi.School;
import com.akshara.support.TestApi.Session;
import com.jayway.jsonpath.JsonPath;

/**
 * Online payments through the sandbox gateway: a payment is recorded exactly once, whether the browser's verification
 * or the webhook comes first, replays change nothing, and bad signatures record nothing.
 */
@RecordApplicationEvents
class OnlinePaymentIT extends IntegrationTest {

    @Autowired
    ApplicationEvents events;

    private School school;
    private Session admin;
    private FeeFixtures fees;
    private SchoolFixtures fixtures;
    private String arjun;
    private String other;
    private Session parent;

    @BeforeEach
    void schoolWithAParent() throws Exception {
        school = api.signup();
        admin = api.login(school);
        fixtures = new SchoolFixtures(api);
        fees = new FeeFixtures(api, mvc);
        String yearId = fixtures.currentYear(admin);
        String class5 = fixtures.schoolClass(admin, "Class 5");
        String class5A = fixtures.section(admin, class5, "A", 40);
        arjun = fixtures.student(admin, class5A, "O-1", "Arjun", "Sharma", "Anitha Sharma", "9876500001");
        other = fixtures.student(admin, class5A, "O-2", "Kabir", "Khan", "Imran Khan", "9876500005");
        Heads heads = fees.defaultHeads(admin);
        fees.publishedStructure(admin, yearId, class5, heads);
        String mother = TestApi.read(api.get("/api/students/" + arjun, admin.accessToken()), "$.guardians[0].id");
        api.post("/api/students/" + arjun + "/guardians/" + mother + "/sign-in", admin.accessToken(), """
                {"mode":"CREATE","email":"%s","password":"%s"}""".formatted(email("anitha"), TestApi.PASSWORD))
                .andExpect(status().isOk());
        parent = api.login(school.code(), email("anitha"), TestApi.PASSWORD);
    }

    private String email(String name) {
        return name + "@" + school.code() + ".akshara.test";
    }

    @Test
    void aParentPaysTheirChildsFeesOnlineAndItIsRecordedOnce() throws Exception {
        String base = "/api/me/children/" + arjun + "/fees";
        String q2 = TestApi.read(api.get(base, parent.accessToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.student.fullName").value("Arjun Sharma"))
                .andExpect(jsonPath("$.instalments.length()").value(4)), "$.instalments[1].instalmentId");
        // Someone else's child is not there at all.
        api.get("/api/me/children/" + other + "/fees", parent.accessToken()).andExpect(status().isNotFound());
        api.post("/api/me/children/" + other + "/fees/orders", parent.accessToken(), """
                {"instalmentIds":["%s"]}""".formatted(q2)).andExpect(status().isNotFound());
        // Parents never reach the school's fee screens.
        api.get("/api/fees/students/" + arjun, parent.accessToken()).andExpect(status().isForbidden());

        String created = api.post(base + "/orders", parent.accessToken(), """
                {"instalmentIds":["%s"]}""".formatted(q2))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.gateway").value("sandbox"))
                .andExpect(jsonPath("$.gatewayOrderId", startsWith("order_SBX")))
                .andExpect(jsonPath("$.amountPaise").value(11_500_00))
                .andExpect(jsonPath("$.status").value("CREATED"))
                .andExpect(jsonPath("$.instalments[0]").value("Quarter 2"))
                .andReturn().getResponse().getContentAsString();
        String id = JsonPath.read(created, "$.id");
        String gatewayOrderId = JsonPath.read(created, "$.gatewayOrderId");

        // The sandbox checkout page, as the payer sees it; nobody else can open it.
        api.get("/api/payments/sandbox/orders/" + gatewayOrderId, parent.accessToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.studentName").value("Arjun Sharma"))
                .andExpect(jsonPath("$.studentId").value(arjun))
                .andExpect(jsonPath("$.amountPaise").value(11_500_00))
                .andExpect(jsonPath("$.schoolName").value("Test School " + school.code()));
        api.createUser(admin, "Other Parent", email("other"), List.of("PARENT"));
        Session otherParent = api.login(school.code(), email("other"), TestApi.PASSWORD);
        api.get("/api/payments/sandbox/orders/" + gatewayOrderId, otherParent.accessToken())
                .andExpect(status().isNotFound());
        api.get(base + "/orders/" + id, otherParent.accessToken())
                .andExpect(status().isNotFound());

        String paid = api.post("/api/payments/sandbox/orders/" + gatewayOrderId + "/complete", parent.accessToken(),
                """
                {"outcome":"SUCCESS"}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andReturn().getResponse().getContentAsString();
        String paymentId = JsonPath.read(paid, "$.gatewayPaymentId");
        String signature = JsonPath.read(paid, "$.signature");
        assertThat(signature).isEqualTo(FeeFixtures.sign(gatewayOrderId + "|" + paymentId));

        // A forged signature records nothing.
        api.post(base + "/orders/" + id + "/verify", parent.accessToken(), """
                {"gatewayPaymentId":"%s","signature":"%s"}""".formatted(paymentId, "0".repeat(64)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.signature").exists());
        assertThat(count("fees.receipt")).isZero();

        String receipt = TestApi.read(api.post(base + "/orders/" + id + "/verify", parent.accessToken(), """
                {"gatewayPaymentId":"%s","signature":"%s"}""".formatted(paymentId, signature))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("ONLINE"))
                .andExpect(jsonPath("$.source").value("ONLINE"))
                .andExpect(jsonPath("$.gatewayPaymentId").value(paymentId))
                .andExpect(jsonPath("$.amountPaise").value(11_500_00))
                .andExpect(jsonPath("$.collectedByName").value("Online payment"))
                .andExpect(jsonPath("$.lines[0].instalmentLabel").value("Quarter 2")), "$.id");
        // Verifying again (a page reload) returns the same receipt.
        api.post(base + "/orders/" + id + "/verify", parent.accessToken(), """
                {"gatewayPaymentId":"%s","signature":"%s"}""".formatted(paymentId, signature))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(receipt));
        // The gateway's webhook for the same payment arrives later, twice.
        String payload = FeeFixtures.webhookJson("evt_" + UUID.randomUUID(), "payment.captured", gatewayOrderId,
                paymentId, 11_500_00);
        fees.webhook("sandbox", payload, FeeFixtures.sign(payload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("ALREADY_RECORDED"));
        fees.webhook("sandbox", payload, FeeFixtures.sign(payload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("DUPLICATE"));
        assertThat(count("fees.receipt")).isEqualTo(1);
        assertThat(events.stream(FeePaymentReceived.class)).hasSize(1);

        api.get(base, parent.accessToken())
                .andExpect(jsonPath("$.instalments[1].status").value("PAID"))
                .andExpect(jsonPath("$.receipts[0].id").value(receipt));
        api.get(base + "/receipts/" + receipt, parent.accessToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.amountInWords").value("Rupees Eleven Thousand Five Hundred Only"));
        // A receipt of another child, through this child's address, is not found.
        String otherReceipt = fees.cash(admin, other, 1_000_00);
        api.get(base + "/receipts/" + otherReceipt, parent.accessToken()).andExpect(status().isNotFound());
        api.get(base + "/orders/" + id, parent.accessToken())
                .andExpect(jsonPath("$.status").value("PAID"))
                .andExpect(jsonPath("$.receiptId").value(receipt));
        api.get("/api/audit-events?limit=20", admin.accessToken())
                .andExpect(jsonPath("$[*].action", hasItem("payment_order.created")))
                .andExpect(jsonPath("$[*].action", hasItem("fee_payment.recorded")));
    }

    @Test
    void aWebhookRecordsAPaymentTheBrowserNeverConfirmed() throws Exception {
        String q1 = fees.instalmentIds(admin, other).getFirst();
        String created = api.post("/api/fees/students/" + other + "/orders", admin.accessToken(), """
                {"instalmentIds":["%s"]}""".formatted(q1))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String id = JsonPath.read(created, "$.id");
        String gatewayOrderId = JsonPath.read(created, "$.gatewayOrderId");
        String payload = FeeFixtures.webhookJson("evt_1_" + gatewayOrderId, "payment.captured", gatewayOrderId,
                "pay_TEST1", 11_500_00);

        // Without a valid signature nothing is recorded, not even the event.
        fees.webhook("sandbox", payload, null).andExpect(status().isBadRequest());
        fees.webhook("sandbox", payload, FeeFixtures.sign(payload + " ")).andExpect(status().isBadRequest());
        fees.webhook("sandbox", payload.replace("11500", "1"), FeeFixtures.sign(payload))
                .andExpect(status().isBadRequest());
        assertThat(count("fees.receipt")).isZero();
        assertThat(count("fees.gateway_event")).isZero();

        fees.webhook("sandbox", payload, FeeFixtures.sign(payload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("RECORDED"));
        fees.webhook("sandbox", payload, FeeFixtures.sign(payload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("DUPLICATE"));
        assertThat(count("fees.receipt")).isEqualTo(1);
        String receipt = TestApi.read(api.get("/api/fees/orders/" + id, admin.accessToken())
                .andExpect(jsonPath("$.status").value("PAID")), "$.receiptId");
        api.get("/api/fees/receipts/" + receipt, admin.accessToken())
                .andExpect(jsonPath("$.gatewayPaymentId").value("pay_TEST1"))
                .andExpect(jsonPath("$.mode").value("ONLINE"));
        // The browser's verification arriving afterwards finds the same receipt.
        api.post("/api/fees/orders/" + id + "/verify", admin.accessToken(), """
                {"gatewayPaymentId":"pay_TEST1","signature":"%s"}"""
                .formatted(FeeFixtures.sign(gatewayOrderId + "|pay_TEST1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(receipt));
        assertThat(count("fees.receipt")).isEqualTo(1);

        // Unknown orders are acknowledged and ignored; unknown gateways do not exist.
        String unknown = FeeFixtures.webhookJson("evt_2", "payment.captured", "order_NOPE", "pay_X", 100);
        fees.webhook("sandbox", unknown, FeeFixtures.sign(unknown))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("IGNORED"));
        fees.webhook("razorpay", payload, FeeFixtures.sign(payload)).andExpect(status().isNotFound());

        // A failed sandbox payment marks a new order failed; it can still be paid later.
        String q2 = fees.instalmentIds(admin, other).get(1);
        String second = api.post("/api/fees/students/" + other + "/orders", admin.accessToken(), """
                {"instalmentIds":["%s"]}""".formatted(q2)).andReturn().getResponse().getContentAsString();
        String secondId = JsonPath.read(second, "$.id");
        String secondGatewayId = JsonPath.read(second, "$.gatewayOrderId");
        api.post("/api/payments/sandbox/orders/" + secondGatewayId + "/complete", admin.accessToken(), """
                {"outcome":"FAILURE"}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAILED"));
        api.get("/api/fees/orders/" + secondId, admin.accessToken())
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.failureReason").exists());
        assertThat(count("fees.receipt")).isEqualTo(1);
        // Nothing left to pay in Q1 means no order for it.
        api.post("/api/fees/students/" + other + "/orders", admin.accessToken(), """
                {"instalmentIds":["%s"]}""".formatted(q1)).andExpect(status().isConflict());
    }

    private long count(String table) throws Exception {
        try (Connection owner = ownerConnection();
                PreparedStatement s = owner.prepareStatement("select count(*) from " + table
                        + " where tenant_id = ?")) {
            s.setObject(1, school.tenantId());
            try (ResultSet rs = s.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }
}
