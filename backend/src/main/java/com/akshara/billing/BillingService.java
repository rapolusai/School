package com.akshara.billing;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.akshara.audit.AuditService;
import com.akshara.audit.AuditService.Actor;
import com.akshara.billing.BillingForms.ChangeForm;
import com.akshara.billing.BillingForms.DetailsForm;
import com.akshara.billing.BillingForms.PaymentForm;
import com.akshara.billing.BillingForms.StartForm;
import com.akshara.billing.BillingViews.InvoiceView;
import com.akshara.billing.BillingViews.SchoolAccount;
import com.akshara.billing.BillingViews.SchoolBilling;
import com.akshara.platform.Plan;
import com.akshara.platform.TenantDirectory;
import com.akshara.platform.TenantStatus;
import com.akshara.platform.TenantView;
import com.akshara.shared.ApiException;
import com.akshara.shared.CurrentUser;
import com.akshara.shared.TenantContext;

/**
 * Changing a school's billing: converting a trial to a paid subscription, changing the plan, billing details, issuing
 * invoices, recording the payments that reach Akshara's bank account, cancelling an unpaid invoice, and the school's
 * status (past due, suspension, reactivation). Every method runs as the school ({@link TenantContext}): the Super
 * Admin's controller selects it first. Each school's changes are serialised on its subscription row, and every change
 * is written to the school's own audit trail. Nothing here runs by itself: a school's status changes only when the
 * Super Admin asks.
 */
@Service
@Transactional
public class BillingService {

    /** How far back a first period may start (a school that has been using Akshara before paying). */
    static final int MAX_BACKDATE_DAYS = 365;

    /** How far ahead a first period may start. */
    static final int MAX_FORWARD_DAYS = 90;

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.ENGLISH);

    private final TenantDirectory tenants;
    private final SubscriptionRepository subscriptions;
    private final InvoiceRepository invoices;
    private final InvoicePaymentRepository payments;
    private final InvoiceIssuer issuer;
    private final BillingAccounts accounts;
    private final BillingProperties properties;
    private final AuditService audit;
    private final ApplicationEventPublisher events;

    BillingService(TenantDirectory tenants, SubscriptionRepository subscriptions, InvoiceRepository invoices,
            InvoicePaymentRepository payments, InvoiceIssuer issuer, BillingAccounts accounts,
            BillingProperties properties, AuditService audit, ApplicationEventPublisher events) {
        this.tenants = tenants;
        this.subscriptions = subscriptions;
        this.invoices = invoices;
        this.payments = payments;
        this.issuer = issuer;
        this.accounts = accounts;
        this.properties = properties;
        this.audit = audit;
        this.events = events;
    }

    // ------------------------------------------------------------------ billing details

    /** The Super Admin sets the school's billing details. Used on future invoices only. */
    public SchoolAccount updateDetails(DetailsForm form, Actor actor) {
        saveDetails(form, actor);
        return accounts.account();
    }

    /** A School Admin sets the school's own billing details. Used on future invoices only. */
    public SchoolBilling updateOwnDetails(DetailsForm form) {
        saveDetails(form, null);
        return accounts.schoolBilling();
    }

    private void saveDetails(DetailsForm form, Actor actor) {
        UUID tenantId = TenantContext.require();
        Actor by = actor(actor);
        String state = form.stateCode().trim();
        if (!Gstin.isStateCode(state)) {
            throw ApiException.badRequest("Choose the state.", "stateCode");
        }
        String gstin = blankToNull(form.gstin());
        if (gstin != null) {
            gstin = gstin.toUpperCase(Locale.ROOT);
            if (!Gstin.isValid(gstin)) {
                throw ApiException.badRequest("This GSTIN is not valid. Check its 15 characters.", "gstin");
            }
            if (!gstin.startsWith(state)) {
                throw ApiException.badRequest("This GSTIN is registered in another state: its first two digits are "
                        + "the state code.", "gstin");
            }
        }
        String legalName = blankToNull(form.legalName());
        String address = blankToNull(form.address());
        Subscription subscription = lockOrCreate(tenantId);
        if (Objects.equals(legalName, subscription.getLegalName())
                && Objects.equals(address, subscription.getBillingAddress())
                && state.equals(subscription.getStateCode()) && Objects.equals(gstin, subscription.getGstin())) {
            return;
        }
        subscription.updateDetails(legalName, address, state, gstin);
        subscriptions.saveAndFlush(subscription);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("stateCode", state);
        details.put("gstin", gstin);
        audit.record(by, "billing_details.updated", "school", tenantId, details);
    }

    // ------------------------------------------------------------------ subscription

    /**
     * Converts a trial (or a school added without one) to a paid subscription: sets the plan, cycle and billed
     * students, starts the first period and issues its invoice. A school on trial becomes ACTIVE.
     */
    public SchoolAccount start(StartForm form, Actor actor) {
        UUID tenantId = TenantContext.require();
        Actor by = actor(actor);
        Subscription subscription = lockOrCreate(tenantId);
        TenantView school = school(tenantId);
        if (school.status() == TenantStatus.SUSPENDED) {
            throw conflict("Suspended", "Reactivate the school before starting its subscription.");
        }
        if (subscription.isPaid()) {
            throw conflict("Already paying", "This school already has a paid subscription. Change it instead.");
        }
        requireState(subscription, "Add the school's billing details (state) before starting its subscription.");
        checkCovers(form.plan(), form.billedStudents());
        LocalDate today = BillingDay.today();
        LocalDate start = form.periodStart() == null ? today : form.periodStart();
        if (start.isBefore(today.minusDays(MAX_BACKDATE_DAYS)) || start.isAfter(today.plusDays(MAX_FORWARD_DAYS))) {
            throw ApiException.badRequest("Start the period no more than a year before today or 90 days after it.",
                    "periodStart");
        }
        subscription.start(form.billingCycle(), form.billedStudents(), start);
        subscriptions.saveAndFlush(subscription);
        TenantView updated = school;
        if (school.plan() != form.plan()) {
            updated = tenants.changePlan(tenantId, form.plan());
        }
        if (school.status() == TenantStatus.TRIAL) {
            updated = tenants.changeStatus(tenantId, TenantStatus.ACTIVE);
        }
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("plan", form.plan().name());
        details.put("billingCycle", form.billingCycle().name());
        details.put("billedStudents", form.billedStudents());
        details.put("periodStart", subscription.getPeriodStart().toString());
        details.put("periodEnd", subscription.getPeriodEnd().toString());
        details.put("previousStatus", school.status().name());
        details.put("status", updated.status().name());
        audit.record(by, "subscription.started", "school", tenantId, details);
        issuer.issue(subscription, updated, by);
        return accounts.account();
    }

    /**
     * Changes the plan; for a paying school also the cycle and billed students. The plan changes at once (what the
     * school may use); the price applies from the next invoice. No proration.
     */
    public SchoolAccount change(ChangeForm form, Actor actor) {
        UUID tenantId = TenantContext.require();
        Actor by = actor(actor);
        Subscription subscription = subscriptions.lockByTenantId(tenantId).orElse(null);
        TenantView school = school(tenantId);
        Map<String, Object> details = new LinkedHashMap<>();
        if (subscription != null && subscription.isPaid()) {
            if (form.billingCycle() == null) {
                throw ApiException.badRequest("Choose monthly or yearly billing.", "billingCycle");
            }
            if (form.billedStudents() == null) {
                throw ApiException.badRequest("Enter how many students to bill.", "billedStudents");
            }
            checkCovers(form.plan(), form.billedStudents());
            if (subscription.getBillingCycle() != form.billingCycle()) {
                details.put("billingCycle", form.billingCycle().name());
                details.put("previousBillingCycle", subscription.getBillingCycle().name());
            }
            if (!subscription.getBilledStudents().equals(form.billedStudents())) {
                details.put("billedStudents", form.billedStudents());
                details.put("previousBilledStudents", subscription.getBilledStudents());
            }
            subscription.change(form.billingCycle(), form.billedStudents());
            subscriptions.saveAndFlush(subscription);
        }
        if (school.plan() != form.plan()) {
            details.put("plan", form.plan().name());
            details.put("previousPlan", school.plan().name());
            tenants.changePlan(tenantId, form.plan());
        }
        if (!details.isEmpty()) {
            audit.record(by, "subscription.changed", "school", tenantId, details);
        }
        return accounts.account();
    }

    // ------------------------------------------------------------------ invoices

    /**
     * Issues the next invoice. When the current period's invoice was cancelled, the period is invoiced again (fitted to
     * the current cycle); otherwise the subscription renews into the next period, which is allowed from
     * {@code renewalWindowDays} before that period starts.
     */
    public InvoiceView issueNext(Actor actor) {
        UUID tenantId = TenantContext.require();
        Actor by = actor(actor);
        Subscription subscription = subscriptions.lockByTenantId(tenantId).filter(Subscription::isPaid)
                .orElseThrow(() -> conflict("Not paying", "Start a paid subscription for this school first."));
        TenantView school = school(tenantId);
        requireState(subscription, "Add the school's billing details (state) before invoicing it.");
        PlanCatalog.PlanInfo plan = PlanCatalog.of(school.plan());
        if (!plan.covers(subscription.getBilledStudents())) {
            throw conflict("Over the plan's limit", "The " + label(school.plan()) + " plan covers up to "
                    + plan.maxStudents() + " students. Change the plan or the billed students first.");
        }
        if (invoices.existsLiveForPeriod(subscription.getPeriodStart())) {
            LocalDate next = subscription.nextRenewalOn();
            LocalDate opensOn = next.minusDays(properties.renewalWindowDays());
            if (BillingDay.today().isBefore(opensOn)) {
                throw conflict("Too early", "The next period starts on " + DAY.format(next)
                        + ". Its invoice can be issued from " + DAY.format(opensOn) + ".");
            }
            subscription.renew();
        } else {
            subscription.refitPeriod();
        }
        subscriptions.saveAndFlush(subscription);
        return accounts.view(issuer.issue(subscription, school, by));
    }

    /** Records money that reached Akshara for an invoice; the invoice is PAID once nothing is left. */
    public InvoiceView recordPayment(UUID invoiceId, PaymentForm form, Actor actor) {
        UUID tenantId = TenantContext.require();
        Actor by = actor(actor);
        Invoice invoice = invoices.lock(invoiceId).orElseThrow(() -> ApiException.notFound("Invoice"));
        if (invoice.isCancelled()) {
            throw conflict("Cancelled", "Invoice " + invoice.getInvoiceNo() + " is cancelled.");
        }
        if (invoice.isPaid()) {
            throw conflict("Already paid", "Invoice " + invoice.getInvoiceNo() + " is already paid.");
        }
        long balance = invoice.balancePaise();
        long amount = form.amountPaise() == null ? balance : form.amountPaise();
        if (amount > balance) {
            throw ApiException.badRequest("This is more than the " + rupees(balance) + " still due.", "amountPaise");
        }
        if (form.paidOn().isAfter(BillingDay.today())) {
            throw ApiException.badRequest("The payment date cannot be in the future.", "paidOn");
        }
        String reference = form.reference().trim();
        payments.save(new InvoicePayment(invoice.getId(), amount, form.mode(), reference, form.paidOn(), by.id(),
                by.name()));
        invoice.pay(amount, form.paidOn());
        invoices.saveAndFlush(invoice);
        payments.flush();
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("invoiceNo", invoice.getInvoiceNo());
        details.put("amountPaise", amount);
        details.put("mode", form.mode().name());
        details.put("reference", reference);
        details.put("paidOn", form.paidOn().toString());
        details.put("balancePaise", invoice.balancePaise());
        audit.record(by, "invoice.payment_recorded", "invoice", invoice.getId(), details);
        if (invoice.isPaid()) {
            events.publishEvent(new SubscriptionInvoicePaid(tenantId, invoice.getId(), invoice.getInvoiceNo(),
                    invoice.getTotalPaise(), invoice.getPaidOn()));
        }
        return accounts.view(invoice);
    }

    /** Cancels an invoice with nothing paid on it. Its number is never reused; the period can be invoiced again. */
    public InvoiceView cancelInvoice(UUID invoiceId, String reason, Actor actor) {
        TenantContext.require();
        Actor by = actor(actor);
        Invoice invoice = invoices.lock(invoiceId).orElseThrow(() -> ApiException.notFound("Invoice"));
        if (invoice.isCancelled()) {
            throw conflict("Already cancelled", "Invoice " + invoice.getInvoiceNo() + " is already cancelled.");
        }
        if (invoice.getPaidPaise() > 0) {
            throw conflict("Has payments", "Invoice " + invoice.getInvoiceNo() + " has payments recorded, so it "
                    + "cannot be cancelled.");
        }
        invoice.cancel(by.name(), reason.trim());
        invoices.saveAndFlush(invoice);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("invoiceNo", invoice.getInvoiceNo());
        details.put("totalPaise", invoice.getTotalPaise());
        details.put("reason", reason.trim());
        audit.record(by, "invoice.cancelled", "invoice", invoice.getId(), details);
        return accounts.view(invoice);
    }

    // ------------------------------------------------------------------ status

    /** Marks a school past due, or active again. Trials end only by starting a subscription. */
    public SchoolAccount changeStatus(TenantStatus status, Actor actor) {
        UUID tenantId = TenantContext.require();
        Actor by = actor(actor);
        if (status != TenantStatus.ACTIVE && status != TenantStatus.PAST_DUE) {
            throw ApiException.badRequest("Choose Active or Past due. Suspension has its own action.", "status");
        }
        subscriptions.lockByTenantId(tenantId);
        TenantView school = school(tenantId);
        if (school.status() == TenantStatus.SUSPENDED) {
            throw conflict("Suspended", "Reactivate the school first.");
        }
        if (school.status() == TenantStatus.TRIAL) {
            throw conflict("On trial", "Start a paid subscription to end this school's trial.");
        }
        if (school.status() != status) {
            tenants.changeStatus(tenantId, status);
            audit.record(by, "school.status_changed", "school", tenantId,
                    Map.of("previousStatus", school.status().name(), "status", status.name()));
        }
        return accounts.account();
    }

    /**
     * Suspends a school: from now on nobody of it can sign in or refresh a session, and its public pages refuse. The
     * reason is kept with the school's billing and in its audit trail.
     */
    public SchoolAccount suspend(String reason, Actor actor) {
        UUID tenantId = TenantContext.require();
        Actor by = actor(actor);
        Subscription subscription = lockOrCreate(tenantId);
        TenantView school = school(tenantId);
        if (school.status() == TenantStatus.SUSPENDED) {
            throw conflict("Already suspended", "This school is already suspended.");
        }
        subscription.suspend(school.status(), reason.trim());
        subscriptions.saveAndFlush(subscription);
        tenants.changeStatus(tenantId, TenantStatus.SUSPENDED);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("reason", reason.trim());
        details.put("previousStatus", school.status().name());
        audit.record(by, "school.suspended", "school", tenantId, details);
        events.publishEvent(new SchoolSuspended(tenantId, subscription.getSuspendedAt()));
        return accounts.account();
    }

    /** Lifts a suspension: the school goes back to the status it had before. */
    public SchoolAccount reactivate(Actor actor) {
        UUID tenantId = TenantContext.require();
        Actor by = actor(actor);
        Subscription subscription = lockOrCreate(tenantId);
        TenantView school = school(tenantId);
        if (school.status() != TenantStatus.SUSPENDED) {
            throw conflict("Not suspended", "This school is not suspended.");
        }
        TenantStatus back = subscription.lift();
        if (back == null) {
            // Suspended outside the console (no reason on file): paying schools go back to active, others to trial.
            back = !subscription.isPaid() && school.trialEndsAt() != null ? TenantStatus.TRIAL : TenantStatus.ACTIVE;
        }
        subscriptions.saveAndFlush(subscription);
        tenants.changeStatus(tenantId, back);
        audit.record(by, "school.reactivated", "school", tenantId, Map.of("status", back.name()));
        events.publishEvent(new SchoolReactivated(tenantId, back));
        return accounts.account();
    }

    // ------------------------------------------------------------------ helpers

    /** The school's subscription row, locked until the transaction ends; created on first use. */
    private Subscription lockOrCreate(UUID tenantId) {
        return subscriptions.lockByTenantId(tenantId)
                .orElseGet(() -> subscriptions.saveAndFlush(new Subscription(tenantId)));
    }

    private TenantView school(UUID tenantId) {
        return tenants.findById(tenantId).orElseThrow(() -> ApiException.notFound("School"));
    }

    private static void requireState(Subscription subscription, String message) {
        if (subscription.getStateCode() == null) {
            throw ApiException.badRequest(message, "stateCode");
        }
    }

    private static void checkCovers(Plan plan, int students) {
        PlanCatalog.PlanInfo info = PlanCatalog.of(plan);
        if (!info.covers(students)) {
            throw ApiException.badRequest("The " + label(plan) + " plan covers up to " + info.maxStudents()
                    + " students.", "billedStudents");
        }
    }

    private static String label(Plan plan) {
        String name = plan.name().toLowerCase(Locale.ROOT);
        return Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }

    /** The given actor, or the signed-in Super Admin or School Admin. Names are copied onto invoices. */
    private static Actor actor(Actor actor) {
        if (actor != null) {
            return actor;
        }
        return new Actor(CurrentUser.id().orElse(null), CurrentUser.name().orElse("Akshara"));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static ApiException conflict(String title, String detail) {
        return new ApiException(HttpStatus.CONFLICT, title, detail);
    }

    /** "₹1,84,300" or "₹1,84,300.50": Indian digit grouping, for messages. */
    static String rupees(long paise) {
        String whole = Long.toString(paise / 100);
        StringBuilder grouped = new StringBuilder();
        int firstGroup = whole.length() > 3 ? (whole.length() - 3) % 2 : whole.length();
        if (whole.length() <= 3) {
            grouped.append(whole);
        } else {
            String head = whole.substring(0, whole.length() - 3);
            for (int i = 0; i < head.length(); i++) {
                if (i > 0 && (i - firstGroup) % 2 == 0) {
                    grouped.append(',');
                }
                grouped.append(head.charAt(i));
            }
            grouped.append(',').append(whole.substring(whole.length() - 3));
        }
        long rest = paise % 100;
        return "₹" + grouped + (rest == 0 ? "" : String.format(".%02d", rest));
    }
}
