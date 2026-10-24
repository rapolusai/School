package com.akshara.fees;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import com.akshara.fees.PaymentGateway.EventKind;
import com.akshara.fees.PaymentGateway.OrderRequest;
import com.akshara.fees.PaymentGateway.WebhookRequest;
import com.akshara.shared.ApiException;
import com.sun.net.httpserver.HttpServer;

import tools.jackson.databind.json.JsonMapper;

/** The Razorpay adapter against a fake Razorpay API on localhost: orders, checkout signatures and webhooks. */
class RazorpayPaymentGatewayTest {

    private static final String KEY_ID = "rzp_test_abc123";
    private static final String KEY_SECRET = "test-key-secret";
    private static final String WEBHOOK_SECRET = "test-webhook-secret";

    private final JsonMapper json = JsonMapper.builder().build();
    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void createsAnOrderWithBasicAuthAndTheAmountInPaise() throws Exception {
        AtomicReference<String> auth = new AtomicReference<>();
        AtomicReference<String> body = new AtomicReference<>();
        String base = fakeRazorpay(200, exchange -> {
            auth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            return "{\"id\":\"order_Q1w2e3\",\"amount\":1187000,\"currency\":\"INR\",\"status\":\"created\"}";
        });
        RazorpayPaymentGateway gateway = gateway("razorpay", base, KEY_ID);

        PaymentGateway.GatewayOrder order = gateway.createOrder(
                new OrderRequest("our-order-id", 1_187_000, "INR", Map.of("orderId", "our-order-id")));

        assertThat(order.id()).isEqualTo("order_Q1w2e3");
        assertThat(order.amountPaise()).isEqualTo(1_187_000);
        assertThat(auth.get()).isEqualTo("Basic " + Base64.getEncoder()
                .encodeToString((KEY_ID + ":" + KEY_SECRET).getBytes(StandardCharsets.UTF_8)));
        assertThat(json.readTree(body.get()).path("amount").asLong()).isEqualTo(1_187_000);
        assertThat(json.readTree(body.get()).path("receipt").asString()).isEqualTo("our-order-id");
        assertThat(gateway.checkoutKey()).as("the browser needs the public key id").isEqualTo(KEY_ID);
    }

    @Test
    void aRefusedOrderAWrongAmountOrNoAnswerIsA502WithoutDetails() throws Exception {
        String refused = fakeRazorpay(401, exchange -> "{\"error\":{\"code\":\"BAD_REQUEST_ERROR\"}}");
        assertBadGateway(gateway("razorpay", refused, KEY_ID));
        stop();

        String otherAmount = fakeRazorpay(200, exchange -> "{\"id\":\"order_x\",\"amount\":1,\"currency\":\"INR\"}");
        assertBadGateway(gateway("razorpay", otherAmount, KEY_ID));
        stop();
        server = null;

        assertBadGateway(gateway("razorpay", "http://127.0.0.1:9", KEY_ID));
    }

    @Test
    void checksTheCheckoutSignatureOverOrderIdAndPaymentId() {
        RazorpayPaymentGateway gateway = gateway("sandbox", "http://127.0.0.1:9", KEY_ID);
        String good = RazorpayPaymentGateway.hmac(KEY_SECRET, "order_Q1w2e3|pay_Z9x8c7");

        assertThat(gateway.verifyPaymentSignature("order_Q1w2e3", "pay_Z9x8c7", good)).isTrue();
        assertThat(gateway.verifyPaymentSignature("order_Q1w2e3", "pay_OTHER", good)).isFalse();
        assertThat(gateway.verifyPaymentSignature("order_Q1w2e3", "pay_Z9x8c7", "forged")).isFalse();
        assertThat(gateway.verifyPaymentSignature("order_Q1w2e3", "pay_Z9x8c7", null)).isFalse();
    }

    @Test
    void readsSignedWebhooksAndRejectsUnsignedOnes() {
        RazorpayPaymentGateway gateway = gateway("sandbox", "http://127.0.0.1:9", KEY_ID);
        String payload = """
                {"entity":"event","event":"payment.captured","payload":{"payment":{"entity":
                {"id":"pay_Z9x8c7","order_id":"order_Q1w2e3","amount":1187000,"status":"captured"}}}}""";
        WebhookRequest signed = new WebhookRequest(payload, Map.of(
                "x-razorpay-signature", RazorpayPaymentGateway.hmac(WEBHOOK_SECRET, payload),
                "x-razorpay-event-id", "evt_1"));

        assertThat(gateway.verifyWebhookSignature(signed)).isTrue();
        assertThat(gateway.verifyWebhookSignature(new WebhookRequest(payload + " ", signed.headers()))).isFalse();
        assertThat(gateway.verifyWebhookSignature(new WebhookRequest(payload, Map.of("x-razorpay-event-id", "e"))))
                .isFalse();

        PaymentGateway.WebhookEvent event = gateway.parseWebhook(signed);
        assertThat(event.eventId()).isEqualTo("evt_1");
        assertThat(event.kind()).isEqualTo(EventKind.PAYMENT_CAPTURED);
        assertThat(event.gatewayOrderId()).isEqualTo("order_Q1w2e3");
        assertThat(event.gatewayPaymentId()).isEqualTo("pay_Z9x8c7");
        assertThat(event.amountPaise()).isEqualTo(1_187_000);
    }

    @Test
    void orderPaidCountsAsCapturedAndPaymentFailedAsFailed() {
        RazorpayPaymentGateway gateway = gateway("sandbox", "http://127.0.0.1:9", KEY_ID);
        assertThat(kindOf(gateway, "order.paid")).isEqualTo(EventKind.PAYMENT_CAPTURED);
        assertThat(kindOf(gateway, "payment.failed")).isEqualTo(EventKind.PAYMENT_FAILED);
        assertThat(kindOf(gateway, "refund.created")).isEqualTo(EventKind.OTHER);
        assertThatThrownBy(() -> gateway.parseWebhook(new WebhookRequest("{\"event\":\"order.paid\"}", Map.of())))
                .as("Razorpay always sends the event id header").isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void refusesToStartAsTheActiveGatewayWithoutItsKeys() {
        assertThatThrownBy(() -> gateway("razorpay", "http://127.0.0.1:9", ""))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("RAZORPAY_KEY_ID");
        // Not the active gateway: it may exist without keys (it then refuses to create orders).
        RazorpayPaymentGateway idle = gateway("sandbox", "http://127.0.0.1:9", "");
        assertThat(idle.checkoutKey()).isNull();
        assertBadGateway(idle);
    }

    private static EventKind kindOf(RazorpayPaymentGateway gateway, String type) {
        return gateway.parseWebhook(new WebhookRequest("{\"event\":\"" + type + "\",\"payload\":{}}",
                Map.of("x-razorpay-event-id", "evt_" + type))).kind();
    }

    private static void assertBadGateway(RazorpayPaymentGateway gateway) {
        assertThatThrownBy(() -> gateway.createOrder(new OrderRequest("r", 100, "INR", Map.of())))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.status()).isEqualTo(HttpStatus.BAD_GATEWAY));
    }

    private RazorpayPaymentGateway gateway(String active, String baseUrl, String keyId) {
        PaymentProperties properties = new PaymentProperties(active, new PaymentProperties.Sandbox(""),
                new PaymentProperties.Razorpay(keyId, keyId.isEmpty() ? "" : KEY_SECRET,
                        keyId.isEmpty() ? "" : WEBHOOK_SECRET, baseUrl, Duration.ofSeconds(2)));
        return new RazorpayPaymentGateway(properties, json);
    }

    private interface Responder {
        String respond(com.sun.net.httpserver.HttpExchange exchange) throws IOException;
    }

    private String fakeRazorpay(int status, Responder responder) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/orders", exchange -> {
            byte[] answer = responder.respond(exchange).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, answer.length);
            exchange.getResponseBody().write(answer);
            exchange.close();
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }
}
