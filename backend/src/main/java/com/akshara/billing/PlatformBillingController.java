package com.akshara.billing;

import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

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

import com.akshara.audit.AuditService.Actor;
import com.akshara.billing.BillingForms.ChangeForm;
import com.akshara.billing.BillingForms.DetailsForm;
import com.akshara.billing.BillingForms.PaymentForm;
import com.akshara.billing.BillingForms.ReasonForm;
import com.akshara.billing.BillingForms.StartForm;
import com.akshara.billing.BillingForms.StatusForm;
import com.akshara.billing.BillingViews.InvoiceView;
import com.akshara.billing.BillingViews.PlanView;
import com.akshara.billing.BillingViews.PlatformHealth;
import com.akshara.billing.BillingViews.RenewalRow;
import com.akshara.billing.BillingViews.SchoolAccount;
import com.akshara.platform.TenantDirectory;
import com.akshara.shared.ApiException;
import com.akshara.shared.CurrentUser;
import com.akshara.shared.TenantContext;

/**
 * The Super Admin's console: plans, renewals, platform health, and one school's subscription, invoices, payments and
 * status. Only platform admins reach {@code /api/platform/**}. The Super Admin has no school of their own, so each
 * school endpoint first selects the school in the path and works as it: its invoices are read and written under
 * row-level security, and every change is written to that school's audit trail with the Super Admin's name.
 */
@RestController
@RequestMapping("/api/platform")
@PreAuthorize("hasAuthority('platform.admin')")
public class PlatformBillingController {

    private final BillingAccounts accounts;
    private final BillingService billing;
    private final PlatformHealthService health;
    private final TenantDirectory tenants;

    PlatformBillingController(BillingAccounts accounts, BillingService billing, PlatformHealthService health,
            TenantDirectory tenants) {
        this.accounts = accounts;
        this.billing = billing;
        this.health = health;
        this.tenants = tenants;
    }

    @GetMapping("/health")
    public PlatformHealth health() {
        return health.health();
    }

    @GetMapping("/billing/plans")
    public List<PlanView> plans() {
        return accounts.plans();
    }

    @GetMapping("/billing/renewals")
    public List<RenewalRow> renewals() {
        return accounts.renewals();
    }

    @GetMapping("/billing/schools/{id}")
    public SchoolAccount account(@PathVariable UUID id) {
        return asSchool(id, accounts::account);
    }

    @PutMapping("/billing/schools/{id}/details")
    public SchoolAccount details(@PathVariable UUID id, @Valid @RequestBody DetailsForm request) {
        return asSchool(id, () -> billing.updateDetails(request, superAdmin()));
    }

    @PostMapping("/billing/schools/{id}/subscription")
    @ResponseStatus(HttpStatus.CREATED)
    public SchoolAccount start(@PathVariable UUID id, @Valid @RequestBody StartForm request) {
        return asSchool(id, () -> billing.start(request, superAdmin()));
    }

    @PutMapping("/billing/schools/{id}/subscription")
    public SchoolAccount change(@PathVariable UUID id, @Valid @RequestBody ChangeForm request) {
        return asSchool(id, () -> billing.change(request, superAdmin()));
    }

    @PostMapping("/billing/schools/{id}/invoices")
    @ResponseStatus(HttpStatus.CREATED)
    public InvoiceView issue(@PathVariable UUID id) {
        return asSchool(id, () -> billing.issueNext(superAdmin()));
    }

    @GetMapping("/billing/schools/{id}/invoices/{invoiceId}")
    public InvoiceView invoice(@PathVariable UUID id, @PathVariable UUID invoiceId) {
        return asSchool(id, () -> accounts.invoice(invoiceId));
    }

    @PostMapping("/billing/schools/{id}/invoices/{invoiceId}/payments")
    public InvoiceView pay(@PathVariable UUID id, @PathVariable UUID invoiceId,
            @Valid @RequestBody PaymentForm request) {
        return asSchool(id, () -> billing.recordPayment(invoiceId, request, superAdmin()));
    }

    @PostMapping("/billing/schools/{id}/invoices/{invoiceId}/cancel")
    public InvoiceView cancel(@PathVariable UUID id, @PathVariable UUID invoiceId,
            @Valid @RequestBody ReasonForm request) {
        return asSchool(id, () -> billing.cancelInvoice(invoiceId, request.reason(), superAdmin()));
    }

    @PostMapping("/billing/schools/{id}/status")
    public SchoolAccount status(@PathVariable UUID id, @Valid @RequestBody StatusForm request) {
        return asSchool(id, () -> billing.changeStatus(request.status(), superAdmin()));
    }

    @PostMapping("/billing/schools/{id}/suspend")
    public SchoolAccount suspend(@PathVariable UUID id, @Valid @RequestBody ReasonForm request) {
        return asSchool(id, () -> billing.suspend(request.reason(), superAdmin()));
    }

    @PostMapping("/billing/schools/{id}/reactivate")
    public SchoolAccount reactivate(@PathVariable UUID id) {
        return asSchool(id, () -> billing.reactivate(superAdmin()));
    }

    /**
     * Runs the work as the school in the path. The services open their transactions inside, so the connection is
     * stamped with this school. An unknown school is 404.
     */
    private <T> T asSchool(UUID tenantId, Supplier<T> work) {
        tenants.findById(tenantId).orElseThrow(() -> ApiException.notFound("School"));
        return TenantContext.runAs(tenantId, work);
    }

    private static Actor superAdmin() {
        return new Actor(CurrentUser.requireId(), CurrentUser.name().orElse("Super Admin"));
    }
}
