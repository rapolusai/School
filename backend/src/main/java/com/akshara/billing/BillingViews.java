package com.akshara.billing;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.akshara.platform.Plan;
import com.akshara.platform.TenantStatus;
import com.akshara.platform.TenantSummary;

/** Response bodies of the billing API (docs/api/phase-1-billing.md). Amounts are in paise. */
public final class BillingViews {

    private BillingViews() {
    }

    /**
     * A plan of the catalogue. Prices are per student, before GST; a month costs a tenth of a year. {@code schools} is
     * how many schools are on the plan (Super Admin only; null for schools).
     */
    public record PlanView(Plan plan, long pricePerStudentPerYearPaise, long pricePerStudentPerMonthPaise,
            Integer maxStudents, Integer maxBranches, List<String> features, List<String> notIncluded,
            boolean popular, Long schools) {

        static PlanView of(PlanCatalog.PlanInfo p, Long schools) {
            return new PlanView(p.plan(), p.pricePerStudentPerYearPaise(), p.unitPricePaise(BillingCycle.MONTHLY),
                    p.maxStudents(), p.maxBranches(), p.features(), p.notIncluded(), p.popular(), schools);
        }
    }

    /** The school as the buyer on its invoices. Every field is null until set. */
    public record BillingDetails(String legalName, String address, String stateCode, String gstin) {

        static final BillingDetails NONE = new BillingDetails(null, null, null, null);
    }

    /**
     * A paying school's subscription. {@code periodStart}–{@code periodEnd} is the latest period invoiced (the current
     * one, or the next once its renewal invoice is out); the next renewal is the day after it ends. The next invoice is
     * {@code billedStudents × unitPricePaise} before GST ({@code nextInvoiceTaxablePaise}), at the current plan and
     * cycle.
     */
    public record SubscriptionView(BillingCycle billingCycle, int billedStudents, LocalDate periodStart,
            LocalDate periodEnd, LocalDate nextRenewalOn, LocalDate paidSince, long unitPricePaise,
            long nextInvoiceTaxablePaise) {
    }

    public record Suspension(Instant suspendedAt, String reason) {
    }

    public record InvoiceSummary(UUID id, String invoiceNo, LocalDate invoiceDate, LocalDate dueDate,
            LocalDate periodStart, LocalDate periodEnd, Plan plan, BillingCycle billingCycle, long totalPaise,
            long paidPaise, long balancePaise, String status, boolean overdue) {
    }

    /** Seller or buyer on an invoice: name, address, two-digit GST state code and GSTIN (null when none). */
    public record Party(String name, String address, String stateCode, String gstin) {
    }

    public record PaymentView(UUID id, long amountPaise, PaymentMode mode, String reference, LocalDate paidOn,
            String recordedByName, Instant recordedAt) {
    }

    /**
     * A printable GST tax invoice. The place of supply is the buyer's state. Rates are in basis points (900 = 9%).
     * {@code sample} is true when the seller's GSTIN is not a valid GSTIN (the placeholder): such an invoice is not a
     * valid tax invoice.
     */
    public record InvoiceView(UUID id, String invoiceNo, String financialYear, LocalDate invoiceDate, LocalDate dueDate,
            Party seller, Party buyer, String sacCode, Plan plan, BillingCycle billingCycle, LocalDate periodStart,
            LocalDate periodEnd, int billedStudents, long unitPricePaise, long taxablePaise, GstMath.Split taxSplit,
            int cgstRateBp, int sgstRateBp, int igstRateBp, long cgstPaise, long sgstPaise, long igstPaise,
            long totalPaise, String amountInWords, String status, long paidPaise, long balancePaise, LocalDate paidOn,
            boolean overdue, String issuedByName, Instant cancelledAt, String cancelledByName, String cancelReason,
            List<PaymentView> payments, boolean sample) {
    }

    /**
     * What a school's admins are told on every page and on the Billing page: {@code kind} is TRIAL_ENDING,
     * TRIAL_ENDED, PAYMENT_OVERDUE or null. {@code overduePaise}, {@code overdueInvoices} and {@code oldestDueDate}
     * describe unpaid invoices past their due date (zero and null when the school is only marked past due).
     */
    public record Notice(String kind, Instant trialEndsAt, Integer trialDaysLeft, long overduePaise,
            int overdueInvoices, LocalDate oldestDueDate) {

        static final Notice NONE = new Notice(null, null, null, 0, 0, null);
    }

    /** The school's own Billing page. {@code trialDaysLeft} is null when the school is not on trial. */
    public record SchoolBilling(TenantStatus status, Plan plan, Instant trialEndsAt, Integer trialDaysLeft,
            long activeStudents, List<PlanView> plans, SubscriptionView subscription, BillingDetails details,
            List<InvoiceSummary> invoices, Notice notice) {
    }

    /** One school's billing as the Super Admin manages it. */
    public record SchoolAccount(TenantSummary school, long activeStudents, SubscriptionView subscription,
            BillingDetails details, Suspension suspension, List<InvoiceSummary> invoices, long unpaidPaise,
            long overduePaise, Notice notice) {
    }

    /**
     * A row of the Super Admin's renewals list. {@code kind} is OVERDUE (date: the oldest unpaid due date, amount:
     * what is overdue), DUE_FOR_RENEWAL (date: the next renewal, amount: the next invoice with GST, or before GST
     * when the state is not known) or TRIAL_ENDING (date: the trial's last day). {@code days} counts from today: days
     * left, or negative days past.
     */
    public record RenewalRow(UUID tenantId, String name, String code, TenantStatus status, Plan plan, String kind,
            LocalDate date, long days, Long amountPaise, Integer invoices) {
    }

    /** The Super Admin's platform health page. Counts are null when the database could not be reached. */
    public record PlatformHealth(Instant checkedAt, Schools schools, Long users, Long activeStudents, Outbox outbox,
            Database database, App app) {

        public record Schools(long total, Map<TenantStatus, Long> byStatus) {
        }

        public record Outbox(long queued, long failed) {
        }

        public record Database(boolean reachable, Long latencyMs) {
        }

        public record App(String version, Instant startedAt, long uptimeSeconds) {
        }
    }
}
