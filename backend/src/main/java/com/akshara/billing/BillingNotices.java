package com.akshara.billing;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.akshara.identity.RoleCatalog;
import com.akshara.identity.UserService;
import com.akshara.identity.UserSummary;
import com.akshara.notifications.Channel;
import com.akshara.notifications.MessageTemplates;
import com.akshara.notifications.NotificationQueue;
import com.akshara.notifications.NotificationQueue.Recipient;
import com.akshara.notifications.NotificationQueue.Related;
import com.akshara.platform.TenantDirectory;
import com.akshara.shared.TenantContext;

/**
 * Tells a school's admins by email about its subscription: an invoice issued or paid, the school past due, suspended
 * or reactivated, a cancellation made or taken back, the renewal coming up and the trial ending. Every School Admin of
 * the school who can sign in gets one. The messages go through the school's outbox ({@link NotificationQueue}), in the
 * caller's transaction, so a change that rolls back sends nothing; they show in the school's message log. Each message
 * has a dedupe key, so the same news is never sent twice (a second run of the renewal job, a retried request).
 */
@Component
class BillingNotices {

    private static final Logger log = LoggerFactory.getLogger(BillingNotices.class);

    /** What the messages are about, in the message log. */
    static final String RELATED_TYPE = "school";

    private final NotificationQueue queue;
    private final UserService users;
    private final TenantDirectory tenants;
    private final SubscriptionRepository subscriptions;

    BillingNotices(NotificationQueue queue, UserService users, TenantDirectory tenants,
            SubscriptionRepository subscriptions) {
        this.queue = queue;
        this.users = users;
        this.tenants = tenants;
        this.subscriptions = subscriptions;
    }

    @EventListener
    void on(SubscriptionInvoiceIssued event) {
        send(MessageTemplates.BILLING_INVOICE_ISSUED, "invoice-issued:" + event.invoiceId(),
                Map.of("invoiceNo", event.invoiceNo(), "amount", BillingService.rupees(event.totalPaise()), "date",
                        event.dueDate().toString()));
    }

    @EventListener
    void on(SubscriptionInvoicePaid event) {
        send(MessageTemplates.BILLING_INVOICE_PAID, "invoice-paid:" + event.invoiceId(),
                Map.of("invoiceNo", event.invoiceNo(), "amount", BillingService.rupees(event.totalPaise()), "date",
                        event.paidOn().toString()));
    }

    @EventListener
    void on(SchoolSuspended event) {
        String reason = subscriptions.findByTenantId(event.tenantId()).map(Subscription::getSuspensionReason)
                .filter(r -> !r.isBlank()).orElse("Contact Akshara for the reason.");
        send(MessageTemplates.BILLING_SUSPENDED, "suspended:" + event.suspendedAt().toEpochMilli(),
                Map.of("reason", reason.endsWith(".") ? reason : reason + "."));
    }

    @EventListener
    void on(SchoolReactivated event) {
        send(MessageTemplates.BILLING_REACTIVATED, "reactivated:" + System.currentTimeMillis(), Map.of());
    }

    /** The school became past due on {@code day} (the renewal job, or the Super Admin). */
    void pastDue(LocalDate day) {
        send(MessageTemplates.BILLING_PAST_DUE, "past-due:" + day + ":" + System.currentTimeMillis(), Map.of());
    }

    /** A cancellation was made: the school works until {@code endsOn}. {@code by} says who cancelled. */
    void cancelScheduled(LocalDate endsOn, String by) {
        send(MessageTemplates.BILLING_CANCEL_SCHEDULED, "cancel:" + System.currentTimeMillis(),
                Map.of("date", endsOn == null ? "" : endsOn.toString(), "by", by));
    }

    /** A cancellation was taken back. {@code by} says who took it back. */
    void cancelWithdrawn(String by) {
        send(MessageTemplates.BILLING_CANCEL_WITHDRAWN, "cancel-withdrawn:" + System.currentTimeMillis(),
                Map.of("by", by));
    }

    /** The subscription renews on {@code renewsOn} with an invoice of about {@code amountPaise}. Once per period. */
    void renewalReminder(LocalDate renewsOn, long amountPaise) {
        send(MessageTemplates.BILLING_RENEWAL_REMINDER, "renewal-reminder:" + renewsOn,
                Map.of("date", renewsOn.toString(), "amount", BillingService.rupees(amountPaise)));
    }

    /** The free trial ends on {@code endsOn}. Once per trial end. */
    void trialEnding(LocalDate endsOn) {
        send(MessageTemplates.BILLING_TRIAL_ENDING, "trial-ending:" + endsOn, Map.of("date", endsOn.toString()));
    }

    /**
     * Queues the message for every active School Admin of the current school. The dedupe key is per person, so a key
     * that came back (the same invoice, the same renewal) is not sent again. A school with no School Admin left gets
     * nothing; that is logged, never an error, because billing must go on.
     */
    private void send(String template, String key, Map<String, String> params) {
        UUID tenantId = TenantContext.require();
        String school = tenants.findById(tenantId).map(t -> t.name()).orElse("Your school");
        List<UserSummary> admins = users.activeWithRole(RoleCatalog.SCHOOL_ADMIN).stream()
                .filter(u -> u.email() != null && u.email().contains("@")).toList();
        if (admins.isEmpty()) {
            log.info("No School Admin to tell about {} for school {}", template, tenantId);
            return;
        }
        Map<String, String> shared = new LinkedHashMap<>(params);
        shared.put("school", school);
        List<Recipient> recipients = admins.stream()
                .map(u -> new Recipient(Channel.EMAIL, u.email(), u.name(), Map.of(), null,
                        "billing:" + key + ":" + u.id()))
                .toList();
        queue.enqueue(template, MessageTemplates.ENGLISH, shared, new Related(RELATED_TYPE, tenantId, school),
                recipients);
    }
}
