package com.akshara.billing;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.akshara.audit.AuditService;
import com.akshara.audit.AuditService.Actor;
import com.akshara.billing.BillingForms.SandboxOutcome;
import com.akshara.billing.BillingForms.VerifyForm;
import com.akshara.billing.BillingViews.InvoiceCheckoutResult;
import com.akshara.billing.BillingViews.InvoiceOrderView;
import com.akshara.billing.BillingViews.InvoiceView;
import com.akshara.billing.BillingViews.SandboxInvoiceCheckout;
import com.akshara.fees.PaymentGateway;
import com.akshara.fees.PaymentGateway.GatewayOrder;
import com.akshara.fees.PaymentGateway.OrderRequest;
import com.akshara.fees.PaymentGateway.WebhookEvent;
import com.akshara.fees.PaymentGateway.WebhookRequest;
import com.akshara.fees.SandboxPaymentGateway;
import com.akshara.platform.TenantDirectory;
import com.akshara.shared.ApiException;
import com.akshara.shared.CurrentUser;
import com.akshara.shared.TenantContext;

/**
 * A School Admin pays an open subscription invoice online, through Akshara's own gateway account
 * ({@link BillingGateways}). An order is made for the invoice's whole balance; the payment is recorded exactly once,
 * as an ONLINE payment on the invoice, by whichever comes first of the browser's verification (with the checkout
 * signature) and the gateway's signed webhook. Both lock the order row; invoice payments are unique per gateway payment
 * id; a school's orders for one invoice are made one at a time and an open order is reused, so two tabs or repeated
 * clicks never create two orders the school could both pay. Recording goes through the same path as the Super Admin's
 * manual payments ({@link BillingService#applyPayment}), so the invoice, the audit trail and
 * {@link SubscriptionInvoicePaid} behave exactly the same.
 */
@Service
public class OnlineInvoicePayments {

    private static final Logger log = LoggerFactory.getLogger(OnlineInvoicePayments.class);

    /** Who the invoice says recorded an online payment, and who the audit trail shows for webhook payments. */
    static final String ONLINE_RECORDER = "Online payment";

    /** What became of a webhook delivery. */
    public record WebhookResult(String outcome) {
    }

    private final BillingGateways gateways;
    private final InvoiceOrderRepository orders;
    private final InvoiceGatewayEventRepository gatewayEvents;
    private final InvoiceRepository invoices;
    private final BillingService billing;
    private final BillingAccounts accounts;
    private final BillingProperties properties;
    private final TenantDirectory tenants;
    private final AuditService audit;
    private final TransactionTemplate tx;
    private final JdbcTemplate jdbc;

    OnlineInvoicePayments(BillingGateways gateways, InvoiceOrderRepository orders,
            InvoiceGatewayEventRepository gatewayEvents, InvoiceRepository invoices, BillingService billing,
            BillingAccounts accounts, BillingProperties properties, TenantDirectory tenants, AuditService audit,
            PlatformTransactionManager transactionManager, JdbcTemplate jdbc) {
        this.gateways = gateways;
        this.orders = orders;
        this.gatewayEvents = gatewayEvents;
        this.invoices = invoices;
        this.billing = billing;
        this.accounts = accounts;
        this.properties = properties;
        this.tenants = tenants;
        this.audit = audit;
        this.tx = new TransactionTemplate(transactionManager);
        this.jdbc = jdbc;
    }

    // ------------------------------------------------------------------ orders

    /**
     * Starts an online payment of an invoice's whole balance, or returns the order already open for it (same gateway,
     * same amount): the gateway takes one payment per order, so the school cannot be charged twice for one invoice.
     */
    @Transactional
    public InvoiceOrderView createOrder(UUID invoiceId) {
        TenantContext.require();
        Actor by = signedIn();
        // One invoice's orders are made one at a time, so two tabs paying it share one order.
        jdbc.queryForObject("select 1 from (select pg_advisory_xact_lock(hashtextextended(?, 0))) l", Integer.class,
                "billing.invoice:" + invoiceId);
        Invoice invoice = invoices.findById(invoiceId).orElseThrow(() -> ApiException.notFound("Invoice"));
        if (invoice.isCancelled()) {
            throw conflict("Cancelled", "Invoice " + invoice.getInvoiceNo() + " is cancelled.");
        }
        if (invoice.isPaid() || invoice.balancePaise() == 0) {
            throw conflict("Already paid", "Invoice " + invoice.getInvoiceNo() + " is already paid.");
        }
        long amount = invoice.balancePaise();
        PaymentGateway gateway = gateways.active();
        Optional<InvoiceOrder> open = orders.findByInvoiceIdAndStatus(invoiceId, InvoiceOrder.CREATED).stream()
                .filter(o -> o.getGateway().equals(gateway.name()) && o.getAmountPaise() == amount)
                .findFirst();
        if (open.isPresent()) {
            return view(open.get(), invoice);
        }
        UUID id = InvoiceOrder.newId();
        GatewayOrder created = gateway.createOrder(new OrderRequest(id.toString(), amount, "INR",
                Map.of("orderId", id.toString(), "invoiceNo", invoice.getInvoiceNo())));
        InvoiceOrder order = orders.saveAndFlush(new InvoiceOrder(id, invoiceId, gateway.name(), created.id(), amount,
                by.id(), by.name()));
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("invoiceNo", invoice.getInvoiceNo());
        details.put("gateway", gateway.name());
        details.put("gatewayOrderId", created.id());
        details.put("amountPaise", amount);
        audit.record(by, "invoice_order.created", "invoice", invoiceId, details);
        return view(order, invoice);
    }

    @Transactional(readOnly = true)
    public InvoiceOrderView order(UUID orderId) {
        TenantContext.require();
        InvoiceOrder order = orders.findById(orderId).orElseThrow(() -> ApiException.notFound("Payment order"));
        return view(order, invoices.findById(order.getInvoiceId()).orElseThrow());
    }

    /**
     * Records a payment the browser reports with the gateway's checkout signature. A bad signature is a 400 and records
     * nothing; verifying an order already paid with the same payment returns the invoice again.
     */
    @Transactional
    public InvoiceView verify(UUID orderId, VerifyForm form) {
        TenantContext.require();
        InvoiceOrder order = orders.lock(orderId).orElseThrow(() -> ApiException.notFound("Payment order"));
        PaymentGateway gateway = gateway(order.getGateway());
        String paymentId = form.gatewayPaymentId().trim();
        if (!gateway.verifyPaymentSignature(order.getGatewayOrderId(), paymentId, form.signature())) {
            throw ApiException.badRequest("The payment could not be verified.", "signature");
        }
        if (order.isPaid()) {
            if (!paymentId.equals(order.getGatewayPaymentId())) {
                throw conflict("Already paid", "This order was already paid with another payment.");
            }
        } else {
            record(order, paymentId, signedIn());
        }
        return accounts.view(invoices.findById(order.getInvoiceId()).orElseThrow());
    }

    /**
     * Records a captured payment on the order's invoice. The order is locked by the caller. When the invoice is no
     * longer due in full (paid by hand or cancelled since the order was made), nothing is recorded on it: the order is
     * marked paid with the reason, for a refund at the gateway.
     */
    private void record(InvoiceOrder order, String gatewayPaymentId, Actor by) {
        Invoice invoice = invoices.lock(order.getInvoiceId()).orElseThrow();
        if (invoice.isCancelled() || invoice.balancePaise() < order.getAmountPaise()) {
            String reason = "Invoice " + invoice.getInvoiceNo() + " was no longer due; refund this payment.";
            order.paidNotApplied(gatewayPaymentId, reason);
            orders.saveAndFlush(order);
            log.warn("Online payment for order {} arrived after its invoice was settled; refund it at the gateway",
                    order.getId());
            audit.record(by, "invoice_order.refund_needed", "invoice", invoice.getId(),
                    Map.of("invoiceNo", invoice.getInvoiceNo(), "gatewayOrderId", order.getGatewayOrderId(),
                            "gatewayPaymentId", gatewayPaymentId, "amountPaise", order.getAmountPaise()));
            return;
        }
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("gateway", order.getGateway());
        extra.put("gatewayOrderId", order.getGatewayOrderId());
        InvoicePayment payment = billing.applyPayment(invoice, order.getAmountPaise(), PaymentMode.ONLINE,
                gatewayPaymentId, BillingDay.today(), by, null, ONLINE_RECORDER, extra);
        order.paid(gatewayPaymentId, payment.getId());
        orders.saveAndFlush(order);
    }

    // ------------------------------------------------------------------ webhooks

    /**
     * Handles a webhook from Akshara's gateway. The signature is checked first: a bad one is a 400 and nothing is
     * stored. Events for unknown orders are ignored. Every event id is handled once; a replay answers DUPLICATE.
     */
    public WebhookResult webhook(String gatewayName, String payload, Map<String, String> headers) {
        PaymentGateway gateway = gateways.named(gatewayName == null ? null : gatewayName.toLowerCase(Locale.ROOT));
        if (gateway == null) {
            throw ApiException.notFound("Payment gateway");
        }
        Map<String, String> lower = new HashMap<>();
        headers.forEach((k, v) -> lower.put(k.toLowerCase(Locale.ROOT), v));
        WebhookRequest request = new WebhookRequest(payload, lower);
        if (!gateway.verifyWebhookSignature(request)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Invalid signature",
                    "The webhook signature does not match.");
        }
        WebhookEvent event;
        try {
            event = gateway.parseWebhook(request);
        } catch (IllegalArgumentException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Unreadable webhook", "The webhook could not be read.");
        }
        if (event.gatewayOrderId() == null) {
            return new WebhookResult(InvoiceGatewayEvent.IGNORED);
        }
        UUID tenantId = jdbc.queryForObject("select billing.payment_order_tenant(?, ?)", UUID.class, gateway.name(),
                event.gatewayOrderId());
        if (tenantId == null) {
            log.info("Ignoring {} billing webhook {} for an unknown order", gateway.name(), event.eventId());
            return new WebhookResult(InvoiceGatewayEvent.IGNORED);
        }
        return TenantContext.runAs(tenantId, () -> handle(gateway, event));
    }

    private WebhookResult handle(PaymentGateway gateway, WebhookEvent event) {
        try {
            return tx.execute(status -> {
                if (gatewayEvents.existsByGatewayAndEventId(gateway.name(), event.eventId())) {
                    return new WebhookResult("DUPLICATE");
                }
                InvoiceOrder order = orders.lockByGatewayOrder(gateway.name(), event.gatewayOrderId()).orElseThrow();
                String outcome = switch (event.kind()) {
                    case PAYMENT_CAPTURED -> captured(order, event);
                    case PAYMENT_FAILED -> failed(order, event);
                    case OTHER -> InvoiceGatewayEvent.IGNORED;
                };
                gatewayEvents.saveAndFlush(new InvoiceGatewayEvent(gateway.name(), event.eventId(), event.type(),
                        order.getId(), event.gatewayPaymentId(), outcome));
                return new WebhookResult(outcome);
            });
        } catch (DataIntegrityViolationException e) {
            // The same event delivered twice at once: the other delivery recorded it.
            return new WebhookResult("DUPLICATE");
        }
    }

    private String captured(InvoiceOrder order, WebhookEvent event) {
        if (order.isPaid()) {
            if (event.gatewayPaymentId() != null && !event.gatewayPaymentId().equals(order.getGatewayPaymentId())) {
                log.warn("Invoice order {} was paid again with another payment; refund it at the gateway",
                        order.getId());
            }
            return InvoiceGatewayEvent.ALREADY_RECORDED;
        }
        if (event.gatewayPaymentId() == null || event.amountPaise() != order.getAmountPaise()) {
            log.warn("Ignoring captured payment for invoice order {}: amount or payment id does not match",
                    order.getId());
            return InvoiceGatewayEvent.IGNORED;
        }
        record(order, event.gatewayPaymentId(), new Actor(null, ONLINE_RECORDER));
        return InvoiceGatewayEvent.RECORDED;
    }

    private String failed(InvoiceOrder order, WebhookEvent event) {
        if (order.isPaid()) {
            return InvoiceGatewayEvent.IGNORED;
        }
        String reason = event.failureReason() == null ? "Payment failed" : truncate(event.failureReason(), 200);
        order.failed(reason);
        orders.saveAndFlush(order);
        audit.record(new Actor(null, ONLINE_RECORDER), "invoice_order.failed", "invoice", order.getInvoiceId(),
                Map.of("gatewayOrderId", order.getGatewayOrderId(), "reason", reason));
        return InvoiceGatewayEvent.FAILED;
    }

    // ------------------------------------------------------------------ the sandbox checkout page

    /** What the sandbox checkout page shows for one of the school's invoice orders. */
    @Transactional(readOnly = true)
    public SandboxInvoiceCheckout sandboxCheckout(String gatewayOrderId) {
        UUID tenantId = TenantContext.require();
        InvoiceOrder order = sandboxOrder(gatewayOrderId);
        Invoice invoice = invoices.findById(order.getInvoiceId()).orElseThrow();
        String school = tenants.findById(tenantId).map(t -> t.name()).orElse("");
        return new SandboxInvoiceCheckout(order.getGatewayOrderId(), order.getId(), invoice.getId(),
                invoice.getInvoiceNo(), order.getAmountPaise(), order.getCurrency(), properties.seller().name(), school,
                order.getStatus());
    }

    /**
     * Pretends the School Admin finished the sandbox checkout. SUCCESS hands back a payment id and a valid signature
     * for the browser to verify, exactly like a real checkout (nothing is recorded yet). FAILURE delivers a signed
     * payment.failed webhook, which marks the order failed.
     */
    public InvoiceCheckoutResult completeSandbox(String gatewayOrderId, SandboxOutcome outcome) {
        TenantContext.require();
        InvoiceOrder order = tx.execute(status -> sandboxOrder(gatewayOrderId));
        if (order.isPaid()) {
            throw conflict("Already paid", "This order is already paid.");
        }
        SandboxPaymentGateway sandbox = gateways.sandbox();
        String paymentId = sandbox.newPaymentId();
        if (outcome == SandboxOutcome.SUCCESS) {
            return new InvoiceCheckoutResult(order.getId(), order.getGatewayOrderId(), "SUCCESS", paymentId,
                    sandbox.paymentSignature(order.getGatewayOrderId(), paymentId));
        }
        String payload = sandbox.webhookPayload(sandbox.newEventId(), "payment.failed", order.getGatewayOrderId(),
                paymentId, order.getAmountPaise(), "The payer cancelled the sandbox payment.");
        webhook(SandboxPaymentGateway.NAME, payload,
                Map.of(SandboxPaymentGateway.SIGNATURE_HEADER, sandbox.webhookSignature(payload)));
        return new InvoiceCheckoutResult(order.getId(), order.getGatewayOrderId(), "FAILED", null, null);
    }

    /**
     * A sandbox order of the school. Only while the sandbox is the live gateway: once Akshara switches to Razorpay, a
     * sandbox order left open must not be "paid" without money.
     */
    private InvoiceOrder sandboxOrder(String gatewayOrderId) {
        if (!SandboxPaymentGateway.NAME.equals(gateways.active().name())) {
            throw ApiException.notFound("Payment order");
        }
        return orders.findByGatewayAndGatewayOrderId(SandboxPaymentGateway.NAME, gatewayOrderId)
                .orElseThrow(() -> ApiException.notFound("Payment order"));
    }

    // ------------------------------------------------------------------ helpers

    private PaymentGateway gateway(String name) {
        PaymentGateway gateway = gateways.named(name);
        if (gateway == null) {
            throw new IllegalStateException("No billing payment gateway named " + name);
        }
        return gateway;
    }

    private InvoiceOrderView view(InvoiceOrder order, Invoice invoice) {
        PaymentGateway gateway = gateways.named(order.getGateway());
        return new InvoiceOrderView(order.getId(), invoice.getId(), invoice.getInvoiceNo(), order.getGateway(),
                order.getGatewayOrderId(), order.getAmountPaise(), order.getCurrency(), order.getStatus(),
                order.getFailureReason(), gateway == null ? null : gateway.checkoutKey(), properties.seller().name());
    }

    /** The signed-in School Admin. */
    private static Actor signedIn() {
        return new Actor(CurrentUser.id().orElse(null), CurrentUser.name().orElse(ONLINE_RECORDER));
    }

    private static ApiException conflict(String title, String detail) {
        return new ApiException(HttpStatus.CONFLICT, title, detail);
    }

    private static String truncate(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max);
    }
}
