package com.akshara.fees;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * A pretend gateway for development, tests and demos. It makes no network calls: orders and payment ids are random,
 * and signatures are HMAC-SHA256 with the configured sandbox secret, exactly as Razorpay signs them
 * ({@code order_id + "|" + payment_id} for the checkout, the raw body for webhooks). Webhook bodies follow Razorpay's
 * shape, plus an {@code id} for the event (Razorpay sends that in the {@code X-Razorpay-Event-Id} header).
 */
@Component
public class SandboxPaymentGateway implements PaymentGateway {

    public static final String NAME = "sandbox";
    public static final String SIGNATURE_HEADER = "X-Sandbox-Signature";

    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";

    private final byte[] secret;
    private final SecureRandom random = new SecureRandom();
    private final JsonMapper json;

    SandboxPaymentGateway(PaymentProperties properties, JsonMapper json) {
        String configured = properties.sandbox() == null ? "" : properties.sandbox().secret();
        if (configured == null || configured.isBlank()) {
            byte[] generated = new byte[32];
            random.nextBytes(generated);
            this.secret = generated;
        } else {
            this.secret = configured.getBytes(StandardCharsets.UTF_8);
        }
        this.json = json;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public GatewayOrder createOrder(OrderRequest request) {
        return new GatewayOrder("order_SBX" + randomId(14), request.amountPaise(), request.currency());
    }

    @Override
    public boolean verifyPaymentSignature(String gatewayOrderId, String gatewayPaymentId, String signature) {
        return matches(sign(gatewayOrderId + "|" + gatewayPaymentId), signature);
    }

    @Override
    public boolean verifyWebhookSignature(WebhookRequest request) {
        return request.payload() != null && matches(sign(request.payload()), request.header(SIGNATURE_HEADER));
    }

    @Override
    public WebhookEvent parseWebhook(WebhookRequest request) {
        JsonNode root;
        try {
            root = json.readTree(request.payload());
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Not a JSON webhook", e);
        }
        String eventId = text(root.path("id"));
        String type = text(root.path("event"));
        JsonNode payment = root.path("payload").path("payment").path("entity");
        if (eventId == null || type == null) {
            throw new IllegalArgumentException("Webhook without id or event");
        }
        EventKind kind = switch (type) {
            case "payment.captured" -> EventKind.PAYMENT_CAPTURED;
            case "payment.failed" -> EventKind.PAYMENT_FAILED;
            default -> EventKind.OTHER;
        };
        return new WebhookEvent(eventId, type, kind, text(payment.path("order_id")), text(payment.path("id")),
                payment.path("amount").asLong(0), text(payment.path("error_description")));
    }

    // ------------------------------------------------------------------ what the sandbox checkout does

    /** A new payment id, as the gateway would assign. */
    String newPaymentId() {
        return "pay_SBX" + randomId(14);
    }

    /** The checkout signature for a successful payment. */
    String paymentSignature(String gatewayOrderId, String gatewayPaymentId) {
        return sign(gatewayOrderId + "|" + gatewayPaymentId);
    }

    /** A webhook body in Razorpay's shape. */
    String webhookPayload(String eventId, String event, String gatewayOrderId, String gatewayPaymentId,
            long amountPaise, String errorDescription) {
        Map<String, Object> entity = new LinkedHashMap<>();
        entity.put("id", gatewayPaymentId);
        entity.put("entity", "payment");
        entity.put("amount", amountPaise);
        entity.put("currency", "INR");
        entity.put("status", "payment.captured".equals(event) ? "captured" : "failed");
        entity.put("order_id", gatewayOrderId);
        if (errorDescription != null) {
            entity.put("error_description", errorDescription);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", eventId);
        body.put("entity", "event");
        body.put("event", event);
        body.put("contains", java.util.List.of("payment"));
        body.put("payload", Map.of("payment", Map.of("entity", entity)));
        return json.writeValueAsString(body);
    }

    /** The webhook signature header value for a body. */
    String webhookSignature(String payload) {
        return sign(payload);
    }

    String newEventId() {
        return "evt_SBX" + randomId(14);
    }

    // ------------------------------------------------------------------ helpers

    private String sign(String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
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

    private String randomId(int length) {
        StringBuilder id = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            id.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return id.toString();
    }

    private static String text(JsonNode node) {
        return node == null || node.isMissingNode() || node.isNull() ? null : node.asString();
    }
}
