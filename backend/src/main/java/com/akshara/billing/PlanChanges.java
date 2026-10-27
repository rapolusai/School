package com.akshara.billing;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.akshara.audit.AuditService;
import com.akshara.audit.AuditService.Actor;
import com.akshara.billing.BillingViews.PlanChangeQuote;
import com.akshara.billing.BillingViews.SchoolBilling;
import com.akshara.billing.InvoiceIssuer.Proration;
import com.akshara.billing.PlanCatalog.PlanInfo;
import com.akshara.platform.Plan;
import com.akshara.platform.TenantDirectory;
import com.akshara.platform.TenantStatus;
import com.akshara.platform.TenantView;
import com.akshara.shared.ApiException;
import com.akshara.shared.CurrentUser;
import com.akshara.shared.TenantContext;
import com.akshara.students.StudentRoster;

/**
 * A School Admin changes the school's plan from its Billing page (docs/api/phase-3-subscriptions.md, section 4):
 * <ul>
 * <li><b>Upgrade</b> (a dearer plan): at once. A paying school gets an invoice of its own for the rest of the current
 * period: the per-student price difference for one period, prorated by day ({@link Proration}), for its billed
 * students, plus GST.</li>
 * <li><b>Downgrade</b> (a cheaper plan): scheduled for the next renewal, and refused while the school has more
 * students on roll than the plan covers. It can be withdrawn until then.</li>
 * <li><b>Cancel</b>: the subscription is not renewed and the school is suspended when the current period ends. It
 * can be withdrawn until then.</li>
 * </ul>
 * A school that does not pay yet (a trial) changes plan at once and for nothing. Every change locks the school's
 * subscription row, so a second click finds the change made and does nothing more, and is audited.
 */
@Service
@Transactional
public class PlanChanges {

    /** Problem type of a downgrade to a plan that does not cover the school's students on roll. */
    static final String DOWNGRADE_REFUSED_TYPE = "urn:akshara:problem:plan-downgrade";

    /** What a plan change would be, for {@link PlanChangeQuote#kind()}. */
    static final String SAME = "SAME";
    static final String UPGRADE = "UPGRADE";
    static final String DOWNGRADE = "DOWNGRADE";
    static final String TRIAL = "TRIAL";

    private final TenantDirectory tenants;
    private final SubscriptionRepository subscriptions;
    private final InvoiceRepository invoices;
    private final InvoiceIssuer issuer;
    private final BillingAccounts accounts;
    private final BillingProperties properties;
    private final StudentRoster roster;
    private final AuditService audit;

    PlanChanges(TenantDirectory tenants, SubscriptionRepository subscriptions, InvoiceRepository invoices,
            InvoiceIssuer issuer, BillingAccounts accounts, BillingProperties properties, StudentRoster roster,
            AuditService audit) {
        this.tenants = tenants;
        this.subscriptions = subscriptions;
        this.invoices = invoices;
        this.issuer = issuer;
        this.accounts = accounts;
        this.properties = properties;
        this.roster = roster;
        this.audit = audit;
    }

    /** A dearer plan is an upgrade (prices rise with the plans' limits and features). */
    static boolean isUpgrade(Plan from, Plan to) {
        return PlanCatalog.of(to).pricePerStudentPerYearPaise() > PlanCatalog.of(from).pricePerStudentPerYearPaise();
    }

    // ------------------------------------------------------------------ what a change would be

    /** What changing to {@code target} would do now, and cost, without changing anything. */
    @Transactional(readOnly = true)
    public PlanChangeQuote quote(Plan target) {
        UUID tenantId = TenantContext.require();
        TenantView school = school(tenantId);
        Subscription subscription = subscriptions.findByTenantId(tenantId).orElse(null);
        LocalDate today = BillingDay.today();
        long active = roster.activeCount();
        PlanInfo info = PlanCatalog.of(target);
        boolean fits = info.covers((int) Math.min(Integer.MAX_VALUE, active));
        Plan current = school.plan();
        if (subscription == null || !subscription.isPaid()) {
            return new PlanChangeQuote(target, current == target ? SAME : TRIAL, today, 0, 0, 0, 0, 0, 0,
                    fits || isUpgrade(current, target), info.maxStudents(), active);
        }
        if (current == target) {
            return new PlanChangeQuote(target, SAME, today, 0, 0, 0, 0, 0, 0, true, info.maxStudents(), active);
        }
        if (!isUpgrade(current, target)) {
            return new PlanChangeQuote(target, DOWNGRADE, subscription.nextRenewalOn(), 0, 0, 0,
                    subscription.getBilledStudents(), 0, 0, fits, info.maxStudents(), active);
        }
        if (!proratable(subscription, today)) {
            return new PlanChangeQuote(target, UPGRADE, today, 0, 0, 0, subscription.getBilledStudents(), 0, 0, true,
                    info.maxStudents(), active);
        }
        Proration proration = Proration.of(subscription, current, target, today);
        long taxable = Math.multiplyExact(proration.unitPricePaise(), subscription.getBilledStudents());
        GstMath.Tax tax = GstMath.tax(taxable, properties.seller().stateCode(), subscription.getStateCode());
        return new PlanChangeQuote(target, UPGRADE, today, proration.remainingDays(), proration.periodDays(),
                proration.unitPricePaise(), subscription.getBilledStudents(), taxable, tax.totalPaise(), true,
                info.maxStudents(), active);
    }

    // ------------------------------------------------------------------ changes

    /**
     * Changes the school's plan: at once for an upgrade (with its prorated invoice) or a school not paying yet; at the
     * next renewal for a downgrade. Choosing the current plan withdraws a scheduled downgrade.
     */
    public SchoolBilling change(Plan target) {
        UUID tenantId = TenantContext.require();
        Actor by = signedIn();
        Subscription subscription = lockOrCreate(tenantId);
        TenantView school = school(tenantId);
        requireNotSuspended(school);
        Plan current = school.plan();

        if (!subscription.isPaid()) {
            if (current != target) {
                if (!isUpgrade(current, target)) {
                    requireFits(target);
                }
                tenants.changePlan(tenantId, target);
                Map<String, Object> details = new LinkedHashMap<>();
                details.put("plan", target.name());
                details.put("previousPlan", current.name());
                details.put("status", school.status().name());
                details.put("selfService", true);
                audit.record(by, "subscription.changed", "school", tenantId, details);
            }
            return accounts.schoolBilling();
        }

        if (current == target) {
            withdrawPending(subscription, by);
            return accounts.schoolBilling();
        }
        if (isUpgrade(current, target)) {
            upgrade(subscription, school, target, by);
        } else {
            requireFits(target);
            if (subscription.getPendingPlan() != target) {
                subscription.schedulePlan(target);
                subscriptions.saveAndFlush(subscription);
                Map<String, Object> details = new LinkedHashMap<>();
                details.put("plan", target.name());
                details.put("currentPlan", current.name());
                details.put("effectiveOn", subscription.nextRenewalOn().toString());
                audit.record(by, "subscription.downgrade_scheduled", "school", tenantId, details);
            }
        }
        return accounts.schoolBilling();
    }

    /** Withdraws a scheduled downgrade: the school stays on its plan. Nothing to withdraw is not an error. */
    public SchoolBilling withdrawDowngrade() {
        UUID tenantId = TenantContext.require();
        Subscription subscription = lockOrCreate(tenantId);
        requireNotSuspended(school(tenantId));
        withdrawPending(subscription, signedIn());
        return accounts.schoolBilling();
    }

    /** Cancels at the end of the current period: no renewal, and the school is suspended after the period ends. */
    public SchoolBilling cancel(String reason) {
        UUID tenantId = TenantContext.require();
        Subscription subscription = lockOrCreate(tenantId);
        TenantView school = school(tenantId);
        requireNotSuspended(school);
        if (!subscription.isPaid()) {
            throw conflict("Not paying", "There is no paid subscription to cancel. A trial simply ends.");
        }
        if (!subscription.isCancelAtPeriodEnd()) {
            subscription.cancelAtPeriodEnd();
            subscriptions.saveAndFlush(subscription);
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("endsOn", subscription.getPeriodEnd().toString());
            String text = reason == null || reason.isBlank() ? null : reason.trim();
            if (text != null) {
                details.put("reason", text);
            }
            audit.record(signedIn(), "subscription.cancel_scheduled", "school", tenantId, details);
        }
        return accounts.schoolBilling();
    }

    /** Takes a cancellation back before the period ends: the subscription renews as before. */
    public SchoolBilling resume() {
        UUID tenantId = TenantContext.require();
        Subscription subscription = lockOrCreate(tenantId);
        requireNotSuspended(school(tenantId));
        if (subscription.isCancelAtPeriodEnd()) {
            subscription.resume();
            subscriptions.saveAndFlush(subscription);
            audit.record(signedIn(), "subscription.cancel_withdrawn", "school", tenantId,
                    Map.of("periodEnd", subscription.getPeriodEnd().toString()));
        }
        return accounts.schoolBilling();
    }

    // ------------------------------------------------------------------ at renewal

    /**
     * As a paying school moves into its next period (the renewal job or the Super Admin's "Issue next invoice"),
     * applies a scheduled downgrade: the plan changes and the billed students are capped at its limit. A downgrade
     * that no longer fits (the school has more students on roll than the plan covers) is dropped and audited, and the
     * school renews on its plan. Returns the school as it now is. The caller has locked the subscription.
     */
    TenantView applyPendingPlan(Subscription subscription, TenantView school, Actor by) {
        Plan pending = subscription.getPendingPlan();
        if (pending == null) {
            return school;
        }
        subscription.clearPendingPlan();
        TenantView updated = school;
        long active = roster.activeCount();
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("plan", pending.name());
        details.put("previousPlan", school.plan().name());
        if (pending == school.plan()) {
            subscriptions.saveAndFlush(subscription);
            return school;
        }
        if (!PlanCatalog.of(pending).covers((int) Math.min(Integer.MAX_VALUE, active))) {
            details.put("activeStudents", active);
            details.put("maxStudents", PlanCatalog.of(pending).maxStudents());
            audit.record(by, "subscription.downgrade_dropped", "school", school.id(), details);
        } else {
            int billed = BillingService.billedStudentsOn(pending, subscription.getBilledStudents());
            if (billed != subscription.getBilledStudents()) {
                details.put("billedStudents", billed);
                details.put("previousBilledStudents", subscription.getBilledStudents());
            }
            subscription.change(subscription.getBillingCycle(), billed);
            updated = tenants.changePlan(school.id(), pending);
            audit.record(by, "subscription.downgraded", "school", school.id(), details);
        }
        subscriptions.saveAndFlush(subscription);
        return updated;
    }

    // ------------------------------------------------------------------ helpers

    private void upgrade(Subscription subscription, TenantView school, Plan target, Actor by) {
        UUID tenantId = school.id();
        Plan from = school.plan();
        LocalDate today = BillingDay.today();
        boolean pendingDropped = subscription.getPendingPlan() != null;
        subscription.clearPendingPlan();
        subscriptions.saveAndFlush(subscription);
        TenantView updated = tenants.changePlan(tenantId, target);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("plan", target.name());
        details.put("previousPlan", from.name());
        if (pendingDropped) {
            details.put("downgradeWithdrawn", true);
        }
        if (proratable(subscription, today)) {
            Proration proration = Proration.of(subscription, from, target, today);
            if (proration.unitPricePaise() > 0) {
                Invoice invoice = issuer.issueUpgrade(subscription, updated, from, by, today);
                details.put("invoiceNo", invoice.getInvoiceNo());
                details.put("totalPaise", invoice.getTotalPaise());
                details.put("remainingDays", proration.remainingDays());
                details.put("periodDays", proration.periodDays());
            }
        }
        audit.record(by, "subscription.upgraded", "school", tenantId, details);
    }

    /**
     * Whether an upgrade today is billed for the rest of the period: the period is under way (or about to start) and
     * its own invoice is live. A period whose invoice was cancelled will be invoiced again at the new plan anyway.
     */
    private boolean proratable(Subscription subscription, LocalDate today) {
        return subscription.getStateCode() != null && !today.isAfter(subscription.getPeriodEnd())
                && invoices.existsLiveForPeriod(subscription.getPeriodStart());
    }

    private void withdrawPending(Subscription subscription, Actor by) {
        Plan pending = subscription.getPendingPlan();
        if (pending != null) {
            subscription.clearPendingPlan();
            subscriptions.saveAndFlush(subscription);
            audit.record(by, "subscription.downgrade_withdrawn", "school", subscription.getTenantId(),
                    Map.of("plan", pending.name()));
        }
    }

    /** Refuses a plan that does not cover the school's students on roll. */
    private void requireFits(Plan target) {
        PlanInfo info = PlanCatalog.of(target);
        long active = roster.activeCount();
        if (!info.covers((int) Math.min(Integer.MAX_VALUE, active))) {
            Map<String, Object> properties = new LinkedHashMap<>();
            properties.put("plan", target.name());
            properties.put("maxStudents", info.maxStudents());
            properties.put("activeStudents", active);
            throw new ApiException(HttpStatus.CONFLICT, "Too many students for this plan",
                    "The " + target.name() + " plan covers up to " + info.maxStudents() + " students on roll, and the "
                            + "school has " + active + ". Fewer students on roll first, or choose a bigger plan.",
                    Map.of("plan", "The plan covers up to " + info.maxStudents() + " students."),
                    DOWNGRADE_REFUSED_TYPE, properties);
        }
    }

    private static void requireNotSuspended(TenantView school) {
        if (school.status() == TenantStatus.SUSPENDED) {
            throw conflict("Suspended", "The school is suspended. Contact Akshara.");
        }
    }

    private Subscription lockOrCreate(UUID tenantId) {
        return subscriptions.lockByTenantId(tenantId)
                .orElseGet(() -> subscriptions.saveAndFlush(new Subscription(tenantId)));
    }

    private TenantView school(UUID tenantId) {
        return tenants.findById(tenantId).orElseThrow(() -> ApiException.notFound("School"));
    }

    private static Actor signedIn() {
        return new Actor(CurrentUser.id().orElse(null), CurrentUser.name().orElse("School admin"));
    }

    private static ApiException conflict(String title, String detail) {
        return new ApiException(HttpStatus.CONFLICT, title, detail);
    }
}
