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
 */
@ConfigurationProperties("akshara.billing")
public record BillingProperties(
        @DefaultValue Seller seller,
        @DefaultValue("998315") String sacCode,
        @DefaultValue("AKS") String invoicePrefix,
        @DefaultValue("15") int paymentTermsDays,
        @DefaultValue("30") int renewalWindowDays,
        @DefaultValue("7") int trialWarningDays) {

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
