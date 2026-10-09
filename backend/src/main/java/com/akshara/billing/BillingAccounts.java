package com.akshara.billing;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.akshara.billing.BillingViews.InvoiceView;
import com.akshara.billing.BillingViews.Notice;
import com.akshara.billing.BillingViews.PlanView;
import com.akshara.billing.BillingViews.RenewalRow;
import com.akshara.billing.BillingViews.SchoolAccount;
import com.akshara.billing.BillingViews.SchoolBilling;
import com.akshara.platform.Plan;
import com.akshara.platform.TenantDirectory;
import com.akshara.platform.TenantStatus;
import com.akshara.platform.TenantSummary;
import com.akshara.platform.TenantView;
import com.akshara.shared.ApiException;
import com.akshara.shared.TenantContext;
import com.akshara.students.StudentRoster;

/**
 * Reading billing: a school's own Billing page and banner, one school's account as the Super Admin sees it, the plan
 * catalogue and the renewals list. Methods about one school run as that school (its own token, or the Super Admin's
 * controller selecting it), so its invoices are read under row-level security.
 */
@Service
@Transactional(readOnly = true)
public class BillingAccounts {

    private final TenantDirectory tenants;
    private final SubscriptionRepository subscriptions;
    private final InvoiceRepository invoices;
    private final InvoicePaymentRepository payments;
    private final StudentRoster roster;
    private final PlatformCounts counts;
    private final BillingProperties properties;

    BillingAccounts(TenantDirectory tenants, SubscriptionRepository subscriptions, InvoiceRepository invoices,
            InvoicePaymentRepository payments, StudentRoster roster, PlatformCounts counts,
            BillingProperties properties) {
        this.tenants = tenants;
        this.subscriptions = subscriptions;
        this.invoices = invoices;
        this.payments = payments;
        this.roster = roster;
        this.counts = counts;
        this.properties = properties;
    }

    // ------------------------------------------------------------------ the school's own view

    /** The school's Billing page: plan, trial, subscription, billing details and invoices. */
    public SchoolBilling schoolBilling() {
        UUID tenantId = TenantContext.require();
        TenantView school = tenants.findById(tenantId).orElseThrow(() -> ApiException.notFound("School"));
        Subscription subscription = subscriptions.findByTenantId(tenantId).orElse(null);
        LocalDate today = BillingDay.today();
        Instant now = Instant.now();
        List<Invoice> all = invoices.newestFirst();
        return new SchoolBilling(school.status(), school.plan(), school.trialEndsAt(),
                BillingMapper.trialDaysLeft(school.status(), school.trialEndsAt(), now), roster.activeCount(),
                PlanCatalog.all().stream().map(p -> PlanView.of(p, null)).toList(),
                BillingMapper.subscription(subscription, school.plan()), BillingMapper.details(subscription),
                all.stream().map(i -> BillingMapper.summary(i, today)).toList(),
                BillingMapper.notice(school.status(), school.trialEndsAt(), all, today, now,
                        properties.trialWarningDays()));
    }

    /** The banner for the school's admins. */
    public Notice notice() {
        UUID tenantId = TenantContext.require();
        TenantView school = tenants.findById(tenantId).orElseThrow(() -> ApiException.notFound("School"));
        return BillingMapper.notice(school.status(), school.trialEndsAt(), invoices.unpaid(), BillingDay.today(),
                Instant.now(), properties.trialWarningDays());
    }

    /** An invoice of the current school; another school's invoice is 404. */
    public InvoiceView invoice(UUID invoiceId) {
        TenantContext.require();
        Invoice invoice = invoices.findById(invoiceId).orElseThrow(() -> ApiException.notFound("Invoice"));
        return view(invoice);
    }

    InvoiceView view(Invoice invoice) {
        return BillingMapper.view(invoice, payments.findByInvoiceIdOrderByCreatedAtAsc(invoice.getId()),
                BillingDay.today());
    }

    // ------------------------------------------------------------------ the Super Admin's views

    /** One school's billing as the Super Admin manages it. Runs as that school. */
    public SchoolAccount account() {
        UUID tenantId = TenantContext.require();
        TenantSummary school = tenants.summary(tenantId).orElseThrow(() -> ApiException.notFound("School"));
        Subscription subscription = subscriptions.findByTenantId(tenantId).orElse(null);
        LocalDate today = BillingDay.today();
        List<Invoice> all = invoices.newestFirst();
        long unpaid = all.stream().filter(i -> Invoice.ISSUED.equals(i.getStatus()))
                .mapToLong(Invoice::balancePaise).sum();
        long overdue = all.stream().filter(i -> i.isOverdue(today)).mapToLong(Invoice::balancePaise).sum();
        return new SchoolAccount(school, roster.activeCount(), BillingMapper.subscription(subscription, school.plan()),
                BillingMapper.details(subscription), BillingMapper.suspension(subscription),
                all.stream().map(i -> BillingMapper.summary(i, today)).toList(), unpaid, overdue,
                BillingMapper.notice(school.status(), school.trialEndsAt(), all, today, Instant.now(),
                        properties.trialWarningDays()));
    }

    /** The plan catalogue with how many schools are on each plan. */
    public List<PlanView> plans() {
        Map<Plan, Long> perPlan = tenants.summaries().stream()
                .collect(Collectors.groupingBy(TenantSummary::plan, Collectors.counting()));
        return PlanCatalog.all().stream().map(p -> PlanView.of(p, perPlan.getOrDefault(p.plan(), 0L))).toList();
    }

    /**
     * What needs the Super Admin's attention, most urgent first: unpaid invoices past their due date, paid
     * subscriptions whose next period starts within {@code renewalWindowDays} (or already started) and has no invoice
     * yet, and trials that end within {@code trialWarningDays} or have ended. Nothing here changes any school.
     * Invoice amounts come from a security-definer function that returns per-school totals only.
     */
    public List<RenewalRow> renewals() {
        LocalDate today = BillingDay.today();
        Map<UUID, Subscription> byTenant = subscriptions.findAll().stream()
                .collect(Collectors.toMap(Subscription::getTenantId, Function.identity()));
        Map<UUID, PlatformCounts.Unpaid> unpaid = counts.unpaid(today);
        String sellerState = properties.seller().stateCode();
        List<RenewalRow> rows = new ArrayList<>();
        for (TenantSummary school : tenants.summaries()) {
            PlatformCounts.Unpaid owed = unpaid.getOrDefault(school.id(), PlatformCounts.Unpaid.NONE);
            if (owed.overdueCount() > 0) {
                rows.add(row(school, "OVERDUE", owed.oldestOverdueDue(), today, owed.overduePaise(),
                        (int) owed.overdueCount()));
            }
            if (school.status() == TenantStatus.SUSPENDED) {
                continue;
            }
            Subscription subscription = byTenant.get(school.id());
            if (subscription != null && subscription.isPaid()) {
                LocalDate next = subscription.nextRenewalOn();
                if (ChronoUnit.DAYS.between(today, next) <= properties.renewalWindowDays()) {
                    long taxable = BillingMapper.subscription(subscription, school.plan()).nextInvoiceTaxablePaise();
                    long amount = subscription.getStateCode() == null ? taxable
                            : GstMath.tax(taxable, sellerState, subscription.getStateCode()).totalPaise();
                    rows.add(row(school, "DUE_FOR_RENEWAL", next, today, amount, null));
                }
            }
            if (school.status() == TenantStatus.TRIAL && school.trialEndsAt() != null) {
                LocalDate ends = BillingDay.of(school.trialEndsAt());
                if (ChronoUnit.DAYS.between(today, ends) <= properties.trialWarningDays()) {
                    rows.add(row(school, "TRIAL_ENDING", ends, today, null, null));
                }
            }
        }
        rows.sort(Comparator.comparing(RenewalRow::date).thenComparing(RenewalRow::name));
        return rows;
    }

    private static RenewalRow row(TenantSummary school, String kind, LocalDate date, LocalDate today, Long amount,
            Integer invoiceCount) {
        return new RenewalRow(school.id(), school.name(), school.code(), school.status(), school.plan(), kind, date,
                ChronoUnit.DAYS.between(today, date), amount, invoiceCount);
    }
}
