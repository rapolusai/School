package com.akshara.billing;

import java.util.UUID;

import jakarta.validation.Valid;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.akshara.billing.BillingForms.DetailsForm;
import com.akshara.billing.BillingViews.InvoiceView;
import com.akshara.billing.BillingViews.Notice;
import com.akshara.billing.BillingViews.SchoolBilling;

/**
 * A school's own billing with Akshara: its plan, trial, subscription and invoices (billing.read, School Admins), and
 * its billing details (also settings.manage). Schools never pay here: the Super Admin records payments.
 */
@RestController
@RequestMapping("/api/billing")
public class SchoolBillingController {

    static final String READ = "hasAuthority('billing.read')";
    static final String MANAGE = "hasAuthority('billing.read') and hasAuthority('settings.manage')";

    private final BillingAccounts accounts;
    private final BillingService billing;

    SchoolBillingController(BillingAccounts accounts, BillingService billing) {
        this.accounts = accounts;
        this.billing = billing;
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
}
