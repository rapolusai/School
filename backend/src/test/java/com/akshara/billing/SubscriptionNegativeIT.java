package com.akshara.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.URI;
import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import com.akshara.platform.TenantStatus;
import com.akshara.support.BillingFixtures;
import com.akshara.support.IntegrationTest;
import com.akshara.support.SchoolFixtures;
import com.akshara.support.TestApi;
import com.akshara.support.TestApi.School;
import com.akshara.support.TestApi.Session;
import com.jayway.jsonpath.JsonPath;

/**
 * Negative sweep of 2026-10-29 (docs/testing/negative-sweep-2026-10-29.md), subscriptions: tampered, replayed and
 * out-of-order webhooks, odd ids and roles on online payments, concurrent verification, plan gates reached through
 * other spellings of their paths, the student cap under concurrent additions, plan changes with odd inputs, and the
 * renewal job on month ends, leap days and the financial year end.
 */
class SubscriptionNegativeIT extends IntegrationTest {

    @Autowired
    BillingRenewals renewals;

    private BillingFixtures billing;
    private Session root;
    private School school;
    private Session admin;

    @BeforeEach
    void school() throws Exception {
        billing = new BillingFixtures(api);
        root = billing.root();
        school = api.signup();
        admin = api.login(school);
    }

    // ------------------------------------------------------------------ online payment and webhooks

    @Test
    void webhooksThatAreUnknownHugeUnreadableOrOutOfOrderChangeNothing() throws Exception {
        String invoiceId = billing.paying(root, school.tenantId(), "36");
        String created = api.post(orders(invoiceId), admin.accessToken(), "{}")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String orderId = JsonPath.read(created, "$.id");
        String gatewayOrderId = JsonPath.read(created, "$.gatewayOrderId");
        long amount = ((Number) JsonPath.read(created, "$.amountPaise")).longValue();

        // An unknown gateway is 404; the gateway name is case-insensitive.
        webhook("paypal", "{}", null).andExpect(status().isNotFound());
        webhook("..%2fsandbox", "{}", null).andExpect(status().is4xxClientError());
        String unknown = OnlineInvoicePaymentIT.webhookJson("evt_neg_unknown", "payment.captured", "order_nobody",
                "pay_x", 1);
        webhook("SANDBOX", unknown, OnlineInvoicePaymentIT.sign(unknown))
                .andExpect(status().isOk()).andExpect(jsonPath("$.outcome").value("IGNORED"));
        // Empty, over 64 KB, signed but not JSON, signed JSON without an event id.
        webhook("sandbox", "", OnlineInvoicePaymentIT.sign("")).andExpect(status().isBadRequest());
        String huge = "{\"pad\":\"" + "x".repeat(70_000) + "\"}";
        webhook("sandbox", huge, OnlineInvoicePaymentIT.sign(huge)).andExpect(status().isBadRequest());
        webhook("sandbox", "not json", OnlineInvoicePaymentIT.sign("not json")).andExpect(status().isBadRequest());
        String noId = "{\"event\":\"payment.captured\"}";
        webhook("sandbox", noId, OnlineInvoicePaymentIT.sign(noId)).andExpect(status().isBadRequest());
        // A captured payment for another amount is ignored and records nothing.
        String wrongAmount = OnlineInvoicePaymentIT.webhookJson("evt_neg_amount", "payment.captured", gatewayOrderId,
                "pay_SBXneg1", amount - 1);
        webhook("sandbox", wrongAmount, OnlineInvoicePaymentIT.sign(wrongAmount))
                .andExpect(jsonPath("$.outcome").value("IGNORED"));
        assertThat(count("billing.invoice_payment")).isZero();

        // A failure that arrives after the capture leaves the order paid.
        String captured = OnlineInvoicePaymentIT.webhookJson("evt_neg_cap", "payment.captured", gatewayOrderId,
                "pay_SBXneg2", amount);
        webhook("sandbox", captured, OnlineInvoicePaymentIT.sign(captured))
                .andExpect(jsonPath("$.outcome").value("RECORDED"));
        String failed = OnlineInvoicePaymentIT.webhookJson("evt_neg_fail", "payment.failed", gatewayOrderId,
                "pay_SBXneg2", amount);
        webhook("sandbox", failed, OnlineInvoicePaymentIT.sign(failed)).andExpect(jsonPath("$.outcome").value("IGNORED"));
        api.get("/api/billing/orders/" + orderId, admin.accessToken()).andExpect(jsonPath("$.status").value("PAID"));
        // The same event again, and a second capture with another payment id: one payment only.
        webhook("sandbox", captured, OnlineInvoicePaymentIT.sign(captured))
                .andExpect(jsonPath("$.outcome").value("DUPLICATE"));
        String other = OnlineInvoicePaymentIT.webhookJson("evt_neg_cap2", "payment.captured", gatewayOrderId,
                "pay_SBXneg3", amount);
        webhook("sandbox", other, OnlineInvoicePaymentIT.sign(other))
                .andExpect(jsonPath("$.outcome").value("ALREADY_RECORDED"));
        assertThat(count("billing.invoice_payment")).isEqualTo(1);
    }

    @Test
    void ordersRefuseOddIdsOtherRolesCancelledInvoicesAndRecordOnceUnderConcurrency() throws Exception {
        String invoiceId = billing.paying(root, school.tenantId(), "36");
        // Ids that are not UUIDs, or nobody's.
        api.post("/api/billing/invoices/not-a-uuid/orders", admin.accessToken(), "{}")
                .andExpect(status().isNotFound());
        api.post(orders(UUID.randomUUID().toString()), admin.accessToken(), "{}").andExpect(status().isNotFound());
        api.get("/api/billing/orders/" + UUID.randomUUID(), admin.accessToken()).andExpect(status().isNotFound());
        api.post("/api/billing/sandbox/orders/order_SBXnobody/complete", admin.accessToken(), """
                {"outcome":"SUCCESS"}""").andExpect(status().isNotFound());
        api.post("/api/billing/sandbox/orders/x/complete", admin.accessToken(), """
                {"outcome":"MAYBE"}""").andExpect(status().isBadRequest());

        // A teacher has no billing at all; the platform console has no school to pay for.
        String email = "teacher@" + school.code() + ".akshara.test";
        api.createUser(admin, "Tara Teacher", email, List.of("TEACHER"));
        Session teacher = api.login(school.code(), email, TestApi.PASSWORD);
        api.post(orders(invoiceId), teacher.accessToken(), "{}").andExpect(status().isForbidden());
        api.post(orders(invoiceId), root.accessToken(), "{}").andExpect(status().is4xxClientError());

        String created = api.post(orders(invoiceId), admin.accessToken(), "{}")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String orderId = JsonPath.read(created, "$.id");
        String gatewayOrderId = JsonPath.read(created, "$.gatewayOrderId");
        long amount = ((Number) JsonPath.read(created, "$.amountPaise")).longValue();

        // Verification from four tabs and the webhook, all at once: one payment.
        String paymentId = "pay_SBXrace01";
        String signature = OnlineInvoicePaymentIT.sign(gatewayOrderId + "|" + paymentId);
        String captured = OnlineInvoicePaymentIT.webhookJson("evt_race", "payment.captured", gatewayOrderId,
                paymentId, amount);
        ExecutorService pool = Executors.newFixedThreadPool(5);
        try {
            List<Callable<Integer>> calls = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                calls.add(() -> api.post("/api/billing/orders/" + orderId + "/verify", admin.accessToken(), """
                        {"gatewayPaymentId":"%s","signature":"%s"}""".formatted(paymentId, signature))
                        .andReturn().getResponse().getStatus());
            }
            calls.add(() -> webhook("sandbox", captured, OnlineInvoicePaymentIT.sign(captured))
                    .andReturn().getResponse().getStatus());
            for (Future<Integer> f : pool.invokeAll(calls)) {
                assertThat(f.get()).isEqualTo(200);
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(count("billing.invoice_payment")).isEqualTo(1);
        api.post(orders(invoiceId), admin.accessToken(), "{}").andExpect(status().isConflict());

        // A cancelled invoice cannot be paid; the order made before it was cancelled records nothing on it.
        // An upgrade issues a second invoice; once the Super Admin cancels it, it cannot be paid, and the order made
        // before it was cancelled records nothing on it.
        api.post("/api/billing/plan", admin.accessToken(), """
                {"plan":"ENTERPRISE"}""").andExpect(status().isOk());
        String second = TestApi.read(api.get("/api/billing", admin.accessToken()), "$.invoices[0].id");
        String secondOrder = TestApi.read(api.post(orders(second), admin.accessToken(), "{}")
                .andExpect(status().isCreated()), "$.gatewayOrderId");
        String secondOrderId = TestApi.read(api.get("/api/billing/sandbox/orders/" + secondOrder,
                admin.accessToken()), "$.orderId");
        api.post(BillingFixtures.school(school.tenantId()) + "/invoices/" + second + "/cancel", root.accessToken(), """
                {"reason":"Issued by mistake"}""").andExpect(status().isOk());
        api.post(orders(second), admin.accessToken(), "{}").andExpect(status().isConflict());
        api.post("/api/billing/orders/" + secondOrderId + "/verify", admin.accessToken(), """
                {"gatewayPaymentId":"pay_SBXlate2","signature":"%s"}"""
                .formatted(OnlineInvoicePaymentIT.sign(secondOrder + "|pay_SBXlate2")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"));
        assertThat(count("billing.invoice_payment")).isEqualTo(1);
    }

    @Test
    void aSuspendedSchoolsLiveTokenCanStillPayButNotChangeThePlan() throws Exception {
        String invoiceId = billing.paying(root, school.tenantId(), "36");
        api.post(BillingFixtures.school(school.tenantId()) + "/suspend", root.accessToken(), """
                {"reason":"Unpaid"}""").andExpect(status().isOk());
        // The access token signed before the suspension lives on for its last minutes.
        api.post("/api/billing/plan", admin.accessToken(), """
                {"plan":"ENTERPRISE"}""").andExpect(status().isConflict());
        api.post("/api/billing/cancel", admin.accessToken(), null).andExpect(status().isConflict());
        api.post(orders(invoiceId), admin.accessToken(), "{}").andExpect(status().isCreated());
    }

    // ------------------------------------------------------------------ plan gates and limits

    @Test
    void aGatedFeatureCannotBeReachedThroughAnotherSpellingOfItsPath() throws Exception {
        billing.details(root, school.tenantId(), "36", null);
        billing.start(root, school.tenantId(), "STARTER", "YEARLY", 20, null);
        api.get("/api/transport/vehicles", admin.accessToken()).andExpect(status().isForbidden());
        for (String path : List.of("/api/%74ransport/vehicles", "/api/transport/%76ehicles",
                "/api/library/%72ules", "/api/%6Ce/library", "/api/exams/report%2Dcards?sectionId=x",
                "/api/transport/vehicles/", "/api/TRANSPORT/vehicles", "/api/inventory/lookups;x=1",
                "/api//library/rules", "/api/library/./rules", "/api/me/../library/rules")) {
            int code = mvc.perform(MockMvcRequestBuilders.get(URI.create(path))
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + admin.accessToken()))
                    .andReturn().getResponse().getStatus();
            assertThat(code).as(path).isIn(400, 403, 404);
        }
    }

    @Test
    void twoAdditionsRacingForTheLastPlaceOnlyOneFits() throws Exception {
        SchoolFixtures fixtures = new SchoolFixtures(api);
        fixtures.currentYear(admin);
        String classFive = fixtures.schoolClass(admin, "Class 5");
        String section = fixtures.section(admin, classFive, "A", 400);
        StringBuilder csv = new StringBuilder(PlanLimitsIT.CSV_HEADER).append('\n');
        for (int i = 1; i <= 298; i++) {
            csv.append("RACE/%d,Kid%d,,2015-01-01,F,Class 5,A,Parent %d,MOTHER,97%08d,\n".formatted(i, i, i, i));
        }
        api.post("/api/students/import?dryRun=false", admin.accessToken(),
                tools.jackson.databind.json.JsonMapper.builder().build()
                        .writeValueAsString(java.util.Map.of("csv", csv.toString())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.created").value(298));
        billing.details(root, school.tenantId(), "36", null);
        billing.start(root, school.tenantId(), "STARTER", "YEARLY", 300, null);

        // 298 on roll, room for 2; four additions at once: exactly two fit.
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<Callable<Integer>> adds = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                String no = "RACE/X" + i;
                String phone = "96000000%02d".formatted(i);
                adds.add(() -> api.post("/api/students", admin.accessToken(),
                        SchoolFixtures.studentJson(section, no, "Racer", null, "Parent", phone, null))
                        .andReturn().getResponse().getStatus());
            }
            List<Integer> codes = new ArrayList<>();
            for (Future<Integer> f : pool.invokeAll(adds)) {
                codes.add(f.get());
            }
            assertThat(codes).filteredOn(c -> c == 201).hasSize(2);
            assertThat(codes).filteredOn(c -> c == 409).hasSize(2);
        } finally {
            pool.shutdownNow();
        }
        api.get("/api/billing/plan-access", admin.accessToken()).andExpect(jsonPath("$.activeStudents").value(300));
    }

    // ------------------------------------------------------------------ plan changes

    @Test
    void planChangesWithOddInputsDoNothingHarmful() throws Exception {
        api.get("/api/billing/plan/quote?plan=PLATINUM", admin.accessToken()).andExpect(status().isBadRequest());
        api.get("/api/billing/plan/quote", admin.accessToken()).andExpect(status().isBadRequest());
        api.post("/api/billing/plan", admin.accessToken(), """
                {"plan":"PLATINUM"}""").andExpect(status().isBadRequest());
        api.post("/api/billing/plan", admin.accessToken(), """
                {"plan":null}""").andExpect(status().isBadRequest());
        api.post("/api/billing/plan", admin.accessToken(), "[]").andExpect(status().isBadRequest());
        // Resume and withdraw with nothing to take back are harmless. (A trial can be cancelled: see
        // SubscriptionCancellationIT.)
        api.post("/api/billing/resume", admin.accessToken(), null).andExpect(status().isOk());
        api.delete("/api/billing/plan/pending", admin.accessToken()).andExpect(status().isOk());
        // The Super Admin has no school plan to change.
        api.post("/api/billing/plan", root.accessToken(), """
                {"plan":"GROWTH"}""").andExpect(status().is4xxClientError());

        // Upgrade on the last day of a monthly period, one student: a small, correctly rounded invoice.
        billing.details(root, school.tenantId(), "36", null);
        LocalDate today = BillingDay.today();
        // The monthly period that ends soonest on or after today: its last day is today when the month lengths
        // allow one (31 October: 1-31 October), otherwise a day or two later (30 October: 1-31 October).
        LocalDate start = today.minusDays(40);
        while (BillingCycle.MONTHLY.periodEnd(start).isBefore(today)) {
            start = start.plusDays(1);
        }
        LocalDate periodEnd = BillingCycle.MONTHLY.periodEnd(start);
        long remainingDays = java.time.temporal.ChronoUnit.DAYS.between(today, periodEnd) + 1;
        assertThat(remainingDays).isBetween(1L, 3L);
        billing.start(root, school.tenantId(), "STARTER", "MONTHLY", 1, start.toString());
        api.get("/api/billing/plan/quote?plan=GROWTH", admin.accessToken())
                .andExpect(jsonPath("$.remainingDays").value((int) remainingDays));
        String body = api.post("/api/billing/plan", admin.accessToken(), """
                {"plan":"GROWTH"}""").andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat((Integer) JsonPath.read(body, "$.invoices.length()")).isEqualTo(2);
        long periodDays = java.time.temporal.ChronoUnit.DAYS.between(start, periodEnd) + 1;
        long unit = Math.round((2000 - 830) * remainingDays / (double) periodDays);
        String upgrade = JsonPath.read(body, "$.invoices[0].id");
        api.get("/api/billing/invoices/" + upgrade, admin.accessToken())
                .andExpect(jsonPath("$.kind").value("UPGRADE"))
                .andExpect(jsonPath("$.unitPricePaise").value(unit))
                .andExpect(jsonPath("$.taxablePaise").value(unit));

        // Cancel twice is one cancellation; resume twice is one resumption.
        api.post("/api/billing/cancel", admin.accessToken(), """
                {"reason":"Too dear"}""").andExpect(status().isOk());
        api.post("/api/billing/cancel", admin.accessToken(), null).andExpect(status().isOk());
        api.post("/api/billing/resume", admin.accessToken(), null).andExpect(status().isOk());
        api.post("/api/billing/resume", admin.accessToken(), null).andExpect(status().isOk());
        api.get("/api/audit-events?limit=50", admin.accessToken())
                .andExpect(jsonPath("$[?(@.action == 'subscription.cancel_scheduled')]")
                        .value(org.hamcrest.Matchers.hasSize(1)))
                .andExpect(jsonPath("$[?(@.action == 'subscription.cancel_withdrawn')]")
                        .value(org.hamcrest.Matchers.hasSize(1)));
    }

    // ------------------------------------------------------------------ the renewal job on odd dates

    @Test
    void renewalsOnMonthEndsLeapDaysAndTheFinancialYearEndLeaveNoGapAndRunOnce() throws Exception {
        billing.details(root, school.tenantId(), "36", null);
        String first = billing.start(root, school.tenantId(), "GROWTH", "MONTHLY", 10, null);
        billing.pay(root, school.tenantId(), first);

        // A monthly period from 31 January 2028 (a leap year): it renews into February and on, with no gap.
        setPeriod(LocalDate.of(2028, 1, 31), LocalDate.of(2028, 1, 31).plusMonths(1).minusDays(1));
        LocalDate end = periodEnd();
        BillingRenewals.Outcome renewed = renewals.runFor(school.tenantId(), end.plusDays(1), at(end.plusDays(1)));
        assertThat(renewed.renewed()).isTrue();
        assertThat(periodStart()).isEqualTo(end.plusDays(1));
        // Twice on the same day: nothing more.
        assertThat(renewals.runFor(school.tenantId(), end.plusDays(1), at(end.plusDays(1))).renewed()).isFalse();

        // A yearly period that starts on 29 February ends the day before the same date next year.
        assertThat(BillingCycle.YEARLY.periodEnd(LocalDate.of(2028, 2, 29))).isEqualTo(LocalDate.of(2029, 2, 27));
        assertThat(BillingCycle.MONTHLY.periodEnd(LocalDate.of(2028, 2, 29))).isEqualTo(LocalDate.of(2028, 3, 28));

        // A period ending on 31 March renews into a new financial year's series.
        setPeriod(LocalDate.of(2029, 3, 1), LocalDate.of(2029, 3, 31));
        markInvoicesPaid();
        BillingRenewals.Outcome fy = renewals.runFor(school.tenantId(), LocalDate.of(2029, 4, 1),
                at(LocalDate.of(2029, 4, 1)));
        assertThat(fy.renewed()).isTrue();
        assertThat(fy.invoiceNo()).startsWith("AKS/29-30/");
        assertThat(fy.statusAfter()).isEqualTo(TenantStatus.ACTIVE);
    }

    // ------------------------------------------------------------------ helpers

    private static String orders(String invoiceId) {
        return "/api/billing/invoices/" + invoiceId + "/orders";
    }

    private ResultActions webhook(String gateway, String payload, String signature) throws Exception {
        var request = MockMvcRequestBuilders.post(URI.create("/api/public/billing/webhook/" + gateway))
                .contentType(MediaType.APPLICATION_JSON).content(payload);
        if (signature != null) {
            request.header("X-Sandbox-Signature", signature);
        }
        return mvc.perform(request);
    }

    private static Instant at(LocalDate day) {
        return day.atTime(10, 0).toInstant(ZoneOffset.ofHoursMinutes(5, 30));
    }

    private void setPeriod(LocalDate start, LocalDate end) throws Exception {
        try (Connection c = ownerConnection(); PreparedStatement ps = c.prepareStatement(
                "update billing.subscription set period_start = ?, period_end = ?, paid_since = ? where tenant_id = ?")) {
            ps.setDate(1, Date.valueOf(start));
            ps.setDate(2, Date.valueOf(end));
            ps.setDate(3, Date.valueOf(start));
            ps.setObject(4, school.tenantId());
            ps.executeUpdate();
        }
        // The period needs a live invoice of its own to renew.
        try (Connection c = ownerConnection(); PreparedStatement ps = c.prepareStatement(
                "update billing.invoice set period_start = ?, period_end = ?, due_date = ? where tenant_id = ? "
                        + "and status <> 'CANCELLED' and kind = 'PERIOD' and id = (select id from billing.invoice "
                        + "where tenant_id = ? and kind = 'PERIOD' order by invoice_date desc, seq desc limit 1)")) {
            ps.setDate(1, Date.valueOf(start));
            ps.setDate(2, Date.valueOf(end));
            ps.setDate(3, Date.valueOf(end.plusYears(5)));
            ps.setObject(4, school.tenantId());
            ps.setObject(5, school.tenantId());
            ps.executeUpdate();
        }
    }

    private void markInvoicesPaid() throws Exception {
        try (Connection c = ownerConnection(); PreparedStatement ps = c.prepareStatement(
                "update billing.invoice set due_date = ? where tenant_id = ? and status = 'ISSUED'")) {
            ps.setDate(1, Date.valueOf(LocalDate.of(2040, 1, 1)));
            ps.setObject(2, school.tenantId());
            ps.executeUpdate();
        }
    }

    private LocalDate periodEnd() throws Exception {
        return day("select period_end from billing.subscription where tenant_id = ?");
    }

    private LocalDate periodStart() throws Exception {
        return day("select period_start from billing.subscription where tenant_id = ?");
    }

    private LocalDate day(String sql) throws Exception {
        try (Connection c = ownerConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, school.tenantId());
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getDate(1).toLocalDate();
            }
        }
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
