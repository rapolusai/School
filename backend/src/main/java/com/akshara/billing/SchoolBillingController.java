package com.akshara.billing;

import java.util.UUID;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.akshara.billing.BillingForms.DetailsForm;
import com.akshara.billing.BillingForms.SandboxForm;
import com.akshara.billing.BillingForms.VerifyForm;
import com.akshara.billing.BillingViews.InvoiceCheckoutResult;
import com.akshara.billing.BillingViews.InvoiceOrderView;
import com.akshara.billing.BillingViews.InvoiceView;
import com.akshara.billing.BillingViews.Notice;
import com.akshara.billing.BillingViews.SandboxInvoiceCheckout;
import com.akshara.billing.BillingViews.SchoolBilling;

/**
 * A school's own billing with Akshara: its plan, trial, subscription and invoices (billing.read, School Admins), its
 * billing details (also settings.manage), and paying an open invoice online through Akshara's payment gateway (also
 * settings.manage). Payments that reach Akshara's bank account outside the app are still recorded by the Super Admin.
 */
@RestController
@RequestMapping("/api/billing")
public class SchoolBillingController {

    static final String READ = "hasAuthority('billing.read')";
    static final String MANAGE = "hasAuthority('billing.read') and hasAuthority('settings.manage')";

    private final BillingAccounts accounts;
    private final BillingService billing;
    private final OnlineInvoicePayments online;

    SchoolBillingController(BillingAccounts accounts, BillingService billing, OnlineInvoicePayments online) {
        this.accounts = accounts;
        this.billing = billing;
        this.online = online;
    }

    @GetMapping
    @PreAuthorize(READ)
    public SchoolBilling billing() {
        return accounts.schoolBilling();
    }

    @GetMapping("/notice")
    @PreAuthorize(READ)
    public Notice notice() {
        return accounts.notice();
    }

    @GetMapping("/invoices/{id}")
    @PreAuthorize(READ)
    public InvoiceView invoice(@PathVariable UUID id) {
        return accounts.invoice(id);
    }

    @PutMapping("/details")
    @PreAuthorize(MANAGE)
    public SchoolBilling details(@Valid @RequestBody DetailsForm request) {
        return billing.updateOwnDetails(request);
    }

    // ------------------------------------------------------------------ paying an invoice online

    /** Starts (or reuses) an online payment of the invoice's whole balance. */
    @PostMapping("/invoices/{id}/orders")
    @PreAuthorize(MANAGE)
    @ResponseStatus(HttpStatus.CREATED)
    public InvoiceOrderView pay(@PathVariable UUID id) {
        return online.createOrder(id);
    }

    @GetMapping("/orders/{orderId}")
    @PreAuthorize(MANAGE)
    public InvoiceOrderView order(@PathVariable UUID orderId) {
        return online.order(orderId);
    }

    /** Records the payment the gateway's checkout reported, after checking its signature. */
    @PostMapping("/orders/{orderId}/verify")
    @PreAuthorize(MANAGE)
    public InvoiceView verify(@PathVariable UUID orderId, @Valid @RequestBody VerifyForm request) {
        return online.verify(orderId, request);
    }

    /** The sandbox gateway's checkout page (no money moves). */
    @GetMapping("/sandbox/orders/{gatewayOrderId}")
    @PreAuthorize(MANAGE)
    public SandboxInvoiceCheckout sandboxCheckout(@PathVariable String gatewayOrderId) {
        return online.sandboxCheckout(gatewayOrderId);
    }

    @PostMapping("/sandbox/orders/{gatewayOrderId}/complete")
    @PreAuthorize(MANAGE)
    public InvoiceCheckoutResult sandboxComplete(@PathVariable String gatewayOrderId,
            @Valid @RequestBody SandboxForm request) {
        return online.completeSandbox(gatewayOrderId, request.outcome());
    }
}
