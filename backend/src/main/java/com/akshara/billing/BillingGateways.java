package com.akshara.billing;

import java.util.Map;

import org.springframework.stereotype.Component;

import com.akshara.fees.PaymentGateway;
import com.akshara.fees.PaymentProperties;
import com.akshara.fees.RazorpayPaymentGateway;
import com.akshara.fees.SandboxPaymentGateway;

import tools.jackson.databind.json.JsonMapper;

/**
 * The payment gateways that collect subscription invoices: the same {@link PaymentGateway} implementations the fees
 * module uses, but built here with Akshara's own keys ({@code akshara.billing.payments}), so an invoice is never paid
 * into a school's account and a school's fee payment never into Akshara's. Both gateways always exist, so an order made
 * through one can still be verified after switching to the other; {@code gateway} picks the one for new orders, or is
 * "off": no online payments at all (production before Akshara's live Razorpay keys, so that no school can mark its own
 * invoice paid through the sandbox), and invoices are paid by bank transfer.
 */
@Component
class BillingGateways {

    private final SandboxPaymentGateway sandbox;
    private final RazorpayPaymentGateway razorpay;
    private final Map<String, PaymentGateway> byName;
    private final String active;

    BillingGateways(BillingProperties properties, JsonMapper json) {
        BillingProperties.Payments payments = properties.payments();
        BillingProperties.Razorpay keys = payments.razorpay();
        this.sandbox = SandboxPaymentGateway.withSecret(payments.sandboxSecret(), json);
        this.razorpay = RazorpayPaymentGateway.withKeys(new PaymentProperties.Razorpay(keys.keyId(), keys.keySecret(),
                keys.webhookSecret(), keys.baseUrl(), keys.timeout()), json);
        this.byName = Map.of(SandboxPaymentGateway.NAME, sandbox, RazorpayPaymentGateway.NAME, razorpay);
        this.active = payments.gateway();
        if (!OFF.equals(active) && !byName.containsKey(active)) {
            throw new IllegalStateException("akshara.billing.payments.gateway must be sandbox, razorpay or off");
        }
        if (RazorpayPaymentGateway.NAME.equals(active) && !razorpay.configured()) {
            throw new IllegalStateException("akshara.billing.payments.gateway is razorpay but BILLING_RAZORPAY_KEY_ID, "
                    + "BILLING_RAZORPAY_KEY_SECRET or BILLING_RAZORPAY_WEBHOOK_SECRET is not set");
        }
    }

    /** {@code akshara.billing.payments.gateway} value that switches online payment of invoices off. */
    static final String OFF = "off";

    /** The gateway for new orders, or null when online payments are off. */
    PaymentGateway active() {
        return byName.get(active);
    }

    /** Whether schools can pay their invoices online now. */
    boolean enabled() {
        return !OFF.equals(active);
    }

    /** A gateway by its name, or null. */
    PaymentGateway named(String name) {
        return name == null ? null : byName.get(name);
    }

    SandboxPaymentGateway sandbox() {
        return sandbox;
    }
}
