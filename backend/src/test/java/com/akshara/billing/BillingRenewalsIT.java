package com.akshara.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;

import com.akshara.platform.TenantStatus;
import com.akshara.support.BillingFixtures;
import com.akshara.support.IntegrationTest;
import com.akshara.support.TestApi.School;
import com.akshara.support.TestApi.Session;

/**
 * The daily renewal job, run for one school at fixed dates: renewal invoices when a period ends, past due when an
 * invoice is overdue or a trial lapses, suspension after the grace period, and back to active once settled. Running
 * it twice changes nothing the second time. (Rounds across all schools are not run here: they would touch other
 * tests' schools; only the cross-instance lock is checked.)
 */
@RecordApplicationEvents
class BillingRenewalsIT extends IntegrationTest {

    @Autowired
    BillingRenewals renewals;

    @Autowired
    ApplicationEvents events;

    private BillingFixtures billing;
    private Session root;
    private School school;
    private String base;
    private LocalDate today;
    private Instant now;

    @BeforeEach
    void school() throws Exception {
        billing = new BillingFixtures(api);
        root = billing.root();
        school = api.signup();
        base = BillingFixtures.school(school.tenantId());
        today = BillingDay.today();
        now = Instant.now();
        billing.details(root, school.tenantId(), "36", null);
    }

    private BillingRenewals.Outcome run(int daysAhead) {
        return renewals.runFor(school.tenantId(), today.plusDays(daysAhead), now.plus(Duration.ofDays(daysAhead)));
    }

    @Test
    void aPeriodThatEndedIsRenewedWithItsInvoiceOnceOnly() throws Exception {
        // A monthly subscription that started 40 days ago: its first period has ended.
        billing.start(root, school.tenantId(), "GROWTH", "MONTHLY", 100, today.minusDays(40).toString());
        LocalDate secondPeriod = today.minusDays(40).plusMonths(1);

        BillingRenewals.Outcome first = run(0);
        assertThat(first.renewed()).isTrue();
        assertThat(first.invoiceNo()).matches("AKS/\\d{2}-\\d{2}/\\d{6}");
        assertThat(first.statusAfter()).isEqualTo(TenantStatus.ACTIVE);
        // Run again the same day, and the next: nothing more to do.
        assertThat(run(0).changed()).isFalse();
        assertThat(run(1).changed()).isFalse();

        api.get(base, root.accessToken())
                .andExpect(jsonPath("$.invoices.length()").value(2))
                .andExpect(jsonPath("$.invoices[0].invoiceNo").value(first.invoiceNo()))
                .andExpect(jsonPath("$.invoices[0].periodStart").value(secondPeriod.toString()))
                .andExpect(jsonPath("$.subscription.periodStart").value(secondPeriod.toString()));
        Session admin = api.login(school);
        api.get("/api/audit-events?limit=30", admin.accessToken())
                .andExpect(jsonPath("$[?(@.action == 'subscription.renewed')].actorName").value(hasItem("Akshara billing")))
                .andExpect(jsonPath("$[?(@.action == 'invoice.issued')].details.invoiceNo")
                        .value(hasItem(first.invoiceNo())));
        assertThat(events.stream(SubscriptionInvoiceIssued.class)).hasSize(2);
        assertThat(count("select count(*) from billing.invoice where tenant_id = ?")).isEqualTo(2);
    }

    @Test
    void anUnpaidInvoiceMakesTheSchoolPastDueThenSuspendedAfterTheGracePeriod() throws Exception {
        String invoiceId = billing.start(root, school.tenantId(), "GROWTH", "YEARLY", 50, null);
        // Due in 15 days: nothing happens until then.
        assertThat(run(15).changed()).isFalse();

        BillingRenewals.Outcome late = run(16);
        assertThat(late.statusAfter()).isEqualTo(TenantStatus.PAST_DUE);
        assertThat(run(16).changed()).isFalse();
        // 15 grace days after the due date: still past due on the last day, suspended the day after.
        assertThat(run(30).statusAfter()).isEqualTo(TenantStatus.PAST_DUE);
        BillingRenewals.Outcome suspended = run(31);
        assertThat(suspended.statusAfter()).isEqualTo(TenantStatus.SUSPENDED);
        assertThat(run(31).changed()).isFalse();
        assertThat(events.stream(SchoolSuspended.class)).hasSize(1);
        api.get(base, root.accessToken())
                .andExpect(jsonPath("$.school.status").value("SUSPENDED"))
                .andExpect(jsonPath("$.suspension.reason").value(org.hamcrest.Matchers.containsString("automatically")));

        // The Super Admin reactivates (on day 31): back to past due, and a whole new grace period.
        api.post(base + "/reactivate", root.accessToken(), "{}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.school.status").value("PAST_DUE"));
        execute("update billing.subscription set grace_restarted_on = ? where tenant_id = ?", today.plusDays(31));
        assertThat(run(32).statusAfter()).isEqualTo(TenantStatus.PAST_DUE);
        assertThat(run(46).statusAfter()).isEqualTo(TenantStatus.PAST_DUE);
        assertThat(run(47).statusAfter()).isEqualTo(TenantStatus.SUSPENDED);
        api.post(base + "/reactivate", root.accessToken(), "{}").andExpect(status().isOk());

        // Paying the invoice makes the school active again at once.
        billing.pay(root, school.tenantId(), invoiceId);
        api.get(base, root.accessToken()).andExpect(jsonPath("$.school.status").value("ACTIVE"));
        assertThat(run(60).statusAfter()).isEqualTo(TenantStatus.ACTIVE);

        Session admin = api.login(school);
        api.get("/api/audit-events?limit=50", admin.accessToken())
                .andExpect(jsonPath("$[?(@.action == 'school.status_changed')].details.reason")
                        .value(hasItem("invoice_overdue")))
                .andExpect(jsonPath("$[?(@.action == 'school.status_changed')].details.reason").value(hasItem("settled")))
                .andExpect(jsonPath("$[?(@.action == 'school.suspended')].details.automatic").value(hasItem(true)));
    }

    @Test
    void aLapsedTrialBecomesPastDueThenSuspendedUnlessTheSchoolStartsPaying() throws Exception {
        // Signed up today: a 14-day trial.
        assertThat(run(13).changed()).isFalse();
        assertThat(run(15).statusAfter()).isEqualTo(TenantStatus.PAST_DUE);
        Session admin = api.login(school);
        api.get("/api/billing/notice", admin.accessToken()).andExpect(jsonPath("$.kind").value("PAYMENT_OVERDUE"));
        // Seven grace days after the trial's last day.
        assertThat(run(21).statusAfter()).isEqualTo(TenantStatus.PAST_DUE);
        assertThat(run(22).statusAfter()).isEqualTo(TenantStatus.SUSPENDED);
        assertThat(events.stream(SchoolSuspended.class)).hasSize(1);
    }

    @Test
    void aLapsedTrialThatStartsPayingIsActiveAgain() throws Exception {
        assertThat(run(15).statusAfter()).isEqualTo(TenantStatus.PAST_DUE);
        billing.start(root, school.tenantId(), "STARTER", "YEARLY", 20, null);
        api.get(base, root.accessToken()).andExpect(jsonPath("$.school.status").value("ACTIVE"));
        assertThat(run(0).changed()).isFalse();
    }

    @Test
    void aSchoolMarkedPastDueByHandIsNotMadeActiveByTheJob() throws Exception {
        billing.start(root, school.tenantId(), "GROWTH", "YEARLY", 50, null);
        api.post(base + "/status", root.accessToken(), """
                {"status":"PAST_DUE"}""").andExpect(status().isOk());
        BillingRenewals.Outcome outcome = run(1);
        assertThat(outcome.statusAfter()).isEqualTo(TenantStatus.PAST_DUE);
        assertThat(outcome.changed()).isFalse();
        // Nothing is overdue yet, so there is nothing to suspend it for either.
        assertThat(run(10).statusAfter()).isEqualTo(TenantStatus.PAST_DUE);
        assertThat(run(10).changed()).isFalse();
    }

    @Test
    void aSuspendedSchoolIsLeftAlone() throws Exception {
        api.post(base + "/suspend", root.accessToken(), """
                {"reason":"Asked to pause"}""").andExpect(status().isOk());
        BillingRenewals.Outcome outcome = run(400);
        assertThat(outcome.changed()).isFalse();
        assertThat(outcome.statusAfter()).isEqualTo(TenantStatus.SUSPENDED);
    }

    @Test
    void onlyOneInstanceRunsARoundAtATime() throws Exception {
        try (Connection other = ownerConnection();
                PreparedStatement lock = other.prepareStatement(
                        "select pg_advisory_lock(hashtextextended(?, 0))")) {
            lock.setString(1, BillingRenewals.LOCK_KEY);
            lock.execute();
            BillingRenewals.Round round = renewals.runAll(today, now);
            assertThat(round.ran()).isFalse();
            // The scheduled job with a fixed clock skips the round too.
            BillingRenewalJob job = new BillingRenewalJob(renewals,
                    Clock.fixed(Instant.parse("2027-01-15T00:00:00Z"), ZoneOffset.UTC));
            assertThat(job.run().ran()).isFalse();
        }
    }

    // ------------------------------------------------------------------ helpers

    private long count(String sql) throws Exception {
        try (Connection c = ownerConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, school.tenantId());
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private void execute(String sql, LocalDate day) throws Exception {
        try (Connection c = ownerConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setDate(1, Date.valueOf(day));
            ps.setObject(2, school.tenantId());
            ps.executeUpdate();
        }
    }
}
