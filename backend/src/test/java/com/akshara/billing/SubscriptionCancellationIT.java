package com.akshara.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.akshara.platform.TenantStatus;
import com.akshara.support.BillingFixtures;
import com.akshara.support.IntegrationTest;
import com.akshara.support.TestApi;
import com.akshara.support.TestApi.School;
import com.akshara.support.TestApi.Session;

/**
 * Cancellations, completed (docs/api/phase-3-subscriptions.md, sections 4 and 5): the Super Admin cancels or takes a
 * cancellation back for a school, or ends its subscription at once; a school on its free trial cancels and is
 * suspended when the trial ends instead of being chased as past due; the renewals list shows schools that are
 * cancelling and downgrades waiting for the renewal; and the school's admins are emailed about each change and before
 * the renewal.
 */
class SubscriptionCancellationIT extends IntegrationTest {

    @Autowired
    BillingRenewals renewals;

    private BillingFixtures billing;
    private Session root;
    private School school;
    private Session admin;
    private String account;
    private LocalDate today;
    private Instant now;

    @BeforeEach
    void school() throws Exception {
        billing = new BillingFixtures(api);
        root = billing.root();
        school = api.signup();
        admin = api.login(school);
        account = BillingFixtures.school(school.tenantId());
        today = BillingDay.today();
        now = Instant.now();
        billing.details(root, school.tenantId(), "36", null);
    }

    private BillingRenewals.Outcome run(int daysAhead) {
        return renewals.runFor(school.tenantId(), today.plusDays(daysAhead), now.plus(Duration.ofDays(daysAhead)));
    }

    private long emails(String template) throws Exception {
        String body = api.get("/api/messages?relatedId=" + school.tenantId() + "&size=100", admin.accessToken())
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return body.split("\"templateKey\":\"" + template + "\"", -1).length - 1;
    }

    @Test
    void theSuperAdminCancelsForTheSchoolAndTakesItBackAndBothAreAuditedAndEmailed() throws Exception {
        billing.start(root, school.tenantId(), "GROWTH", "YEARLY", 100, today.minusDays(30).toString());
        LocalDate periodEnd = today.minusDays(30).plusYears(1).minusDays(1);

        // The reason is required from the Super Admin.
        api.post(account + "/cancel", root.accessToken(), """
                {"reason":" ","immediate":false}""").andExpect(status().isBadRequest());
        api.post(account + "/cancel", root.accessToken(), """
                {"reason":"Principal phoned: closing the branch","immediate":false}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.school.status").value("ACTIVE"))
                .andExpect(jsonPath("$.subscription.cancelAtPeriodEnd").value(true))
                .andExpect(jsonPath("$.cancellation.endsOn").value(periodEnd.toString()))
                .andExpect(jsonPath("$.cancellation.reason").value("Principal phoned: closing the branch"))
                .andExpect(jsonPath("$.cancellation.trial").value(false));
        // Again: nothing more.
        api.post(account + "/cancel", root.accessToken(), """
                {"reason":"twice","immediate":false}""")
                .andExpect(status().isOk()).andExpect(jsonPath("$.cancellation.reason")
                        .value("Principal phoned: closing the branch"));

        api.get("/api/billing", admin.accessToken())
                .andExpect(jsonPath("$.cancellation.endsOn").value(periodEnd.toString()))
                .andExpect(jsonPath("$.cancellation.requestedByName").exists());
        api.get("/api/platform/billing/renewals", root.accessToken())
                .andExpect(jsonPath("$[?(@.tenantId == '" + school.tenantId() + "')].kind").value(hasItem("CANCELLING")))
                .andExpect(jsonPath("$[?(@.tenantId == '" + school.tenantId() + "')].note")
                        .value(hasItem("Principal phoned: closing the branch")));
        api.get("/api/audit-events?action=subscription.cancel_scheduled", admin.accessToken())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].details.onBehalf").value(true))
                .andExpect(jsonPath("$[0].details.endsOn").value(periodEnd.toString()));
        assertThat(emails("billing.cancel_scheduled")).isEqualTo(1);

        api.post(account + "/resume", root.accessToken(), "{}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subscription.cancelAtPeriodEnd").value(false))
                .andExpect(jsonPath("$.cancellation").doesNotExist());
        api.get("/api/audit-events?action=subscription.cancel_withdrawn", admin.accessToken())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].details.onBehalf").value(true));
        assertThat(emails("billing.cancel_withdrawn")).isEqualTo(1);
        api.get("/api/platform/billing/renewals", root.accessToken())
                .andExpect(jsonPath("$[?(@.tenantId == '" + school.tenantId() + "')].kind")
                        .value(not(hasItem("CANCELLING"))));
    }

    @Test
    void theSuperAdminEndsASubscriptionAtOnceAndReactivatingBringsTheSchoolBack() throws Exception {
        billing.start(root, school.tenantId(), "GROWTH", "YEARLY", 100, null);
        api.post(account + "/cancel", root.accessToken(), """
                {"reason":"School closed down","immediate":true}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.school.status").value("SUSPENDED"))
                .andExpect(jsonPath("$.suspension.reason").value("Subscription ended by Akshara: School closed down"));
        api.post("/api/auth/login", null, """
                {"schoolCode":"%s","email":"%s","password":"%s"}"""
                .formatted(school.code(), school.adminEmail(), TestApi.PASSWORD))
                .andExpect(status().is4xxClientError());
        // Already suspended: ending again is refused.
        api.post(account + "/cancel", root.accessToken(), """
                {"reason":"again","immediate":true}""").andExpect(status().isConflict());

        api.post(account + "/reactivate", root.accessToken(), "{}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.school.status").value("ACTIVE"));
        Session back = api.login(school);
        api.get("/api/audit-events?action=subscription.ended", back.accessToken())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].details.immediate").value(true))
                .andExpect(jsonPath("$[0].details.reason").value("School closed down"));
        api.get("/api/audit-events?action=school.suspended", back.accessToken())
                .andExpect(jsonPath("$[0].details.cancelled").value(true));
        admin = back;
        assertThat(emails("billing.suspended")).isEqualTo(1);
        assertThat(emails("billing.reactivated")).isEqualTo(1);
    }

    @Test
    void aSchoolOnTrialCancelsAndIsSuspendedWhenTheTrialEndsWithoutBeingPastDue() throws Exception {
        api.post("/api/billing/cancel", admin.accessToken(), """
                {"reason":"Chose another product"}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("TRIAL"))
                .andExpect(jsonPath("$.cancellation.trial").value(true))
                .andExpect(jsonPath("$.cancellation.reason").value("Chose another product"))
                .andExpect(jsonPath("$.cancellation.endsOn").exists());
        api.get("/api/platform/billing/renewals", root.accessToken())
                .andExpect(jsonPath("$[?(@.tenantId == '" + school.tenantId() + "')].kind").value(hasItem("CANCELLING")))
                .andExpect(jsonPath("$[?(@.tenantId == '" + school.tenantId() + "')].kind")
                        .value(not(hasItem("TRIAL_ENDING"))));

        // Signed up today: a 14-day trial. Nothing happens before it ends, and no trial reminder is sent.
        assertThat(run(10).changed()).isFalse();
        assertThat(run(13).changed()).isFalse();
        assertThat(emails("billing.trial_ending")).isZero();
        BillingRenewals.Outcome ended = run(15);
        assertThat(ended.statusBefore()).isEqualTo(TenantStatus.TRIAL);
        assertThat(ended.statusAfter()).isEqualTo(TenantStatus.SUSPENDED);
        api.get(account, root.accessToken())
                .andExpect(jsonPath("$.school.status").value("SUSPENDED"))
                .andExpect(jsonPath("$.suspension.reason").value(org.hamcrest.Matchers.startsWith(
                        "The school cancelled its free trial")));
        api.post(account + "/reactivate", root.accessToken(), "{}").andExpect(status().isOk());
        Session back = api.login(school);
        api.get("/api/audit-events?action=subscription.ended", back.accessToken())
                .andExpect(jsonPath("$[0].details.trial").value(true));
        api.get("/api/audit-events?action=school.status_changed", back.accessToken())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void aTrialThatCancelledAndThenStartsPayingCarriesOn() throws Exception {
        api.post("/api/billing/cancel", admin.accessToken(), "{}").andExpect(status().isOk());
        api.post("/api/billing/resume", admin.accessToken(), "{}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.cancellation").doesNotExist());
        api.post("/api/billing/cancel", admin.accessToken(), "{}").andExpect(status().isOk());

        billing.start(root, school.tenantId(), "STARTER", "YEARLY", 20, null);
        api.get(account, root.accessToken())
                .andExpect(jsonPath("$.school.status").value("ACTIVE"))
                .andExpect(jsonPath("$.cancellation").doesNotExist());
        api.get("/api/audit-events?action=subscription.started", admin.accessToken())
                .andExpect(jsonPath("$[0].details.cancellationWithdrawn").value(true));
        assertThat(run(16).statusAfter()).isNotEqualTo(TenantStatus.SUSPENDED);
    }

    @Test
    void aSuspendedSchoolCannotBeCancelledOrResumedAndOtherRolesAndSchoolsAreRefused() throws Exception {
        billing.start(root, school.tenantId(), "GROWTH", "YEARLY", 100, null);
        School other = api.signup();
        Session otherAdmin = api.login(other);
        api.post(account + "/cancel", otherAdmin.accessToken(), """
                {"reason":"x","immediate":false}""").andExpect(status().isForbidden());
        api.post(account + "/resume", admin.accessToken(), "{}").andExpect(status().isForbidden());
        api.post("/api/platform/billing/schools/00000000-0000-7000-8000-000000000000/cancel", root.accessToken(), """
                {"reason":"x","immediate":false}""").andExpect(status().isNotFound());

        api.post(account + "/suspend", root.accessToken(), """
                {"reason":"Test"}""").andExpect(status().isOk());
        api.post(account + "/cancel", root.accessToken(), """
                {"reason":"x","immediate":false}""").andExpect(status().isConflict());
        api.post(account + "/resume", root.accessToken(), "{}").andExpect(status().isConflict());
    }

    @Test
    void aScheduledDowngradeIsListedForTheRenewalAndTheRenewalReminderIsEmailedOnce() throws Exception {
        // A monthly Growth subscription whose period ends in five days.
        LocalDate start = today.plusDays(5).minusMonths(1).plusDays(1);
        billing.start(root, school.tenantId(), "GROWTH", "MONTHLY", 100, start.toString());
        api.post("/api/billing/plan", admin.accessToken(), """
                {"plan":"STARTER"}""").andExpect(status().isOk());
        api.get("/api/platform/billing/renewals", root.accessToken())
                .andExpect(jsonPath("$[?(@.tenantId == '" + school.tenantId() + "')].kind")
                        .value(hasItem("DOWNGRADE_SCHEDULED")))
                .andExpect(jsonPath("$[?(@.tenantId == '" + school.tenantId() + "')].pendingPlan")
                        .value(hasItem("STARTER")));

        assertThat(emails("billing.renewal_reminder")).isZero();
        run(0);
        run(0);
        run(1);
        assertThat(emails("billing.renewal_reminder")).isEqualTo(1);
        // The first invoice was emailed when it was issued.
        assertThat(emails("billing.invoice_issued")).isEqualTo(1);
    }

    @Test
    void aPaymentAndAPastDueAreEmailedToTheSchoolAdmins() throws Exception {
        String invoiceId = billing.start(root, school.tenantId(), "GROWTH", "YEARLY", 50, null);
        assertThat(run(16).statusAfter()).isEqualTo(TenantStatus.PAST_DUE);
        assertThat(emails("billing.past_due")).isEqualTo(1);
        billing.pay(root, school.tenantId(), invoiceId);
        assertThat(emails("billing.invoice_paid")).isEqualTo(1);
        api.get("/api/messages?relatedId=" + school.tenantId() + "&size=100", admin.accessToken())
                .andExpect(jsonPath("$.items[?(@.templateKey == 'billing.invoice_paid')].channel").value(hasItem("EMAIL")))
                .andExpect(jsonPath("$.items[?(@.templateKey == 'billing.invoice_paid')].relatedType")
                        .value(hasItem("school")));
    }
}
