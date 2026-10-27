package com.akshara.fees;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import com.akshara.shared.ApiException;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Razorpay (https://razorpay.com/docs/api/orders/): orders are created with {@code POST /v1/orders} using HTTP Basic
 * with the key id and secret; the browser's Razorpay Checkout returns {@code razorpay_payment_id} and
 * {@code razorpay_signature}, which is HMAC-SHA256 of {@code order_id + "|" + payment_id} with the key secret; webhooks
 * carry {@code X-Razorpay-Signature} (HMAC-SHA256 of the raw body with the webhook secret) and
 * {@code X-Razorpay-Event-Id}.
 *
 * <p>The bean always exists, so orders made through Razorpay can still be verified after switching back to the
 * sandbox; with {@code akshara.payments.gateway=razorpay} the API refuses to start unless all three keys are set.
 * Nothing secret, and no payer detail, is ever logged.
 */
@Component
public class RazorpayPaymentGateway implements PaymentGateway {

    public static final String NAME = "razorpay";
    public static final String SIGNATURE_HEADER = "X-Razorpay-Signature";
    public static final String EVENT_ID_HEADER = "X-Razorpay-Event-Id";

    private static final Logger log = LoggerFactory.getLogger(RazorpayPaymentGateway.class);

    private final PaymentProperties.Razorpay config;
    private final JsonMapper json;
    private final HttpClient http;

    @Autowired
    RazorpayPaymentGateway(PaymentProperties properties, JsonMapper json) {
        this(properties.razorpay(), json);
        if (NAME.equals(properties.gateway()) && !config.configured()) {
            throw new IllegalStateException("akshara.payments.gateway is razorpay but RAZORPAY_KEY_ID, "
                    + "RAZORPAY_KEY_SECRET or RAZORPAY_WEBHOOK_SECRET is not set");
        }
    }

    private RazorpayPaymentGateway(PaymentProperties.Razorpay config, JsonMapper json) {
        this.config = config;
        this.json = json;
        this.http = HttpClient.newBuilder().connectTimeout(config.timeout()).build();
    }

    /**
     * A Razorpay client with keys of its own, for another Razorpay account than the schools' fee payments (Akshara's
     * own account, which collects the schools' subscription invoices). Not a Spring bean; the caller checks that the
     * keys are set when it needs them.
     */
    public static RazorpayPaymentGateway withKeys(PaymentProperties.Razorpay keys, JsonMapper json) {
        return new RazorpayPaymentGateway(keys, json);
    }

    /** Whether the key id, key secret and webhook secret are all set. */
    public boolean configured() {
        return config.configured();
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String checkoutKey() {
        return config.keyId().isBlank() ? null : config.keyId();
    }

    @Override
    public GatewayOrder createOrder(OrderRequest request) {
        if (!config.configured()) {
            throw unavailable();
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("amount", request.amountPaise());
        body.put("currency", request.currency());
        body.put("receipt", request.receipt());
        body.put("notes", request.notes());
        String credentials = Base64.getEncoder().encodeToString(
                (config.keyId() + ":" + config.keySecret()).getBytes(StandardCharsets.UTF_8));
        HttpRequest call = HttpRequest.newBuilder(URI.create(config.baseUrl().replaceAll("/+$", "") + "/v1/orders"))
                .timeout(config.timeout())
                .header("Authorization", "Basic " + credentials)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
                .build();
        HttpResponse<String> response;
        try {
            response = http.send(call, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            log.warn("Razorpay could not be reached ({})", e.getClass().getSimpleName());
            throw unavailable();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw unavailable();
        }
        if (response.statusCode() / 100 != 2) {
            log.warn("Razorpay refused an order: HTTP {}", response.statusCode());
            throw unavailable();
        }
        JsonNode order;
        try {
            order = json.readTree(response.body());
        } catch (RuntimeException e) {
            log.warn("Razorpay answered an order with a body that is not JSON");
            throw unavailable();
        }
        String id = text(order.path("id"));
        long amount = order.path("amount").asLong(-1);
        if (id == null || amount != request.amountPaise()) {
            log.warn("Razorpay answered an order without an id or with another amount");
            throw unavailable();
        }
        String currency = text(order.path("currency"));
        return new GatewayOrder(id, amount, currency == null ? request.currency() : currency);
    }

    @Override
    public boolean verifyPaymentSignature(String gatewayOrderId, String gatewayPaymentId, String signature) {
        return config.configured() && matches(hmac(config.keySecret(), gatewayOrderId + "|" + gatewayPaymentId),
                signature);
    }

    @Override
    public boolean verifyWebhookSignature(WebhookRequest request) {
        return config.configured() && request.payload() != null
                && matches(hmac(config.webhookSecret(), request.payload()), request.header(SIGNATURE_HEADER));
    }

    /**
     * {@code payment.captured} and {@code order.paid} both mean the money was taken (the service records it once per
     * order); {@code payment.failed} marks the order failed; anything else is ignored.
     */
    @Override
    public WebhookEvent parseWebhook(WebhookRequest request) {
        JsonNode root;
        try {
            root = json.readTree(request.payload());
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Not a JSON webhook", e);
        }
        String eventId = request.header(EVENT_ID_HEADER);
        String type = text(root.path("event"));
        if (eventId == null || eventId.isBlank() || type == null) {
            throw new IllegalArgumentException("Webhook without an event id or event");
        }
        JsonNode payment = root.path("payload").path("payment").path("entity");
        EventKind kind = switch (type) {
            case "payment.captured", "order.paid" -> EventKind.PAYMENT_CAPTURED;
            case "payment.failed" -> EventKind.PAYMENT_FAILED;
            default -> EventKind.OTHER;
        };
        return new WebhookEvent(eventId.trim(), type, kind, text(payment.path("order_id")), text(payment.path("id")),
                payment.path("amount").asLong(0), text(payment.path("error_description")));
    }

    private static ApiException unavailable() {
        return new ApiException(HttpStatus.BAD_GATEWAY, "Payment gateway unavailable",
                "Online payment is not available right now. Try again in a few minutes, or pay at the school office.");
    }

    static String hmac(String secret, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("HMAC-SHA256 is not available", e);
        }
    }

    /** Constant-time comparison, so response timing reveals nothing about the expected signature. */
    private static boolean matches(String expected, String given) {
        if (given == null) {
            return false;
        }
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                given.trim().getBytes(StandardCharsets.UTF_8));
    }

    private static String text(JsonNode node) {
        return node == null || node.isMissingNode() || node.isNull() ? null : node.asString();
    }
}
