package com.akshara.billing;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Akshara as the seller on GST invoices, and billing rules. The seller defaults are obvious placeholders (an "XXXXX"
 * GSTIN that cannot belong to anyone): each environment sets the real name, address, state and GSTIN.
 *
 * @param sacCode          SAC 998315, "Hosting and information technology infrastructure provisioning services",
 *                         which covers application service provisioning (software run for the customer in a hosted
 *                         environment, i.e. software as a service). To be confirmed by Akshara's tax adviser.
 * @param invoicePrefix    1 to 3 capital letters; invoice numbers look like AKS/26-27/000001 (16 characters at most)
 * @param paymentTermsDays an invoice is due this many days after its date
 * @param renewalWindowDays a paid school shows as "due for renewal" this many days before its next renewal
 * @param trialWarningDays a trial shows as "ending" (Super Admin list, school admins' banner) this many days before it
 *                         ends
 * @param renewals         the daily renewal job: when renewal invoices are raised and how long a past-due school
 *                         keeps working before it is suspended
 * @param payments         how schools pay their invoices online: Akshara's own gateway account, separate from the
 *                         schools' fee gateway ({@code akshara.payments})
 */
@ConfigurationProperties("akshara.billing")
public record BillingProperties(
        @DefaultValue Seller seller,
        @DefaultValue("998315") String sacCode,
        @DefaultValue("AKS") String invoicePrefix,
        @DefaultValue("15") int paymentTermsDays,
        @DefaultValue("30") int renewalWindowDays,
        @DefaultValue("7") int trialWarningDays,
        @DefaultValue Renewals renewals,
        @DefaultValue Payments payments) {

    /** The placeholder GSTIN: "XXXXX" is not a valid PAN, so it can never be a real registration. */
    public static final String PLACEHOLDER_GSTIN = "36XXXXX0000X1ZX";

    public BillingProperties {
        if (!invoicePrefix.matches("^[A-Z]{1,3}$")) {
            throw new IllegalStateException("akshara.billing.invoice-prefix must be 1 to 3 capital letters");
        }
        if (!Gstin.isStateCode(seller.stateCode())) {
            throw new IllegalStateException("akshara.billing.seller.state-code must be a two-digit GST state code");
        }
        if (paymentTermsDays < 0 || renewalWindowDays < 1 || trialWarningDays < 1) {
            throw new IllegalStateException("akshara.billing day counts are out of range");
        }
        if (renewals.leadDays() < 0 || renewals.leadDays() > renewalWindowDays || renewals.graceDays() < 0
                || renewals.trialGraceDays() < 0) {
            throw new IllegalStateException("akshara.billing.renewals day counts are out of range");
        }
    }

    /**
     * The daily renewal job ({@code BillingRenewalJob}).
     *
     * @param leadDays       the next period's invoice is raised this many days before it starts (0: on its first
     *                       day, the day after the current period ends); at most {@code renewalWindowDays}
     * @param graceDays      a school past due because of an unpaid invoice is suspended once the invoice is more than
     *                       this many days past its due date (and after the Super Admin last reactivated it)
     * @param trialGraceDays a school whose trial ended without a paid subscription is suspended once the trial ended
     *                       more than this many days ago (and after its last reactivation)
     */
    public record Renewals(
            @DefaultValue("0") int leadDays,
            @DefaultValue("15") int graceDays,
            @DefaultValue("7") int trialGraceDays) {
    }

    /**
     * Online payment of subscription invoices. The money is Akshara's, so these are Akshara's own gateway keys, never a
     * school's: {@code gateway} is "sandbox" (default: no network, no money) or "razorpay". The sandbox secret signs
     * sandbox payments and webhooks (random at startup when empty). Razorpay's keys come from Secrets Manager as
     * environment variables (BILLING_RAZORPAY_KEY_ID, BILLING_RAZORPAY_KEY_SECRET, BILLING_RAZORPAY_WEBHOOK_SECRET); the
     * API refuses to start with gateway "razorpay" and any of them missing.
     */
    public record Payments(
            @DefaultValue("sandbox") String gateway,
            @DefaultValue("") String sandboxSecret,
            @DefaultValue Razorpay razorpay) {
    }

    /** Akshara's Razorpay account. {@code keyId} is public (the browser's checkout needs it); the others are secret. */
    public record Razorpay(
            @DefaultValue("") String keyId,
            @DefaultValue("") String keySecret,
            @DefaultValue("") String webhookSecret,
            @DefaultValue("https://api.razorpay.com") String baseUrl,
            @DefaultValue("PT10S") java.time.Duration timeout) {
    }

    public record Seller(
            @DefaultValue("Akshara School Cloud (sample seller)") String name,
            @DefaultValue("Sample address - set BILLING_SELLER_ADDRESS") String address,
            @DefaultValue("36") String stateCode,
            @DefaultValue(PLACEHOLDER_GSTIN) String gstin) {

        /** True while the seller still has the placeholder GSTIN: such invoices are marked as samples. */
        public boolean placeholder() {
            return PLACEHOLDER_GSTIN.equals(gstin) || !Gstin.isValid(gstin);
        }
    }
}
