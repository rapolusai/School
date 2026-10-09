package com.akshara.fees;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

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
import com.akshara.fees.FeeDuesService.PlanItem;
import com.akshara.fees.FeeForms.OrderForm;
import com.akshara.fees.FeeForms.VerifyForm;
import com.akshara.fees.FeeViews.CheckoutResult;
import com.akshara.fees.FeeViews.OrderView;
import com.akshara.fees.FeeViews.ReceiptView;
import com.akshara.fees.FeeViews.SandboxCheckout;
import com.akshara.fees.PaymentGateway.EventKind;
import com.akshara.fees.PaymentGateway.GatewayOrder;
import com.akshara.fees.PaymentGateway.OrderRequest;
import com.akshara.fees.PaymentGateway.WebhookEvent;
import com.akshara.fees.PaymentGateway.WebhookRequest;
import com.akshara.platform.TenantDirectory;
import com.akshara.platform.TenantView;
import com.akshara.shared.ApiException;
import com.akshara.shared.TenantContext;

/**
 * Online fee payments through a {@link PaymentGateway}. An order is made for chosen instalments; the payment is
 * recorded exactly once, by whichever comes first of the browser's verification (with the checkout signature) and the
 * gateway's signed webhook. Both lock the order row, and receipts are unique per gateway payment id, so replays and
 * races never record a payment twice.
 */
@Service
public class OnlinePaymentService {

    private static final Logger log = LoggerFactory.getLogger(OnlinePaymentService.class);

    /** Who an online receipt says collected it, and who the audit trail shows for webhook-recorded payments. */
    static final String ONLINE_COLLECTOR = "Online payment";

    /** What became of a webhook delivery. */
    public record WebhookResult(String outcome) {
    }

    public enum SandboxOutcome {
        SUCCESS, FAILURE
    }

    private final Map<String, PaymentGateway> gateways;
    private final PaymentProperties properties;
    private final SandboxPaymentGateway sandbox;
    private final PaymentOrderRepository orders;
    private final GatewayEventRepository gatewayEvents;
    private final FeeInstalmentRepository instalments;
    private final FeeDuesService duesService;
    private final FeePaymentService payments;
    private final StudentRoster roster;
    private final TenantDirectory tenants;
    private final AuditService audit;
    private final TransactionTemplate tx;
    private final JdbcTemplate jdbc;

    OnlinePaymentService(List<PaymentGateway> gateways, PaymentProperties properties, SandboxPaymentGateway sandbox,
            PaymentOrderRepository orders, GatewayEventRepository gatewayEvents, FeeInstalmentRepository instalments,
            FeeDuesService duesService, FeePaymentService payments, StudentRoster roster,
            TenantDirectory tenants, AuditService audit, PlatformTransactionManager transactionManager,
            JdbcTemplate jdbc) {
        this.gateways = gateways.stream().collect(Collectors.toMap(PaymentGateway::name, Function.identity()));
        this.properties = properties;
        this.sandbox = sandbox;
        this.orders = orders;
        this.gatewayEvents = gatewayEvents;
        this.instalments = instalments;
        this.duesService = duesService;
        this.payments = payments;
        this.roster = roster;
        this.tenants = tenants;
        this.audit = audit;
        this.tx = new TransactionTemplate(transactionManager);
        this.jdbc = jdbc;
    }

    // ------------------------------------------------------------------ orders

    /**
     * Starts an online payment of the chosen instalments' balance plus any late fee due today. The late fee is fixed
     * as of today, so paying tomorrow does not change the amount.
     */
    @Transactional
    public OrderView createOrder(UUID studentId, OrderForm form, Actor actor) {
        TenantContext.require();
        Actor by = Fees.actor(actor);
        StudentRoster.Entry student = roster.student(studentId);
        duesService.generateForStudent(studentId);
        LocalDate today = SchoolDay.today();
        List<PlanItem> plan = duesService.plan(studentId, form.instalmentIds(), true, today);
        long amount = plan.stream().mapToLong(PlanItem::outstandingPaise).sum();
        if (amount == 0) {
            throw Fees.conflict("Nothing due", "Nothing is due in the chosen instalments.");
        }
        PaymentGateway gateway = gateway(properties.gateway());
        UUID id = PaymentOrder.newId();
        GatewayOrder created = gateway.createOrder(new OrderRequest(id.toString(), amount, "INR",
                Map.of("orderId", id.toString())));
        List<UUID> instalmentIds = plan.stream().map(p -> p.instalment().getId()).distinct().toList();
        PaymentOrder order = orders.saveAndFlush(new PaymentOrder(id, studentId, gateway.name(), created.id(), amount,
                instalmentIds, today, by.id(), by.name()));
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("studentName", student.fullName());
        details.put("admissionNo", student.admissionNo());
        details.put("gateway", gateway.name());
        details.put("gatewayOrderId", created.id());
        details.put("amountPaise", amount);
        audit.record(by, "payment_order.created", "payment_order", id, details);
        return view(order, student.fullName());
    }

    /** An order; with {@code studentId}, only if it is that student's (404 otherwise). */
    @Transactional(readOnly = true)
    public OrderView order(UUID orderId, UUID studentId) {
        TenantContext.require();
        PaymentOrder order = orders.findById(orderId)
                .filter(o -> studentId == null || o.getStudentId().equals(studentId))
                .orElseThrow(() -> ApiException.notFound("Payment order"));
        return view(order, roster.student(order.getStudentId()).fullName());
    }

    /**
     * Records a payment the browser reports with the gateway's checkout signature. A bad signature is a 400 and
     * records nothing; verifying an order that is already paid with the same payment returns its receipt again.
     * With {@code studentId}, the order must be that student's (404 otherwise).
     */
    @Transactional
    public ReceiptView verify(UUID orderId, UUID studentId, VerifyForm form) {
        TenantContext.require();
        PaymentOrder order = orders.lock(orderId)
                .filter(o -> studentId == null || o.getStudentId().equals(studentId))
                .orElseThrow(() -> ApiException.notFound("Payment order"));
        PaymentGateway gateway = gateway(order.getGateway());
        String paymentId = form.gatewayPaymentId().trim();
        if (!gateway.verifyPaymentSignature(order.getGatewayOrderId(), paymentId, form.signature())) {
            throw ApiException.badRequest("The payment could not be verified.", "signature");
        }
        if (order.isPaid()) {
            if (paymentId.equals(order.getGatewayPaymentId())) {
                return payments.receipt(order.getReceiptId());
            }
            throw Fees.conflict("Already paid", "This order was already paid with another payment.");
        }
        return payments.view(record(order, paymentId));
    }

    /** Issues the receipt of a captured payment. The order is locked by the caller. */
    private Receipt record(PaymentOrder order, String gatewayPaymentId) {
        StudentRoster.Entry student = roster.student(order.getStudentId());
        List<PlanItem> all = duesService.plan(order.getStudentId(), null, true, order.getLateFeeAsOf());
        Set<UUID> chosen = new LinkedHashSet<>(order.getInstalmentIds());
        // The chosen instalments first; if someone paid them at the counter meanwhile, the money goes to what else
        // is owed, and anything left over is kept as an advance (the gateway has already taken it).
        List<PlanItem> plan = new ArrayList<>(all.stream().filter(p -> chosen.contains(p.instalment().getId()))
                .toList());
        plan.addAll(all.stream().filter(p -> !chosen.contains(p.instalment().getId())).toList());
        LocalDate today = SchoolDay.today();
        FeePaymentService.Payment payment = new FeePaymentService.Payment(PaymentMode.ONLINE, null, null, null,
                Receipt.ONLINE, order.getGateway(), gatewayPaymentId, null, today, Instant.now());
        Receipt receipt = payments.issue(student, plan, order.getAmountPaise(), true, payment,
                new Actor(null, ONLINE_COLLECTOR));
        order.paid(gatewayPaymentId, receipt.getId());
        orders.saveAndFlush(order);
        return receipt;
    }

    // ------------------------------------------------------------------ webhooks

    /**
     * Handles a gateway webhook. The signature is checked first: a bad one is a 400 and nothing is stored. Events for
     * unknown orders are ignored. Every event id is handled once; a replay answers DUPLICATE and changes nothing.
     */
    public WebhookResult webhook(String gatewayName, String payload, Map<String, String> headers) {
        PaymentGateway gateway = gateways.get(gatewayName == null ? "" : gatewayName.toLowerCase(Locale.ROOT));
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
            return new WebhookResult(GatewayEvent.IGNORED);
        }
        UUID tenantId = jdbc.queryForObject("select fees.payment_order_tenant(?, ?)", UUID.class, gateway.name(),
                event.gatewayOrderId());
        if (tenantId == null) {
            log.info("Ignoring {} webhook {} for an unknown order", gateway.name(), event.eventId());
            return new WebhookResult(GatewayEvent.IGNORED);
        }
        return TenantContext.runAs(tenantId, () -> handle(gateway, event));
    }

    private WebhookResult handle(PaymentGateway gateway, WebhookEvent event) {
        try {
            return tx.execute(status -> {
                if (gatewayEvents.existsByGatewayAndEventId(gateway.name(), event.eventId())) {
                    return new WebhookResult("DUPLICATE");
                }
                PaymentOrder order = orders.lockByGatewayOrder(gateway.name(), event.gatewayOrderId()).orElseThrow();
                String outcome = switch (event.kind()) {
                    case PAYMENT_CAPTURED -> captured(order, event);
                    case PAYMENT_FAILED -> failed(order, event);
                    case OTHER -> GatewayEvent.IGNORED;
                };
                gatewayEvents.saveAndFlush(new GatewayEvent(gateway.name(), event.eventId(), event.type(),
                        order.getId(), event.gatewayPaymentId(), outcome));
                return new WebhookResult(outcome);
            });
        } catch (DataIntegrityViolationException e) {
            // The same event delivered twice at once: the other delivery recorded it.
            return new WebhookResult("DUPLICATE");
        }
    }

    private String captured(PaymentOrder order, WebhookEvent event) {
        if (order.isPaid()) {
            if (event.gatewayPaymentId() != null && !event.gatewayPaymentId().equals(order.getGatewayPaymentId())) {
                log.warn("Order {} was paid again with another payment; refund it at the gateway", order.getId());
            }
            return GatewayEvent.ALREADY_RECORDED;
        }
        if (event.gatewayPaymentId() == null || event.amountPaise() != order.getAmountPaise()) {
            log.warn("Ignoring captured payment for order {}: amount or payment id does not match", order.getId());
            return GatewayEvent.IGNORED;
        }
        record(order, event.gatewayPaymentId());
        return GatewayEvent.RECORDED;
    }

    private String failed(PaymentOrder order, WebhookEvent event) {
        if (order.isPaid()) {
            return GatewayEvent.IGNORED;
        }
        String reason = event.failureReason() == null ? "Payment failed" : truncate(event.failureReason(), 200);
        order.failed(reason);
        orders.saveAndFlush(order);
        audit.record(new Actor(null, ONLINE_COLLECTOR), "payment_order.failed", "payment_order", order.getId(),
                Map.of("gatewayOrderId", order.getGatewayOrderId(), "reason", reason));
        return GatewayEvent.FAILED;
    }

    // ------------------------------------------------------------------ the sandbox checkout page

    /** What the sandbox checkout page shows. Only the order's creator or staff with fees.collect may open it. */
    @Transactional(readOnly = true)
    public SandboxCheckout sandboxCheckout(String gatewayOrderId, UUID userId, boolean staff) {
        TenantContext.require();
        PaymentOrder order = sandboxOrder(gatewayOrderId, userId, staff);
        TenantView school = tenants.findById(TenantContext.require()).orElseThrow();
        return new SandboxCheckout(order.getGatewayOrderId(), order.getId(), order.getAmountPaise(),
                order.getCurrency(), school.name(), roster.student(order.getStudentId()).fullName(),
                labels(order), order.getStatus());
    }

    /**
     * Pretends the payer finished the sandbox checkout. SUCCESS hands back a payment id and a valid signature for the
     * browser to verify, exactly like a real checkout (nothing is recorded yet). FAILURE delivers a signed
     * payment.failed webhook, which marks the order failed.
     */
    public CheckoutResult completeSandbox(String gatewayOrderId, SandboxOutcome outcome, UUID userId, boolean staff) {
        TenantContext.require();
        PaymentOrder order = tx.execute(status -> sandboxOrder(gatewayOrderId, userId, staff));
        if (order.isPaid()) {
            throw Fees.conflict("Already paid", "This order is already paid.");
        }
        String paymentId = sandbox.newPaymentId();
        if (outcome == SandboxOutcome.SUCCESS) {
            return new CheckoutResult(order.getId(), order.getGatewayOrderId(), "SUCCESS", paymentId,
                    sandbox.paymentSignature(order.getGatewayOrderId(), paymentId));
        }
        String payload = sandbox.webhookPayload(sandbox.newEventId(), "payment.failed", order.getGatewayOrderId(),
                paymentId, order.getAmountPaise(), "The payer cancelled the sandbox payment.");
        webhook(SandboxPaymentGateway.NAME, payload,
                Map.of(SandboxPaymentGateway.SIGNATURE_HEADER, sandbox.webhookSignature(payload)));
        return new CheckoutResult(order.getId(), order.getGatewayOrderId(), "FAILED", null, null);
    }

    private PaymentOrder sandboxOrder(String gatewayOrderId, UUID userId, boolean staff) {
        return orders.findByGatewayAndGatewayOrderId(SandboxPaymentGateway.NAME, gatewayOrderId)
                .filter(o -> staff || (userId != null && userId.equals(o.getCreatedBy())))
                .orElseThrow(() -> ApiException.notFound("Payment order"));
    }

    // ------------------------------------------------------------------ helpers

    private PaymentGateway gateway(String name) {
        PaymentGateway gateway = gateways.get(name);
        if (gateway == null) {
            throw new IllegalStateException("No payment gateway named " + name);
        }
        return gateway;
    }

    private OrderView view(PaymentOrder order, String studentName) {
        return new OrderView(order.getId(), order.getGateway(), order.getGatewayOrderId(), order.getAmountPaise(),
                order.getCurrency(), order.getStatus(), order.getStudentId(), studentName, labels(order),
                order.getReceiptId(), order.getFailureReason());
    }

    private List<String> labels(PaymentOrder order) {
        return instalments.findAllById(order.getInstalmentIds()).stream()
                .sorted(Comparator.comparing(FeeInstalment::getDueDate).thenComparing(FeeInstalment::getSeq))
                .map(FeeInstalment::getLabel)
                .toList();
    }

    private static String truncate(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max);
    }
}
