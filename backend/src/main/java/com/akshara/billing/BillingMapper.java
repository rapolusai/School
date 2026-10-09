package com.akshara.billing;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;

import com.akshara.billing.BillingViews.BillingDetails;
import com.akshara.billing.BillingViews.InvoiceSummary;
import com.akshara.billing.BillingViews.InvoiceView;
import com.akshara.billing.BillingViews.Notice;
import com.akshara.billing.BillingViews.Party;
import com.akshara.billing.BillingViews.PaymentView;
import com.akshara.billing.BillingViews.SubscriptionView;
import com.akshara.billing.BillingViews.Suspension;
import com.akshara.fees.AmountInWords;
import com.akshara.platform.Plan;
import com.akshara.platform.TenantStatus;

/** Turns subscriptions and invoices into API views, and decides what a school's admins are told. */
final class BillingMapper {

    static final String TRIAL_ENDING = "TRIAL_ENDING";
    static final String TRIAL_ENDED = "TRIAL_ENDED";
    static final String PAYMENT_OVERDUE = "PAYMENT_OVERDUE";

    private BillingMapper() {
    }

    /** The paid subscription at the school's current plan, or null before the school pays. */
    static SubscriptionView subscription(Subscription s, Plan plan) {
        if (s == null || !s.isPaid()) {
            return null;
        }
        long unit = PlanCatalog.of(plan).unitPricePaise(s.getBillingCycle());
        return new SubscriptionView(s.getBillingCycle(), s.getBilledStudents(), s.getPeriodStart(), s.getPeriodEnd(),
                s.nextRenewalOn(), s.getPaidSince(), unit, Math.multiplyExact(unit, s.getBilledStudents()));
    }

    static BillingDetails details(Subscription s) {
        if (s == null) {
            return BillingDetails.NONE;
        }
        return new BillingDetails(s.getLegalName(), s.getBillingAddress(), s.getStateCode(), s.getGstin());
    }

    static Suspension suspension(Subscription s) {
        return s == null || s.getSuspendedAt() == null ? null
                : new Suspension(s.getSuspendedAt(), s.getSuspensionReason());
    }

    static InvoiceSummary summary(Invoice i, LocalDate today) {
        return new InvoiceSummary(i.getId(), i.getInvoiceNo(), i.getInvoiceDate(), i.getDueDate(), i.getPeriodStart(),
                i.getPeriodEnd(), i.getPlan(), i.getBillingCycle(), i.getTotalPaise(), i.getPaidPaise(),
                i.balancePaise(), i.getStatus(), i.isOverdue(today));
    }

    static InvoiceView view(Invoice i, List<InvoicePayment> payments, LocalDate today) {
        boolean intraState = i.getTaxSplit() == GstMath.Split.CGST_SGST;
        List<PaymentView> paid = payments.stream()
                .sorted(Comparator.comparing(InvoicePayment::getCreatedAt))
                .map(p -> new PaymentView(p.getId(), p.getAmountPaise(), p.getMode(), p.getReference(),
                        p.getPaidOn(), p.getRecordedByName(), p.getCreatedAt()))
                .toList();
        return new InvoiceView(i.getId(), i.getInvoiceNo(), i.getFinancialYear(), i.getInvoiceDate(), i.getDueDate(),
                new Party(i.getSellerName(), i.getSellerAddress(), i.getSellerStateCode(), i.getSellerGstin()),
                new Party(i.getBuyerName(), i.getBuyerAddress(), i.getBuyerStateCode(), i.getBuyerGstin()),
                i.getSacCode(), i.getPlan(), i.getBillingCycle(), i.getPeriodStart(), i.getPeriodEnd(),
                i.getBilledStudents(), i.getUnitPricePaise(), i.getTaxablePaise(), i.getTaxSplit(),
                intraState ? GstMath.HALF_RATE_BP : 0, intraState ? GstMath.HALF_RATE_BP : 0,
                intraState ? 0 : GstMath.GST_RATE_BP, i.getCgstPaise(), i.getSgstPaise(), i.getIgstPaise(),
                i.getTotalPaise(), AmountInWords.rupees(i.getTotalPaise()), i.getStatus(), i.getPaidPaise(),
                i.balancePaise(), i.getPaidOn(), i.isOverdue(today), i.getIssuedByName(), i.getCancelledAt(),
                i.getCancelledByName(), i.getCancelReason(), paid, !Gstin.isValid(i.getSellerGstin()));
    }

    /** Whole days of trial left (0 on the last day and after it ends), or null for a school not on trial. */
    static Integer trialDaysLeft(TenantStatus status, Instant trialEndsAt, Instant now) {
        if (status != TenantStatus.TRIAL || trialEndsAt == null) {
            return null;
        }
        if (!now.isBefore(trialEndsAt)) {
            return 0;
        }
        // Rounded up, like the trial banner on the dashboard: a trial ending in 20 hours has 1 day left.
        return (int) Math.ceil(Duration.between(now, trialEndsAt).toSeconds() / 86_400.0);
    }

    /**
     * The banner for a school's admins: overdue payment first (an unpaid invoice past its due date, or the school
     * marked past due by the Super Admin), then a trial that ends within {@code trialWarningDays} or has ended. A
     * suspended school gets none (nobody can sign in), and nothing ever changes the school's status by itself.
     */
    static Notice notice(TenantStatus status, Instant trialEndsAt, List<Invoice> invoices, LocalDate today,
            Instant now, int trialWarningDays) {
        if (status == TenantStatus.SUSPENDED) {
            return Notice.NONE;
        }
        List<Invoice> overdue = invoices.stream().filter(i -> i.isOverdue(today)).toList();
        if (!overdue.isEmpty() || status == TenantStatus.PAST_DUE) {
            long amount = overdue.stream().mapToLong(Invoice::balancePaise).sum();
            LocalDate oldest = overdue.stream().map(Invoice::getDueDate).min(Comparator.naturalOrder()).orElse(null);
            return new Notice(PAYMENT_OVERDUE, null, null, amount, overdue.size(), oldest);
        }
        Integer daysLeft = trialDaysLeft(status, trialEndsAt, now);
        if (daysLeft == null) {
            return Notice.NONE;
        }
        if (!now.isBefore(trialEndsAt)) {
            return new Notice(TRIAL_ENDED, trialEndsAt, 0, 0, 0, null);
        }
        if (daysLeft <= trialWarningDays) {
            return new Notice(TRIAL_ENDING, trialEndsAt, daysLeft, 0, 0, null);
        }
        return Notice.NONE;
    }
}
