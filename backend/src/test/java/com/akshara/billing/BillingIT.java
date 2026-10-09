package com.akshara.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;

import com.akshara.support.BillingFixtures;
import com.akshara.support.FeeFixtures;
import com.akshara.support.IntegrationTest;
import com.akshara.support.TestApi;
import com.akshara.support.TestApi.School;
import com.akshara.support.TestApi.Session;

/**
 * The Super Admin's billing console against a real database: converting a trial, GST on invoices, payments,
 * cancellations, renewals, plan changes, status changes, the school's own Billing page and banner, and who may see
 * what.
 */
@RecordApplicationEvents
class BillingIT extends IntegrationTest {

    private static final String INVOICE_NO = "^AKS/\\d{2}-\\d{2}/\\d{6}$";

    @Autowired
    ApplicationEvents events;

    private BillingFixtures billing;
    private Session root;
    private School school;
    private Session admin;
    private String base;
    private LocalDate today;

    @BeforeEach
    void schoolOnTrial() throws Exception {
        billing = new BillingFixtures(api);
        root = billing.root();
        school = api.signup();
        admin = api.login(school);
        base = BillingFixtures.school(school.tenantId());
        today = FeeFixtures.today();
    }

    @Test
    void convertingATrialNeedsTheStateThenIssuesAnInvoiceWithCgstAndSgstInTheSellersState() throws Exception {
        api.get(base, root.accessToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.school.status").value("TRIAL"))
                .andExpect(jsonPath("$.subscription").isEmpty())
                .andExpect(jsonPath("$.details.stateCode").isEmpty())
                .andExpect(jsonPath("$.invoices.length()").value(0));
        String start = """
                {"plan":"GROWTH","billingCycle":"YEARLY","billedStudents":250}""";
        api.post(base + "/subscription", root.accessToken(), start)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.stateCode").exists());

        billing.details(root, school.tenantId(), "36", null);
        api.post(base + "/subscription", root.accessToken(), """
                {"plan":"STARTER","billingCycle":"YEARLY","billedStudents":301}""")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.billedStudents").value("The Starter plan covers up to 300 students."));
        api.post(base + "/subscription", root.accessToken(), """
                {"plan":"GROWTH","billingCycle":"YEARLY","billedStudents":250,"periodStart":"%s"}"""
                .formatted(today.minusYears(2)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.periodStart").exists());

        String invoiceId = TestApi.read(api.post(base + "/subscription", root.accessToken(), start)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.school.status").value("ACTIVE"))
                .andExpect(jsonPath("$.school.plan").value("GROWTH"))
                .andExpect(jsonPath("$.subscription.billingCycle").value("YEARLY"))
                .andExpect(jsonPath("$.subscription.billedStudents").value(250))
                .andExpect(jsonPath("$.subscription.periodStart").value(today.toString()))
                .andExpect(jsonPath("$.subscription.periodEnd").value(today.plusYears(1).minusDays(1).toString()))
                .andExpect(jsonPath("$.subscription.nextRenewalOn").value(today.plusYears(1).toString()))
                .andExpect(jsonPath("$.subscription.unitPricePaise").value(200_00))
                .andExpect(jsonPath("$.subscription.nextInvoiceTaxablePaise").value(50_000_00))
                .andExpect(jsonPath("$.invoices.length()").value(1))
                .andExpect(jsonPath("$.unpaidPaise").value(59_000_00)), "$.invoices[0].id");

        // 250 students × ₹200 = ₹50,000; CGST 9% + SGST 9% = ₹9,000; ₹59,000 in all.
        api.get(base + "/invoices/" + invoiceId, root.accessToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.invoiceNo", matchesPattern(INVOICE_NO)))
                .andExpect(jsonPath("$.financialYear").value(GstMath.financialYear(today)))
                .andExpect(jsonPath("$.invoiceDate").value(today.toString()))
                .andExpect(jsonPath("$.dueDate").value(today.plusDays(15).toString()))
                .andExpect(jsonPath("$.seller.name").value("Akshara School Cloud (sample seller)"))
                .andExpect(jsonPath("$.seller.stateCode").value("36"))
                .andExpect(jsonPath("$.seller.gstin").value(BillingProperties.PLACEHOLDER_GSTIN))
                .andExpect(jsonPath("$.sample").value(true))
                .andExpect(jsonPath("$.buyer.name").value("Test School Trust"))
                .andExpect(jsonPath("$.buyer.stateCode").value("36"))
                .andExpect(jsonPath("$.buyer.gstin").isEmpty())
                .andExpect(jsonPath("$.sacCode").value("998315"))
                .andExpect(jsonPath("$.taxSplit").value("CGST_SGST"))
                .andExpect(jsonPath("$.taxablePaise").value(50_000_00))
                .andExpect(jsonPath("$.cgstRateBp").value(900))
                .andExpect(jsonPath("$.cgstPaise").value(4_500_00))
                .andExpect(jsonPath("$.sgstPaise").value(4_500_00))
                .andExpect(jsonPath("$.igstPaise").value(0))
                .andExpect(jsonPath("$.totalPaise").value(59_000_00))
                .andExpect(jsonPath("$.amountInWords").value("Rupees Fifty Nine Thousand Only"))
                .andExpect(jsonPath("$.status").value("ISSUED"))
                .andExpect(jsonPath("$.issuedByName").value("Test Platform Admin"));

        // Starting twice is refused; the school's own trail has both entries, by the Super Admin.
        api.post(base + "/subscription", root.accessToken(), start).andExpect(status().isConflict());
        api.get("/api/audit-events?limit=20", admin.accessToken())
                .andExpect(jsonPath("$[?(@.action == 'subscription.started')].actorName")
                        .value(hasItem("Test Platform Admin")))
                .andExpect(jsonPath("$[*].action", hasItems("invoice.issued", "billing_details.updated")));
        assertThat(events.stream(SubscriptionInvoiceIssued.class)).extracting(SubscriptionInvoiceIssued::invoiceId)
                .contains(UUID.fromString(invoiceId));
    }

    @Test
    void aSchoolInAnotherStatePaysIgstAndItsGstinIsChecked() throws Exception {
        String valid27 = BillingFixtures.gstin("27");
        String wrongCheck = valid27.substring(0, 14) + (valid27.charAt(14) == 'A' ? 'B' : 'A');
        api.put(base + "/details", root.accessToken(), """
                {"stateCode":"27","gstin":"%s"}""".formatted(wrongCheck))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.gstin").value("This GSTIN is not valid. Check its 15 characters."));
        api.put(base + "/details", root.accessToken(), """
                {"stateCode":"27","gstin":"%s"}""".formatted(BillingFixtures.gstin("29")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.gstin", containsString("another state")));
        api.put(base + "/details", root.accessToken(), """
                {"stateCode":"25"}""")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.stateCode").exists());
        String gstin = BillingFixtures.gstin("27");
        api.put(base + "/details", root.accessToken(), """
                {"legalName":"  ","address":"Pune","stateCode":"27","gstin":"%s"}""".formatted(gstin.toLowerCase()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.details.gstin").value(gstin))
                .andExpect(jsonPath("$.details.legalName").isEmpty());

        String invoiceId = billing.start(root, school.tenantId(), "STARTER", "MONTHLY", 120, null);
        // 120 students × ₹8.30 a month = ₹996; IGST 18% = ₹179.28.
        api.get(base + "/invoices/" + invoiceId, root.accessToken())
                .andExpect(jsonPath("$.taxSplit").value("IGST"))
                .andExpect(jsonPath("$.buyer.name").value("Test School " + school.code()))
                .andExpect(jsonPath("$.buyer.gstin").value(gstin))
                .andExpect(jsonPath("$.unitPricePaise").value(8_30))
                .andExpect(jsonPath("$.taxablePaise").value(996_00))
                .andExpect(jsonPath("$.cgstPaise").value(0))
                .andExpect(jsonPath("$.sgstPaise").value(0))
                .andExpect(jsonPath("$.igstRateBp").value(1800))
                .andExpect(jsonPath("$.igstPaise").value(179_28))
                .andExpect(jsonPath("$.totalPaise").value(1_175_28))
                .andExpect(jsonPath("$.amountInWords")
                        .value("Rupees One Thousand One Hundred Seventy Five and Twenty Eight Paise Only"))
                .andExpect(jsonPath("$.periodEnd").value(today.plusMonths(1).minusDays(1).toString()));
    }

    @Test
    void paymentsAreRecordedByHandUntilTheInvoiceIsPaid() throws Exception {
        String invoiceId = billing.paying(root, school.tenantId(), "36");
        String pay = base + "/invoices/" + invoiceId + "/payments";
        // ₹20,000 + 18% = ₹23,600.
        api.post(pay, root.accessToken(), """
                {"amountPaise":2360001,"mode":"UPI","reference":"UPI-1","paidOn":"%s"}""".formatted(today))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.amountPaise").value("This is more than the ₹23,600 still due."));
        api.post(pay, root.accessToken(), """
                {"amountPaise":1000000,"mode":"UPI","reference":"UPI-1","paidOn":"%s"}""".formatted(today.plusDays(1)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.paidOn").exists());
        api.post(pay, root.accessToken(), """
                {"amountPaise":1000000,"mode":"UPI","reference":"","paidOn":"%s"}""".formatted(today))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.reference").exists());
        api.post(pay, root.accessToken(), """
                {"amountPaise":1000000,"mode":"UPI","reference":"UPI-1","paidOn":"%s"}""".formatted(today))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ISSUED"))
                .andExpect(jsonPath("$.paidPaise").value(10_000_00))
                .andExpect(jsonPath("$.balancePaise").value(13_600_00));
        assertThat(events.stream(SubscriptionInvoicePaid.class)).isEmpty();
        // Without an amount, the rest is paid.
        api.post(pay, root.accessToken(), """
                {"mode":"CHEQUE","reference":"CHQ 445566","paidOn":"%s"}""".formatted(today))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAID"))
                .andExpect(jsonPath("$.paidPaise").value(23_600_00))
                .andExpect(jsonPath("$.balancePaise").value(0))
                .andExpect(jsonPath("$.paidOn").value(today.toString()))
                .andExpect(jsonPath("$.payments.length()").value(2))
                .andExpect(jsonPath("$.payments[1].mode").value("CHEQUE"))
                .andExpect(jsonPath("$.payments[1].recordedByName").value("Test Platform Admin"));
        assertThat(events.stream(SubscriptionInvoicePaid.class)).hasSize(1);

        api.post(pay, root.accessToken(), """
                {"mode":"UPI","reference":"UPI-2","paidOn":"%s"}""".formatted(today))
                .andExpect(status().isConflict());
        api.post(base + "/invoices/" + invoiceId + "/cancel", root.accessToken(), """
                {"reason":"Wrong"}""")
                .andExpect(status().isConflict());
        api.get(base, root.accessToken())
                .andExpect(jsonPath("$.unpaidPaise").value(0))
                .andExpect(jsonPath("$.invoices[0].status").value("PAID"));
        api.get("/api/audit-events?limit=20", admin.accessToken())
                .andExpect(jsonPath("$[?(@.action == 'invoice.payment_recorded')].details.amountPaise",
                        hasItems(10_000_00, 13_600_00)));
    }

    @Test
    void aCancelledInvoiceKeepsItsNumberAndThePeriodIsInvoicedAgainWithTheNewTerms() throws Exception {
        String first = billing.paying(root, school.tenantId(), "36");
        String firstNo = TestApi.read(api.get(base + "/invoices/" + first, root.accessToken()), "$.invoiceNo");
        api.post(base + "/invoices/" + first + "/cancel", root.accessToken(), """
                {"reason":""}""")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.reason").exists());
        api.post(base + "/invoices/" + first + "/cancel", root.accessToken(), """
                {"reason":"Billed the wrong number of students"}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.balancePaise").value(0))
                .andExpect(jsonPath("$.cancelReason").value("Billed the wrong number of students"));
        api.post(base + "/invoices/" + first + "/cancel", root.accessToken(), """
                {"reason":"Again"}""")
                .andExpect(status().isConflict());
        api.post(base + "/invoices/" + first + "/payments", root.accessToken(), """
                {"mode":"UPI","reference":"UPI-1","paidOn":"%s"}""".formatted(today))
                .andExpect(status().isConflict());

        // Fix the terms, then invoice the same period again: a new number, monthly now.
        api.put(base + "/subscription", root.accessToken(), """
                {"plan":"GROWTH","billingCycle":"MONTHLY","billedStudents":80}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subscription.billingCycle").value("MONTHLY"))
                .andExpect(jsonPath("$.subscription.nextInvoiceTaxablePaise").value(1_600_00));
        String second = TestApi.read(api.post(base + "/invoices", root.accessToken(), null)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.periodStart").value(today.toString()))
                .andExpect(jsonPath("$.periodEnd").value(today.plusMonths(1).minusDays(1).toString()))
                .andExpect(jsonPath("$.billedStudents").value(80))
                .andExpect(jsonPath("$.taxablePaise").value(1_600_00))
                .andExpect(jsonPath("$.invoiceNo", not(firstNo))), "$.id");
        assertThat(second).isNotEqualTo(first);
        api.get(base, root.accessToken())
                .andExpect(jsonPath("$.invoices.length()").value(2))
                .andExpect(jsonPath("$.subscription.periodEnd").value(today.plusMonths(1).minusDays(1).toString()))
                .andExpect(jsonPath("$.invoices[*].status", hasItems("ISSUED", "CANCELLED")));
    }

    @Test
    void theNextPeriodIsInvoicedOnlyFromThirtyDaysBeforeItStarts() throws Exception {
        billing.paying(root, school.tenantId(), "36");
        api.post(base + "/invoices", root.accessToken(), null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail", containsString("Its invoice can be issued from")));

        // Move the current period, and its invoice, back so that it ends in ten days.
        LocalDate end = today.plusDays(10);
        try (Connection owner = ownerConnection()) {
            for (String table : List.of("billing.subscription", "billing.invoice")) {
                try (PreparedStatement s = owner.prepareStatement(
                        "update " + table + " set period_start = ?, period_end = ? where tenant_id = ?")) {
                    s.setObject(1, end.minusYears(1).plusDays(1));
                    s.setObject(2, end);
                    s.setObject(3, school.tenantId());
                    s.executeUpdate();
                }
            }
        }
        api.get("/api/platform/billing/renewals", root.accessToken())
                .andExpect(jsonPath("$[?(@.tenantId == '%s' && @.kind == 'DUE_FOR_RENEWAL')].days"
                        .formatted(school.tenantId())).value(hasItem(11)))
                .andExpect(jsonPath("$[?(@.tenantId == '%s' && @.kind == 'DUE_FOR_RENEWAL')].amountPaise"
                        .formatted(school.tenantId())).value(hasItem(23_600_00)));
        api.post(base + "/invoices", root.accessToken(), null)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.periodStart").value(end.plusDays(1).toString()))
                .andExpect(jsonPath("$.periodEnd").value(end.plusYears(1).toString()))
                .andExpect(jsonPath("$.dueDate").value(end.plusDays(15).isAfter(today.plusDays(15))
                        ? today.plusDays(15).toString() : end.plusDays(1).toString()));
        api.get(base, root.accessToken())
                .andExpect(jsonPath("$.subscription.nextRenewalOn").value(end.plusYears(1).plusDays(1).toString()));
        // Renewed: no longer due.
        api.get("/api/platform/billing/renewals", root.accessToken())
                .andExpect(jsonPath("$[?(@.tenantId == '%s' && @.kind == 'DUE_FOR_RENEWAL')]"
                        .formatted(school.tenantId())).isEmpty());
    }

    @Test
    void aTrialSchoolChangesOnlyItsPlanAndAPayingSchoolChangesFromItsNextInvoice() throws Exception {
        api.put(base + "/subscription", root.accessToken(), """
                {"plan":"ENTERPRISE"}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.school.plan").value("ENTERPRISE"))
                .andExpect(jsonPath("$.school.status").value("TRIAL"))
                .andExpect(jsonPath("$.subscription").isEmpty());
        api.get("/api/me", admin.accessToken()).andExpect(jsonPath("$.tenant.plan").value("ENTERPRISE"));

        String invoiceId = billing.paying(root, school.tenantId(), "36");
        api.put(base + "/subscription", root.accessToken(), """
                {"plan":"STARTER"}""")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.billingCycle").exists());
        api.put(base + "/subscription", root.accessToken(), """
                {"plan":"STARTER","billingCycle":"YEARLY","billedStudents":400}""")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.billedStudents").exists());
        api.put(base + "/subscription", root.accessToken(), """
                {"plan":"ENTERPRISE","billingCycle":"YEARLY","billedStudents":120}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.school.plan").value("ENTERPRISE"))
                .andExpect(jsonPath("$.subscription.billedStudents").value(120))
                .andExpect(jsonPath("$.subscription.nextInvoiceTaxablePaise").value(60_000_00));
        // The issued invoice is unchanged.
        api.get(base + "/invoices/" + invoiceId, root.accessToken())
                .andExpect(jsonPath("$.plan").value("GROWTH"))
                .andExpect(jsonPath("$.billedStudents").value(100));
        api.get("/api/audit-events?limit=30", admin.accessToken())
                .andExpect(jsonPath("$[?(@.action == 'subscription.changed')].details.plan", hasItem("ENTERPRISE")));
    }

    @Test
    void statusChangesAreManualAndLimitedToActiveAndPastDue() throws Exception {
        api.post(base + "/status", root.accessToken(), """
                {"status":"PAST_DUE"}""")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail", containsString("trial")));
        billing.paying(root, school.tenantId(), "36");
        api.post(base + "/status", root.accessToken(), """
                {"status":"SUSPENDED"}""")
                .andExpect(status().isBadRequest());
        api.post(base + "/status", root.accessToken(), """
                {"status":"TRIAL"}""")
                .andExpect(status().isBadRequest());
        api.post(base + "/status", root.accessToken(), """
                {"status":"PAST_DUE"}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.school.status").value("PAST_DUE"))
                .andExpect(jsonPath("$.notice.kind").value("PAYMENT_OVERDUE"));
        // Past due changes nothing for the school's people: they still sign in and work.
        Session again = api.login(school);
        api.get("/api/billing/notice", again.accessToken())
                .andExpect(jsonPath("$.kind").value("PAYMENT_OVERDUE"));
        api.post(base + "/status", root.accessToken(), """
                {"status":"ACTIVE"}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.school.status").value("ACTIVE"))
                .andExpect(jsonPath("$.notice.kind").isEmpty());
        api.get("/api/audit-events?limit=30", admin.accessToken())
                .andExpect(jsonPath("$[?(@.action == 'school.status_changed')].details.status",
                        hasItems("PAST_DUE", "ACTIVE")));
    }

    @Test
    void theSchoolSeesItsOwnBillingAndInvoicesButNeverAnotherSchools() throws Exception {
        api.get("/api/billing", admin.accessToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("TRIAL"))
                .andExpect(jsonPath("$.plan").value("STARTER"))
                .andExpect(jsonPath("$.trialEndsAt").isNotEmpty())
                .andExpect(jsonPath("$.trialDaysLeft").value(14))
                .andExpect(jsonPath("$.plans.length()").value(3))
                .andExpect(jsonPath("$.plans[1].plan").value("GROWTH"))
                .andExpect(jsonPath("$.plans[1].pricePerStudentPerYearPaise").value(200_00))
                .andExpect(jsonPath("$.plans[1].schools").isEmpty())
                .andExpect(jsonPath("$.subscription").isEmpty())
                .andExpect(jsonPath("$.invoices.length()").value(0))
                .andExpect(jsonPath("$.notice.kind").isEmpty());

        // The School Admin keeps the school's billing details; they are used from the next invoice.
        api.put("/api/billing/details", admin.accessToken(), """
                {"legalName":"Own Trust","address":"2 School Lane","stateCode":"36","gstin":"%s"}"""
                .formatted(BillingFixtures.gstin("36")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.details.legalName").value("Own Trust"));
        String invoiceId = billing.start(root, school.tenantId(), "GROWTH", "YEARLY", 50, null);
        api.get("/api/billing", admin.accessToken())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.trialDaysLeft").isEmpty())
                .andExpect(jsonPath("$.subscription.nextRenewalOn").value(today.plusYears(1).toString()))
                .andExpect(jsonPath("$.invoices[0].id").value(invoiceId))
                .andExpect(jsonPath("$.invoices[0].totalPaise").value(11_800_00));
        api.get("/api/billing/invoices/" + invoiceId, admin.accessToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.buyer.name").value("Own Trust"))
                .andExpect(jsonPath("$.buyer.gstin").value(BillingFixtures.gstin("36")));

        // Another school's admin gets 404 for this invoice, and its own page shows nothing of it.
        School other = api.signup();
        Session otherAdmin = api.login(other);
        api.get("/api/billing/invoices/" + invoiceId, otherAdmin.accessToken()).andExpect(status().isNotFound());
        api.get("/api/billing", otherAdmin.accessToken()).andExpect(jsonPath("$.invoices.length()").value(0));
        // The Super Admin also reads it only through its own school.
        api.get(BillingFixtures.school(other.tenantId()) + "/invoices/" + invoiceId, root.accessToken())
                .andExpect(status().isNotFound());
        api.post(BillingFixtures.school(other.tenantId()) + "/invoices/" + invoiceId + "/payments",
                root.accessToken(), """
                {"mode":"UPI","reference":"X","paidOn":"%s"}""".formatted(today))
                .andExpect(status().isNotFound());
        api.get(BillingFixtures.school(UUID.randomUUID()), root.accessToken()).andExpect(status().isNotFound());
    }

    @Test
    void onlySchoolAdminsSeeBillingAndOnlyTheSuperAdminReachesTheConsole() throws Exception {
        api.createUser(admin, "Tara Teacher", "tara@" + school.code() + ".akshara.test", List.of("TEACHER"));
        api.createUser(admin, "Prem Principal", "prem@" + school.code() + ".akshara.test", List.of("PRINCIPAL"));
        Session teacher = api.login(school.code(), "tara@" + school.code() + ".akshara.test", TestApi.PASSWORD);
        Session principal = api.login(school.code(), "prem@" + school.code() + ".akshara.test", TestApi.PASSWORD);
        api.get("/api/me", admin.accessToken()).andExpect(jsonPath("$.permissions", hasItem("billing.read")));
        api.get("/api/roles", admin.accessToken())
                .andExpect(jsonPath("$[?(@.code == 'SCHOOL_ADMIN')].permissions[*]", hasItem("billing.read")))
                .andExpect(jsonPath("$[?(@.code == 'PRINCIPAL')].permissions[*]", not(hasItem("billing.read"))));
        for (Session staff : List.of(teacher, principal)) {
            api.get("/api/billing", staff.accessToken()).andExpect(status().isForbidden());
            api.get("/api/billing/notice", staff.accessToken()).andExpect(status().isForbidden());
            api.put("/api/billing/details", staff.accessToken(), """
                    {"stateCode":"36"}""").andExpect(status().isForbidden());
        }
        String invoiceId = billing.paying(root, school.tenantId(), "36");
        api.get("/api/billing/invoices/" + invoiceId, teacher.accessToken()).andExpect(status().isForbidden());

        // School users, even admins, never reach the Super Admin's console; the Super Admin has no school page.
        for (Session user : List.of(admin, teacher)) {
            api.get("/api/platform/billing/plans", user.accessToken()).andExpect(status().isForbidden());
            api.get("/api/platform/billing/renewals", user.accessToken()).andExpect(status().isForbidden());
            api.get("/api/platform/health", user.accessToken()).andExpect(status().isForbidden());
            api.get(base, user.accessToken()).andExpect(status().isForbidden());
            api.post(base + "/suspend", user.accessToken(), """
                    {"reason":"x"}""").andExpect(status().isForbidden());
            api.post(base + "/invoices/" + invoiceId + "/payments", user.accessToken(), """
                    {"mode":"UPI","reference":"X","paidOn":"%s"}""".formatted(today))
                    .andExpect(status().isForbidden());
        }
        api.get("/api/platform/billing/plans", null).andExpect(status().isUnauthorized());
        api.get("/api/billing", root.accessToken()).andExpect(status().isForbidden());
    }

    @Test
    void thePlanCatalogueCountsSchoolsPerPlan() throws Exception {
        String before = TestApi.read(api.get("/api/platform/billing/plans", root.accessToken()), "$[1].schools");
        api.put(base + "/subscription", root.accessToken(), """
                {"plan":"GROWTH"}""").andExpect(status().isOk());
        api.get("/api/platform/billing/plans", root.accessToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].plan").value("STARTER"))
                .andExpect(jsonPath("$[0].maxStudents").value(300))
                .andExpect(jsonPath("$[0].features", hasItem("fees_online")))
                .andExpect(jsonPath("$[1].popular").value(true))
                .andExpect(jsonPath("$[1].pricePerStudentPerMonthPaise").value(20_00))
                .andExpect(jsonPath("$[1].schools").value(Integer.parseInt(before) + 1))
                .andExpect(jsonPath("$[2].maxStudents").isEmpty());
    }

    @Test
    void theBannerAndTheRenewalsListWarnAboutEndingTrialsAndOverdueInvoices() throws Exception {
        // A trial that ends in three days.
        try (Connection owner = ownerConnection(); PreparedStatement s = owner.prepareStatement(
                "update platform.tenant set trial_ends_at = ? where id = ?")) {
            s.setObject(1, java.sql.Timestamp.from(Instant.now().plus(3, ChronoUnit.DAYS).minusSeconds(60)));
            s.setObject(2, school.tenantId());
            s.executeUpdate();
        }
        api.get("/api/billing/notice", admin.accessToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kind").value("TRIAL_ENDING"))
                .andExpect(jsonPath("$.trialDaysLeft").value(3));
        api.get("/api/platform/billing/renewals", root.accessToken())
                .andExpect(jsonPath("$[?(@.tenantId == '%s')].kind".formatted(school.tenantId()))
                        .value(hasItem("TRIAL_ENDING")));

        // Paying, with the first invoice past its due date: overdue, and the status does not change by itself.
        String invoiceId = billing.paying(root, school.tenantId(), "36");
        try (Connection owner = ownerConnection(); PreparedStatement s = owner.prepareStatement(
                "update billing.invoice set invoice_date = ?, due_date = ? where id = ?")) {
            s.setObject(1, today.minusDays(40));
            s.setObject(2, today.minusDays(25));
            s.setObject(3, UUID.fromString(invoiceId));
            s.executeUpdate();
        }
        api.get("/api/billing/notice", admin.accessToken())
                .andExpect(jsonPath("$.kind").value("PAYMENT_OVERDUE"))
                .andExpect(jsonPath("$.overduePaise").value(23_600_00))
                .andExpect(jsonPath("$.overdueInvoices").value(1))
                .andExpect(jsonPath("$.oldestDueDate").value(today.minusDays(25).toString()));
        api.get("/api/platform/billing/renewals", root.accessToken())
                .andExpect(jsonPath("$[?(@.tenantId == '%s' && @.kind == 'OVERDUE')].days"
                        .formatted(school.tenantId())).value(hasItem(-25)))
                .andExpect(jsonPath("$[?(@.tenantId == '%s' && @.kind == 'OVERDUE')].amountPaise"
                        .formatted(school.tenantId())).value(hasItem(23_600_00)))
                .andExpect(jsonPath("$[?(@.tenantId == '%s')].kind".formatted(school.tenantId()))
                        .value(not(hasItem("TRIAL_ENDING"))));
        api.get(base, root.accessToken())
                .andExpect(jsonPath("$.school.status").value("ACTIVE"))
                .andExpect(jsonPath("$.overduePaise").value(23_600_00))
                .andExpect(jsonPath("$.invoices[0].overdue").value(true));
        api.get("/api/billing", admin.accessToken())
                .andExpect(jsonPath("$.notice.kind").value("PAYMENT_OVERDUE"));

        billing.pay(root, school.tenantId(), invoiceId);
        api.get("/api/billing/notice", admin.accessToken()).andExpect(jsonPath("$.kind").isEmpty());
        api.get("/api/platform/billing/renewals", root.accessToken())
                .andExpect(jsonPath("$[?(@.tenantId == '%s')]".formatted(school.tenantId())).isEmpty());
    }
}
