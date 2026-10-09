package com.akshara.fees;

import java.util.Map;

/**
 * A payment gateway, modelled on Razorpay's orders API: the server creates an order for an amount, the browser's
 * checkout collects the money and hands back a payment id with a signature, and the gateway also posts signed webhooks.
 * Implementations are Spring beans; the one used for new orders is chosen by {@code akshara.payments.gateway}.
 *
 * <p>Only {@link SandboxPaymentGateway} exists. A Razorpay adapter would implement this interface with key id and
 * secret read from Secrets Manager (injected as environment variables), call {@code POST /v1/orders} in
 * {@link #createOrder}, and check {@code X-Razorpay-Signature} with HMAC-SHA256 of the webhook secret; see
 * docs/api/phase-1-fees.md.
 */
public interface PaymentGateway {

    /** What to create an order for. {@code receipt} is our own order id, echoed back by the gateway. */
    record OrderRequest(String receipt, long amountPaise, String currency, Map<String, String> notes) {
    }

    /** An order created at the gateway. */
    record GatewayOrder(String id, long amountPaise, String currency) {
    }

    /** A webhook as received: the raw body (signatures cover the exact bytes) and headers with lower-case names. */
    record WebhookRequest(String payload, Map<String, String> headers) {

        public String header(String name) {
            return headers.get(name.toLowerCase(java.util.Locale.ROOT));
        }
    }

    enum EventKind {
        PAYMENT_CAPTURED, PAYMENT_FAILED, OTHER
    }

    /** What a verified webhook says. {@code eventId} is unique per delivery, so replays can be recognised. */
    record WebhookEvent(String eventId, String type, EventKind kind, String gatewayOrderId, String gatewayPaymentId,
            long amountPaise, String failureReason) {
    }

    /** Short lower-case name used in URLs and stored on orders, e.g. "sandbox" or "razorpay". */
    String name();

    /** Creates an order at the gateway. Must not record anything locally. */
    GatewayOrder createOrder(OrderRequest request);

    /** Whether the signature the checkout returned matches this order and payment. */
    boolean verifyPaymentSignature(String gatewayOrderId, String gatewayPaymentId, String signature);

    /** Whether the webhook's signature header matches its body. */
    boolean verifyWebhookSignature(WebhookRequest request);

    /** Reads a webhook whose signature has been verified. Throws IllegalArgumentException when it cannot be read. */
    WebhookEvent parseWebhook(WebhookRequest request);
}
