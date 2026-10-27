package com.akshara.billing;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import com.akshara.billing.OnlineInvoicePayments.WebhookResult;
import com.akshara.shared.ApiException;

/**
 * Webhooks from Akshara's own payment gateway account (subscription invoices). A separate address from the schools' fee
 * webhooks, because it is another account with another webhook secret. Public (gateways do not sign in), so every
 * request must carry a valid signature over its exact body: a bad signature is a 400 and nothing is recorded.
 */
@RestController
public class BillingWebhookController {

    /** Larger bodies are not webhooks we know; Razorpay's are a few kilobytes. */
    static final int MAX_BODY = 64 * 1024;

    private final OnlineInvoicePayments online;

    BillingWebhookController(OnlineInvoicePayments online) {
        this.online = online;
    }

    @PostMapping("/api/public/billing/webhook/{gateway}")
    public WebhookResult webhook(@PathVariable String gateway, @RequestBody(required = false) String payload,
            @RequestHeader Map<String, String> headers) {
        if (payload == null || payload.length() > MAX_BODY) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Unreadable webhook", "The webhook could not be read.");
        }
        return online.webhook(gateway, payload, headers);
    }
}
