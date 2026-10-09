package com.akshara.fees;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import com.akshara.fees.OnlinePaymentService.WebhookResult;
import com.akshara.shared.ApiException;

/**
 * Payment gateway webhooks. Public (gateways do not sign in), so every request must carry a valid signature over its
 * exact body: a bad signature is a 400 and nothing is recorded. Replays of an event are answered 200 and ignored.
 */
@RestController
public class PaymentWebhookController {

    /** Larger bodies are not webhooks we know; Razorpay's are a few kilobytes. */
    static final int MAX_BODY = 64 * 1024;

    private final OnlinePaymentService online;

    public PaymentWebhookController(OnlinePaymentService online) {
        this.online = online;
    }

    @PostMapping("/api/public/payments/webhook/{gateway}")
    public WebhookResult webhook(@PathVariable String gateway, @RequestBody(required = false) String payload,
            @RequestHeader Map<String, String> headers) {
        if (payload == null || payload.length() > MAX_BODY) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Unreadable webhook", "The webhook could not be read.");
        }
        return online.webhook(gateway, payload, headers);
    }
}
