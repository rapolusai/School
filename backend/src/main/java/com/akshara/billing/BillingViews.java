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
    /**
     * {@code pendingPlan}: a downgrade the school scheduled for {@code nextRenewalOn} (the next invoice is priced at
     * it). {@code cancelAtPeriodEnd}: the school cancelled; it is not renewed and is suspended after
     * {@code periodEnd}. The next invoice is {@code nextBilledStudents} at {@code nextUnitPricePaise}
     * ({@code nextInvoiceTaxablePaise} before GST).
     */
    public record SubscriptionView(BillingCycle billingCycle, int billedStudents, LocalDate periodStart,
            LocalDate periodEnd, LocalDate nextRenewalOn, LocalDate paidSince, long unitPricePaise,
            long nextInvoiceTaxablePaise, Plan pendingPlan, Instant pendingPlanRequestedAt, boolean cancelAtPeriodEnd,
            Instant cancelRequestedAt, long nextUnitPricePaise, int nextBilledStudents) {
    }

    public record Suspension(Instant suspendedAt, String reason) {
    }

    /**
     * A cancellation waiting to take effect: the school is suspended instead of renewed after {@code endsOn} (the
     * current period's last day, or the trial's last day for a school not paying yet: {@code trial}).
     * {@code requestedByName} is the School Admin, or the Super Admin who cancelled for the school.
     */
    public record Cancellation(LocalDate endsOn, Instant requestedAt, String reason, String requestedByName,
            boolean trial) {
    }

    /** {@code kind}: PERIOD (a period's own invoice) or UPGRADE (the rest of a period after an upgrade). */
    public record InvoiceSummary(UUID id, String invoiceNo, LocalDate invoiceDate, LocalDate dueDate,
            LocalDate periodStart, LocalDate periodEnd, Plan plan, BillingCycle billingCycle, long totalPaise,
            long paidPaise, long balancePaise, String status, boolean overdue, String kind, Plan previousPlan) {
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
            List<PaymentView> payments, boolean sample, String kind, Plan previousPlan) {
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

    /**
     * The school's own Billing page. {@code trialDaysLeft} is null when the school is not on trial.
     * {@code cancellation} is null unless the school cancelled. {@code onlinePayments} is false while Akshara has no
     * payment gateway switched on (invoices are then paid by bank transfer).
     */
    public record SchoolBilling(TenantStatus status, Plan plan, Instant trialEndsAt, Integer trialDaysLeft,
            long activeStudents, List<PlanView> plans, SubscriptionView subscription, BillingDetails details,
            List<InvoiceSummary> invoices, Notice notice, Cancellation cancellation, boolean onlinePayments) {
    }

    /** One school's billing as the Super Admin manages it. {@code cancellation} is null unless it cancelled. */
    public record SchoolAccount(TenantSummary school, long activeStudents, SubscriptionView subscription,
            BillingDetails details, Suspension suspension, List<InvoiceSummary> invoices, long unpaidPaise,
            long overduePaise, Notice notice, Cancellation cancellation) {
    }

    /**
     * A row of the Super Admin's renewals list. {@code kind} is OVERDUE (date: the oldest unpaid due date, amount:
     * what is overdue), DUE_FOR_RENEWAL (date: the next renewal, amount: the next invoice with GST, or before GST
     * when the state is not known), TRIAL_ENDING (date: the trial's last day), CANCELLING (the school cancelled; date:
     * the last day it works, the period's or the trial's) or DOWNGRADE_SCHEDULED (date: the next renewal, when
     * {@code pendingPlan} takes over; amount: the next invoice as for DUE_FOR_RENEWAL). {@code days} counts from
     * today: days left, or negative days past. {@code pendingPlan} is the scheduled downgrade, {@code note} the
     * cancellation's reason; both null otherwise.
     */
    public record RenewalRow(UUID tenantId, String name, String code, TenantStatus status, Plan plan, String kind,
            LocalDate date, long days, Long amountPaise, Integer invoices, Plan pendingPlan, String note) {
    }

    /**
     * An online payment of an invoice's whole balance. {@code checkoutKey} is the public key the browser's Razorpay
     * checkout needs (null for the sandbox, whose checkout is a page of this app). {@code failureReason} also says when
     * a payment arrived for an invoice that was no longer due (to be refunded at the gateway).
     */
    public record InvoiceOrderView(UUID id, UUID invoiceId, String invoiceNo, String gateway, String gatewayOrderId,
            long amountPaise, String currency, String status, String failureReason, String checkoutKey,
            String payeeName) {
    }

    /** What the sandbox checkout page shows for an invoice order. */
    public record SandboxInvoiceCheckout(String gatewayOrderId, UUID orderId, UUID invoiceId, String invoiceNo,
            long amountPaise, String currency, String payeeName, String schoolName, String status) {
    }

    /** What a gateway's checkout hands back to the browser: verify it with the order. */
    public record InvoiceCheckoutResult(UUID orderId, String gatewayOrderId, String status, String gatewayPaymentId,
            String signature) {
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

    /**
     * What the signed-in school's plan allows, for the web app's menu: {@code excludedFeatures} are the gated features
     * the plan leaves out, {@code lockedFeatures} and {@code lockedModules} those locked now (none during the trial,
     * when {@code enforced} is false), and the students on roll against {@code maxStudents} (null: unlimited).
     */
    public record PlanAccessView(Plan plan, TenantStatus status, boolean enforced, List<String> excludedFeatures,
            List<String> lockedFeatures, List<String> lockedModules, Integer maxStudents, long activeStudents) {
    }

    /**
     * What changing to {@code plan} would do now (GET /api/billing/plan/quote). {@code kind}: SAME, TRIAL (a school
     * not paying yet: at once, free), UPGRADE (at once; for a paying school with a live invoice for the period, an
     * invoice for the rest of it: {@code remainingDays} of {@code periodDays}, {@code unitPricePaise} a student for
     * {@code billedStudents}, {@code taxablePaise} plus GST = {@code totalPaise}) or DOWNGRADE (on {@code effectiveOn},
     * the next renewal). {@code allowed} is false when the plan does not cover the students on roll.
     */
    public record PlanChangeQuote(Plan plan, String kind, LocalDate effectiveOn, long remainingDays, long periodDays,
            long unitPricePaise, int billedStudents, long taxablePaise, long totalPaise, boolean allowed,
            Integer maxStudents, long activeStudents) {
    }
}
