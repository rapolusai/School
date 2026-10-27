package com.akshara.billing;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import com.akshara.audit.AuditService;
import com.akshara.audit.AuditService.Actor;
import com.akshara.platform.TenantDirectory;
import com.akshara.platform.TenantStatus;
import com.akshara.platform.TenantSummary;
import com.akshara.platform.TenantView;
import com.akshara.shared.TenantContext;

/**
 * What the daily renewal job does, for one school or for all of them, as of a given day (so tests can use fixed
 * dates). For each school that is not suspended, in this order and in one transaction as the school, with its
 * subscription row locked:
 * <ol>
 * <li><b>Renewal.</b> A paying school whose next period starts within {@code renewals.lead-days} (0: has started) and
 * whose current period is invoiced moves into the next period and gets its invoice, exactly as the Super Admin's
 * "Issue next invoice" (the same {@link InvoiceIssuer}). A period whose invoice was cancelled is left to the Super
 * Admin.</li>
 * <li><b>Past due.</b> An ACTIVE or TRIAL school with an unpaid invoice past its due date, or whose trial ended
 * without a paid subscription, becomes PAST_DUE. A school the job marked past due becomes ACTIVE again once that is no
 * longer true (it paid, or started paying).</li>
 * <li><b>Suspension.</b> A PAST_DUE school with an invoice more than {@code renewals.grace-days} past its due date, or
 * a trial that ended more than {@code renewals.trial-grace-days} ago, is suspended through the same path as the Super
 * Admin's suspension ({@link SchoolSuspended}). The grace period also counts from the school's last reactivation.</li>
 * </ol>
 * Every step reads the current state first, so running twice on one day changes nothing the second time.
 */
@Service
public class BillingRenewals {

    private static final Logger log = LoggerFactory.getLogger(BillingRenewals.class);

    /** Who the audit trail and invoices name for the job's changes. */
    static final String SYSTEM = "Akshara billing";

    /** Advisory lock key: one renewal round at a time across every API instance. */
    static final String LOCK_KEY = "billing.renewals";

    /** What the job did for one school. */
    public record Outcome(UUID tenantId, boolean renewed, String invoiceNo, TenantStatus statusBefore,
            TenantStatus statusAfter) {

        public boolean changed() {
            return renewed || statusBefore != statusAfter;
        }
    }

    /** One round across schools: how many were renewed, marked past due, made active again and suspended. */
    public record Round(boolean ran, int schools, int renewed, int pastDue, int restored, int suspended, int failed) {
    }

    private final TenantDirectory tenants;
    private final SubscriptionRepository subscriptions;
    private final InvoiceRepository invoices;
    private final InvoiceIssuer issuer;
    private final BillingService billing;
    private final BillingProperties properties;
    private final AuditService audit;
    private final TransactionTemplate tx;
    private final TransactionTemplate outer;
    private final JdbcTemplate jdbc;

    BillingRenewals(TenantDirectory tenants, SubscriptionRepository subscriptions, InvoiceRepository invoices,
            InvoiceIssuer issuer, BillingService billing, BillingProperties properties, AuditService audit,
            PlatformTransactionManager transactions, JdbcTemplate jdbc) {
        this.tenants = tenants;
        this.subscriptions = subscriptions;
        this.invoices = invoices;
        this.issuer = issuer;
        this.billing = billing;
        this.properties = properties;
        this.audit = audit;
        this.tx = new TransactionTemplate(transactions);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.outer = new TransactionTemplate(transactions);
        this.jdbc = jdbc;
    }

    /**
     * Runs the round for every school, each in its own transaction so one school's failure does not stop the others.
     * Must run with no school selected. When another instance is already running a round, returns at once with
     * {@code ran = false}.
     */
    public Round runAll(LocalDate today, Instant now) {
        if (TenantContext.current().isPresent()) {
            throw new IllegalStateException("Renewals work across schools; run them with no school selected");
        }
        Round round = outer.execute(status -> {
            Boolean locked = jdbc.queryForObject("select pg_try_advisory_xact_lock(hashtextextended(?, 0))",
                    Boolean.class, LOCK_KEY);
            if (!Boolean.TRUE.equals(locked)) {
                return new Round(false, 0, 0, 0, 0, 0, 0);
            }
            List<UUID> schools = new ArrayList<>();
            for (TenantSummary school : tenants.summaries()) {
                if (school.status() != TenantStatus.SUSPENDED) {
                    schools.add(school.id());
                }
            }
            int renewed = 0;
            int pastDue = 0;
            int restored = 0;
            int suspended = 0;
            int failed = 0;
            for (UUID school : schools) {
                try {
                    Outcome outcome = runFor(school, today, now);
                    renewed += outcome.renewed() ? 1 : 0;
                    if (outcome.statusBefore() != outcome.statusAfter()) {
                        switch (outcome.statusAfter()) {
                            case PAST_DUE -> pastDue++;
                            case ACTIVE -> restored++;
                            case SUSPENDED -> suspended++;
                            default -> {
                            }
                        }
                    }
                } catch (RuntimeException e) {
                    failed++;
                    log.warn("Renewals for school {} failed ({})", school, e.getClass().getSimpleName());
                }
            }
            return new Round(true, schools.size(), renewed, pastDue, restored, suspended, failed);
        });
        return round == null ? new Round(false, 0, 0, 0, 0, 0, 0) : round;
    }

    /** Runs the round for one school, as that school, in a transaction of its own. */
    public Outcome runFor(UUID tenantId, LocalDate today, Instant now) {
        return TenantContext.runAs(tenantId, () -> tx.execute(status -> round(tenantId, today, now)));
    }

    private Outcome round(UUID tenantId, LocalDate today, Instant now) {
        Actor system = new Actor(null, SYSTEM);
        Subscription subscription = subscriptions.lockByTenantId(tenantId).orElse(null);
        TenantView school = tenants.findById(tenantId).orElseThrow();
        TenantStatus before = school.status();
        if (before == TenantStatus.SUSPENDED) {
            return new Outcome(tenantId, false, null, before, before);
        }

        // 1. Renewal.
        String invoiceNo = null;
        if (subscription != null && subscription.isPaid() && subscription.getStateCode() != null) {
            LocalDate next = subscription.nextRenewalOn();
            boolean due = !today.isBefore(next.minusDays(properties.renewals().leadDays()));
            if (due && invoices.existsLiveForPeriod(subscription.getPeriodStart())) {
                invoiceNo = renew(subscription, school, system, today);
            }
        }

        // 2. Past due, and back.
        LocalDate overdueSince = billing.overdueSince(today);
        boolean trialLapsed = BillingService.trialLapsed(subscription, school, now);
        TenantStatus status = school.status();
        if ((overdueSince != null || trialLapsed)
                && (status == TenantStatus.ACTIVE || status == TenantStatus.TRIAL)) {
            if (subscription == null) {
                subscription = subscriptions.saveAndFlush(new Subscription(tenantId));
            }
            subscription.markedPastDueAutomatically(today);
            subscriptions.saveAndFlush(subscription);
            tenants.changeStatus(tenantId, TenantStatus.PAST_DUE);
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("previousStatus", status.name());
            details.put("status", TenantStatus.PAST_DUE.name());
            details.put("reason", overdueSince != null ? "invoice_overdue" : "trial_ended");
            details.put("automatic", true);
            audit.record(system, "school.status_changed", "school", tenantId, details);
            status = TenantStatus.PAST_DUE;
        } else if (status == TenantStatus.PAST_DUE && billing.restoreIfSettled(tenantId, system, today, now)) {
            status = TenantStatus.ACTIVE;
        }

        // 3. Suspension after the grace period.
        if (status == TenantStatus.PAST_DUE) {
            LocalDate graceFrom = null;
            int graceDays = 0;
            String reason = null;
            if (overdueSince != null) {
                graceFrom = overdueSince;
                graceDays = properties.renewals().graceDays();
                reason = "Subscription invoice unpaid for more than " + graceDays + " days after its due date "
                        + "(suspended automatically).";
            } else if (trialLapsed) {
                graceFrom = BillingDay.of(school.trialEndsAt());
                graceDays = properties.renewals().trialGraceDays();
                reason = "Free trial ended more than " + graceDays + " days ago without a paid subscription "
                        + "(suspended automatically).";
            }
            if (graceFrom != null && subscription != null && subscription.getGraceRestartedOn() != null
                    && subscription.getGraceRestartedOn().isAfter(graceFrom)) {
                graceFrom = subscription.getGraceRestartedOn();
            }
            if (graceFrom != null && today.isAfter(graceFrom.plusDays(graceDays))) {
                if (subscription == null) {
                    subscription = subscriptions.saveAndFlush(new Subscription(tenantId));
                }
                TenantView current = tenants.findById(tenantId).orElseThrow();
                billing.suspendNow(subscription, current, reason, system, Map.of("automatic", true));
                status = TenantStatus.SUSPENDED;
            }
        }
        return new Outcome(tenantId, invoiceNo != null, invoiceNo, before, status);
    }

    /** Moves into the next period and issues its invoice, if the plan still covers the billed students. */
    private String renew(Subscription subscription, TenantView school, Actor system, LocalDate today) {
        PlanCatalog.PlanInfo plan = PlanCatalog.of(school.plan());
        if (!plan.covers(subscription.getBilledStudents())) {
            log.warn("Not renewing school {}: its plan does not cover its billed students", school.id());
            return null;
        }
        LocalDate previousEnd = subscription.getPeriodEnd();
        subscription.renew();
        subscriptions.saveAndFlush(subscription);
        Invoice invoice = issuer.issue(subscription, school, system, today);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("invoiceNo", invoice.getInvoiceNo());
        details.put("previousPeriodEnd", previousEnd.toString());
        details.put("periodStart", subscription.getPeriodStart().toString());
        details.put("periodEnd", subscription.getPeriodEnd().toString());
        details.put("plan", school.plan().name());
        audit.record(system, "subscription.renewed", "school", school.id(), details);
        return invoice.getInvoiceNo();
    }
}
