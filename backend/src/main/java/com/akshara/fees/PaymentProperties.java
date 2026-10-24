package com.akshara.fees;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Online payment settings. {@code gateway} picks the gateway for new orders. The sandbox secret signs sandbox
 * payments and webhooks; when it is empty a random one is made at startup (fine on one API instance; in AWS every task
 * gets the same secret from Secrets Manager). Real gateway keys never go in configuration files: they come from
 * Secrets Manager as environment variables (RAZORPAY_KEY_ID, RAZORPAY_KEY_SECRET, RAZORPAY_WEBHOOK_SECRET).
 */
@ConfigurationProperties("akshara.payments")
public record PaymentProperties(@DefaultValue("sandbox") String gateway, @DefaultValue Sandbox sandbox,
        @DefaultValue Razorpay razorpay) {

    public record Sandbox(@DefaultValue("") String secret) {
    }

    /**
     * Razorpay. {@code keyId} is public (the browser's checkout needs it); {@code keySecret} signs API calls and
     * checkout signatures; {@code webhookSecret} is the secret set on the webhook in Razorpay's dashboard.
     */
    public record Razorpay(
            @DefaultValue("") String keyId,
            @DefaultValue("") String keySecret,
            @DefaultValue("") String webhookSecret,
            @DefaultValue("https://api.razorpay.com") String baseUrl,
            @DefaultValue("PT10S") Duration timeout) {

        boolean configured() {
            return !keyId.isBlank() && !keySecret.isBlank() && !webhookSecret.isBlank();
        }
    }
}
