package com.akshara.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.akshara.platform.TenantStatus;
import com.akshara.support.BillingFixtures;
import com.akshara.support.FeeFixtures;
import com.akshara.support.IntegrationTest;
import com.akshara.support.SchoolFixtures;
import com.akshara.support.TestApi;
import com.akshara.support.TestApi.School;
import com.akshara.support.TestApi.Session;

import tools.jackson.databind.json.JsonMapper;

/**
 * A School Admin changes the plan from the Billing page: an upgrade at once with a prorated GST invoice, a downgrade
 * at the next renewal (refused when the students on roll do not fit), and a cancellation at the period's end that can
 * be taken back. The Super Admin sees each change, and each is audited.
 */
class PlanChangeIT extends IntegrationTest {

    @Autowired
    BillingRenewals renewals;

    private final JsonMapper json = JsonMapper.builder().build();

    private BillingFixtures billing;
    private Session root;
    private School school;
    private Session admin;
    private String account;
    private LocalDate today;

    @BeforeEach
    void school() throws Exception {
        billing = new BillingFixtures(api);
        root = billing.root();
        school = api.signup();
        admin = api.login(school);
        account = BillingFixtures.school(school.tenantId());
        today = BillingDay.today();
        billing.details(root, school.tenantId(), "36", null);
    }

    @Test
    void anUpgradeIsImmediateWithAProratedGstInvoiceForTheRestOfThePeriodAndASecondClickDoesNothingMore()
            throws Exception {
        LocalDate start = today.minusDays(100);
        billing.start(root, school.tenantId(), "STARTER", "YEARLY", 100, start.toString());
        api.get("/api/library/rules", admin.accessToken()).andExpect(status().isForbidden());

        LocalDate end = start.plusYears(1).minusDays(1);
        long periodDays = ChronoUnit.DAYS.between(start, end) + 1;
        long remaining = ChronoUnit.DAYS.between(today, end) + 1;
        long unit = BigDecimal.valueOf(200_00 - 83_00).multiply(BigDecimal.valueOf(remaining))
                .divide(BigDecimal.valueOf(periodDays), 0, RoundingMode.HALF_UP).longValueExact();
        long taxable = unit * 100;
        GstMath.Tax tax = GstMath.tax(taxable, "36", "36");

        api.get("/api/billing/plan/quote?plan=GROWTH", admin.accessToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kind").value("UPGRADE"))
                .andExpect(jsonPath("$.remainingDays").value(remaining))
                .andExpect(jsonPath("$.periodDays").value(periodDays))
                .andExpect(jsonPath("$.unitPricePaise").value(unit))
                .andExpect(jsonPath("$.taxablePaise").value(taxable))
                .andExpect(jsonPath("$.totalPaise").value(tax.totalPaise()));

        api.post("/api/billing/plan", admin.accessToken(), """
                {"plan":"GROWTH"}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.plan").value("GROWTH"))
                .andExpect(jsonPath("$.invoices.length()").value(2))
                .andExpect(jsonPath("$.invoices[0].kind").value("UPGRADE"))
                .andExpect(jsonPath("$.invoices[0].previousPlan").value("STARTER"))
                .andExpect(jsonPath("$.invoices[1].kind").value("PERIOD"));
        // A second click (or tab) finds the plan changed: no second invoice.
        api.post("/api/billing/plan", admin.accessToken(), """
                {"plan":"GROWTH"}""")
                .andExpect(status().isOk()).andExpect(jsonPath("$.invoices.length()").value(2));

        String upgradeId = TestApi.read(api.get("/api/billing", admin.accessToken()), "$.invoices[0].id");
        api.get("/api/billing/invoices/" + upgradeId, admin.accessToken())
                .andExpect(jsonPath("$.plan").value("GROWTH"))
                .andExpect(jsonPath("$.kind").value("UPGRADE"))
                .andExpect(jsonPath("$.periodStart").value(today.toString()))
                .andExpect(jsonPath("$.periodEnd").value(end.toString()))
                .andExpect(jsonPath("$.billedStudents").value(100))
                .andExpect(jsonPath("$.unitPricePaise").value(unit))
                .andExpect(jsonPath("$.taxablePaise").value(taxable))
                .andExpect(jsonPath("$.cgstPaise").value(tax.cgstPaise()))
                .andExpect(jsonPath("$.sgstPaise").value(tax.sgstPaise()))
                .andExpect(jsonPath("$.totalPaise").value(tax.totalPaise()))
                .andExpect(jsonPath("$.sacCode").value("998315"))
                .andExpect(jsonPath("$.invoiceNo").value(org.hamcrest.Matchers.matchesPattern("AKS/\\d{2}-\\d{2}/\\d{6}")));
        // Unlocked at once; the next renewal is billed at the new plan; the Super Admin sees it.
        api.get("/api/library/rules", admin.accessToken()).andExpect(status().isOk());
        api.get(account, root.accessToken())
                .andExpect(jsonPath("$.school.plan").value("GROWTH"))
                .andExpect(jsonPath("$.subscription.nextInvoiceTaxablePaise").value(200_00 * 100))
                .andExpect(jsonPath("$.invoices[0].kind").value("UPGRADE"));
        api.get("/api/audit-events?limit=30", admin.accessToken())
                .andExpect(jsonPath("$[?(@.action == 'subscription.upgraded')].details.previousPlan")
                        .value(hasItem("STARTER")))
                .andExpect(jsonPath("$[?(@.action == 'subscription.upgraded')]").value(
                        org.hamcrest.Matchers.hasSize(1)));
        // The upgrade invoice is paid like any other, online too.
        api.post("/api/billing/invoices/" + upgradeId + "/orders", admin.accessToken(), "{}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.amountPaise").value(tax.totalPaise()));
    }

    @Test
    void aDowngradeWaitsForTheRenewalAndIsRefusedWhileTheStudentsOnRollDoNotFit() throws Exception {
        SchoolFixtures fixtures = new SchoolFixtures(api);
        fixtures.currentYear(admin);
        String classFive = fixtures.schoolClass(admin, "Class 5");
        String section = fixtures.section(admin, classFive, "A", 400);
        api.post("/api/students/import?dryRun=false", admin.accessToken(), csv(1, 300)).andExpect(status().isOk());
        String extra = fixtures.student(admin, section, "DG/301", "One", "More", "Parent", "9700000301");
        // A monthly Growth subscription whose period ends yesterday is renewed today.
        billing.start(root, school.tenantId(), "GROWTH", "MONTHLY", 350, today.minusMonths(1).toString());
        LocalDate nextPeriod = today.minusMonths(1).plusMonths(1);
        String renewalsJobDay = nextPeriod.toString();

        api.get("/api/billing/plan/quote?plan=STARTER", admin.accessToken())
                .andExpect(jsonPath("$.kind").value("DOWNGRADE"))
                .andExpect(jsonPath("$.allowed").value(false))
                .andExpect(jsonPath("$.effectiveOn").value(renewalsJobDay));
        api.post("/api/billing/plan", admin.accessToken(), """
                {"plan":"STARTER"}""")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value(PlanChanges.DOWNGRADE_REFUSED_TYPE))
                .andExpect(jsonPath("$.maxStudents").value(300))
                .andExpect(jsonPath("$.activeStudents").value(301));

        api.post("/api/students/" + extra + "/leave", admin.accessToken(), """
                {"status":"TRANSFERRED","leftOn":"%s","reason":"Moved"}""".formatted(FeeFixtures.today()))
                .andExpect(status().isOk());
        api.post("/api/billing/plan", admin.accessToken(), """
                {"plan":"STARTER"}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.plan").value("GROWTH"))
                .andExpect(jsonPath("$.subscription.pendingPlan").value("STARTER"))
                .andExpect(jsonPath("$.subscription.nextInvoiceTaxablePaise").value(8_30 * 300))
                .andExpect(jsonPath("$.invoices.length()").value(1));
        api.get(account, root.accessToken()).andExpect(jsonPath("$.subscription.pendingPlan").value("STARTER"));
        // Withdrawn, and scheduled again.
        api.delete("/api/billing/plan/pending", admin.accessToken())
                .andExpect(status().isOk()).andExpect(jsonPath("$.subscription.pendingPlan").doesNotExist());
        api.post("/api/billing/plan", admin.accessToken(), """
                {"plan":"STARTER"}""").andExpect(status().isOk());

        // The renewal applies it: Starter from the next period, billed students capped at 300.
        BillingRenewals.Outcome outcome = renewals.runFor(school.tenantId(), today, Instant.now());
        assertThat(outcome.renewed()).isTrue();
        assertThat(renewals.runFor(school.tenantId(), today, Instant.now()).changed()).isFalse();
        api.get("/api/billing", admin.accessToken())
                .andExpect(jsonPath("$.plan").value("STARTER"))
                .andExpect(jsonPath("$.subscription.pendingPlan").doesNotExist())
                .andExpect(jsonPath("$.subscription.billedStudents").value(300))
                .andExpect(jsonPath("$.invoices[0].plan").value("STARTER"))
                .andExpect(jsonPath("$.invoices[0].periodStart").value(renewalsJobDay))
                .andExpect(jsonPath("$.invoices[0].totalPaise").value(
                        GstMath.tax(8_30L * 300, "36", "36").totalPaise()));
        api.get("/api/audit-events?limit=30", admin.accessToken())
                .andExpect(jsonPath("$[*].action").value(org.hamcrest.Matchers.hasItems(
                        "subscription.downgrade_scheduled", "subscription.downgrade_withdrawn",
                        "subscription.downgraded", "subscription.renewed")));
    }

    @Test
    void aDowngradeThatNoLongerFitsAtRenewalIsDroppedAndTheSchoolRenewsOnItsPlan() throws Exception {
        SchoolFixtures fixtures = new SchoolFixtures(api);
        fixtures.currentYear(admin);
        String classFive = fixtures.schoolClass(admin, "Class 5");
        String section = fixtures.section(admin, classFive, "A", 400);
        api.post("/api/students/import?dryRun=false", admin.accessToken(), csv(1, 300)).andExpect(status().isOk());
        billing.start(root, school.tenantId(), "GROWTH", "MONTHLY", 300, today.minusMonths(1).toString());
        api.post("/api/billing/plan", admin.accessToken(), """
                {"plan":"STARTER"}""").andExpect(status().isOk());
        // Growth still allows more students meanwhile.
        fixtures.student(admin, section, "DG/301", "One", "More", "Parent", "9700000301");

        assertThat(renewals.runFor(school.tenantId(), today, Instant.now()).renewed()).isTrue();
        api.get("/api/billing", admin.accessToken())
                .andExpect(jsonPath("$.plan").value("GROWTH"))
                .andExpect(jsonPath("$.subscription.pendingPlan").doesNotExist())
                .andExpect(jsonPath("$.invoices[0].plan").value("GROWTH"));
        api.get("/api/audit-events?limit=30", admin.accessToken())
                .andExpect(jsonPath("$[?(@.action == 'subscription.downgrade_dropped')].details.activeStudents")
                        .value(hasItem(301)));
    }

    @Test
    void aCancellationEndsTheSubscriptionWithItsPeriodAndCanBeTakenBackUntilThen() throws Exception {
        LocalDate start = today.minusDays(10);
        billing.pay(root, school.tenantId(),
                billing.start(root, school.tenantId(), "GROWTH", "MONTHLY", 50, start.toString()));
        LocalDate end = start.plusMonths(1).minusDays(1);
        long daysToEnd = ChronoUnit.DAYS.between(today, end);

        api.post("/api/billing/cancel", admin.accessToken(), """
                {"reason":"Moving to another system"}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subscription.cancelAtPeriodEnd").value(true));
        api.get(account, root.accessToken())
                .andExpect(jsonPath("$.subscription.cancelAtPeriodEnd").value(true))
                .andExpect(jsonPath("$.subscription.cancelRequestedAt").exists());
        // The Super Admin cannot invoice the next period while it stands.
        api.post(account + "/invoices", root.accessToken(), "{}").andExpect(status().isConflict());
        api.post("/api/billing/resume", admin.accessToken(), "{}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subscription.cancelAtPeriodEnd").value(false));
        api.post("/api/billing/cancel", admin.accessToken(), null).andExpect(status().isOk());

        // Until the period ends nothing happens; the day after, the school is suspended instead of renewed.
        assertThat(run(daysToEnd).changed()).isFalse();
        BillingRenewals.Outcome ended = run(daysToEnd + 1);
        assertThat(ended.statusAfter()).isEqualTo(TenantStatus.SUSPENDED);
        assertThat(ended.renewed()).isFalse();
        assertThat(run(daysToEnd + 1).changed()).isFalse();
        api.get(account, root.accessToken())
                .andExpect(jsonPath("$.school.status").value("SUSPENDED"))
                .andExpect(jsonPath("$.invoices.length()").value(1))
                .andExpect(jsonPath("$.suspension.reason").value(org.hamcrest.Matchers.containsString("cancelled")));

        // Reactivated by the Super Admin, the school carries on (and renews again).
        api.post(account + "/reactivate", root.accessToken(), "{}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.school.status").value("ACTIVE"))
                .andExpect(jsonPath("$.subscription.cancelAtPeriodEnd").value(false));
        Session again = api.login(school);
        api.get("/api/audit-events?limit=30", again.accessToken())
                .andExpect(jsonPath("$[*].action").value(org.hamcrest.Matchers.hasItems(
                        "subscription.cancel_scheduled", "subscription.cancel_withdrawn", "subscription.ended")));
    }

    @Test
    void aTrialChangesPlanForNothingAndOnlyTheSchoolAdminMayChangeIt() throws Exception {
        api.post("/api/billing/plan", admin.accessToken(), """
                {"plan":"ENTERPRISE"}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.plan").value("ENTERPRISE"))
                .andExpect(jsonPath("$.status").value("TRIAL"))
                .andExpect(jsonPath("$.invoices.length()").value(0));
        api.get("/api/billing/plan/quote?plan=STARTER", admin.accessToken())
                .andExpect(jsonPath("$.kind").value("TRIAL")).andExpect(jsonPath("$.allowed").value(true));
        // A trial can be cancelled (and taken back): see SubscriptionCancellationIT.
        api.post("/api/billing/cancel", admin.accessToken(), "{}").andExpect(status().isOk())
                .andExpect(jsonPath("$.cancellation.trial").value(true));
        api.post("/api/billing/resume", admin.accessToken(), null).andExpect(status().isOk())
                .andExpect(jsonPath("$.cancellation").doesNotExist());
        api.post("/api/billing/plan", admin.accessToken(), """
                {"plan":"NOPE"}""").andExpect(status().isBadRequest());

        // Accountants, other roles and the platform console may not change it.
        String email = "accountant@" + school.code() + ".akshara.test";
        api.createUser(admin, "Accountant Member", email, List.of("ACCOUNTANT"));
        Session accountant = api.login(school.code(), email, TestApi.PASSWORD);
        api.post("/api/billing/plan", accountant.accessToken(), """
                {"plan":"STARTER"}""").andExpect(status().isForbidden());
        api.post("/api/billing/cancel", accountant.accessToken(), "{}").andExpect(status().isForbidden());
        api.post("/api/billing/plan", root.accessToken(), """
                {"plan":"STARTER"}""").andExpect(status().isForbidden());

        // Another school is not touched.
        School other = api.signup();
        api.get(BillingFixtures.school(other.tenantId()), root.accessToken())
                .andExpect(jsonPath("$.school.plan").value("STARTER"));
        api.get(account, root.accessToken()).andExpect(jsonPath("$.school.plan").value("ENTERPRISE"));
    }

    // ------------------------------------------------------------------ helpers

    private BillingRenewals.Outcome run(long daysAhead) {
        return renewals.runFor(school.tenantId(), today.plusDays(daysAhead),
                Instant.now().plus(Duration.ofDays(daysAhead)));
    }

    private String csv(int from, int count) throws Exception {
        StringBuilder csv = new StringBuilder(PlanLimitsIT.CSV_HEADER).append('\n');
        for (int n = from; n < from + count; n++) {
            csv.append(String.format(Locale.ROOT, "DG/%d,Student%d,,2015-01-01,M,Class 5,A,Parent %d,MOTHER,98%08d,%n",
                    n, n, n, n));
        }
        return json.writeValueAsString(Map.of("csv", csv.toString()));
    }
}
