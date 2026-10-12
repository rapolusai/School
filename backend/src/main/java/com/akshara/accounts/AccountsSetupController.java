package com.akshara.accounts;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.akshara.accounts.AccountsForms.ApproversForm;
import com.akshara.accounts.AccountsForms.BudgetForm;
import com.akshara.accounts.AccountsForms.CostCentreForm;
import com.akshara.accounts.AccountsForms.HeadForm;
import com.akshara.accounts.AccountsForms.SettingsForm;
import com.akshara.accounts.AccountsForms.VendorForm;
import com.akshara.accounts.AccountsViews.BudgetsView;
import com.akshara.accounts.AccountsViews.CostCentreView;
import com.akshara.accounts.AccountsViews.HeadView;
import com.akshara.accounts.AccountsViews.SettingsView;
import com.akshara.accounts.AccountsViews.VendorLedger;
import com.akshara.accounts.AccountsViews.VendorView;

/** Settings, approvers, the chart of heads, cost centres, vendors and budgets. */
@RestController
@RequestMapping("/api/accounts")
@PreAuthorize(AccountsAccess.READ)
public class AccountsSetupController {

    private final AccountsSettingsService settings;
    private final ChartService chart;
    private final VendorService vendors;
    private final BudgetService budgets;

    AccountsSetupController(AccountsSettingsService settings, ChartService chart, VendorService vendors,
            BudgetService budgets) {
        this.settings = settings;
        this.chart = chart;
        this.vendors = vendors;
        this.budgets = budgets;
    }

    // ------------------------------------------------------------------ settings

    @GetMapping("/settings")
    @PreAuthorize(AccountsAccess.READ_OR_APPROVE)
    public SettingsView settings() {
        return settings.view();
    }

    @PutMapping("/settings")
    @PreAuthorize(AccountsAccess.MANAGE)
    public SettingsView saveSettings(@Valid @RequestBody SettingsForm form) {
        return settings.save(form, AccountsAccess.actor());
    }

    @PutMapping("/settings/approvers")
    @PreAuthorize(AccountsAccess.MANAGE)
    public SettingsView saveApprovers(@Valid @RequestBody ApproversForm form) {
        return settings.saveApprovers(form, AccountsAccess.actor());
    }

    // ------------------------------------------------------------------ heads

    @GetMapping("/heads")
    @PreAuthorize(AccountsAccess.READ_OR_APPROVE)
    public List<HeadView> heads() {
        return chart.heads();
    }

    @PostMapping("/heads")
    @PreAuthorize(AccountsAccess.MANAGE)
    @ResponseStatus(HttpStatus.CREATED)
    public HeadView createHead(@Valid @RequestBody HeadForm form) {
        return chart.createHead(form, AccountsAccess.actor());
    }

    @PostMapping("/heads/standard")
    @PreAuthorize(AccountsAccess.MANAGE)
    public List<HeadView> addStandardHeads() {
        return chart.addStandardHeads(AccountsAccess.actor());
    }

    @PutMapping("/heads/{id}")
    @PreAuthorize(AccountsAccess.MANAGE)
    public HeadView updateHead(@PathVariable UUID id, @Valid @RequestBody HeadForm form) {
        return chart.updateHead(id, form, AccountsAccess.actor());
    }

    // ------------------------------------------------------------------ cost centres

    @GetMapping("/cost-centres")
    @PreAuthorize(AccountsAccess.READ_OR_APPROVE)
    public List<CostCentreView> costCentres() {
        return chart.costCentres();
    }

    @PostMapping("/cost-centres")
    @PreAuthorize(AccountsAccess.MANAGE)
    @ResponseStatus(HttpStatus.CREATED)
    public CostCentreView createCostCentre(@Valid @RequestBody CostCentreForm form) {
        return chart.createCostCentre(form, AccountsAccess.actor());
    }

    @PutMapping("/cost-centres/{id}")
    @PreAuthorize(AccountsAccess.MANAGE)
    public CostCentreView updateCostCentre(@PathVariable UUID id, @Valid @RequestBody CostCentreForm form) {
        return chart.updateCostCentre(id, form, AccountsAccess.actor());
    }

    @DeleteMapping("/cost-centres/{id}")
    @PreAuthorize(AccountsAccess.MANAGE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteCostCentre(@PathVariable UUID id) {
        chart.deleteCostCentre(id, AccountsAccess.actor());
    }

    // ------------------------------------------------------------------ vendors

    @GetMapping("/vendors")
    @PreAuthorize(AccountsAccess.READ_OR_APPROVE)
    public List<VendorView> vendors() {
        return vendors.list();
    }

    @GetMapping("/vendors/{id}")
    @PreAuthorize(AccountsAccess.READ_OR_APPROVE)
    public VendorView vendor(@PathVariable UUID id) {
        return vendors.get(id);
    }

    @GetMapping("/vendors/{id}/ledger")
    public VendorLedger ledger(@PathVariable UUID id, @RequestParam LocalDate from, @RequestParam LocalDate to) {
        return vendors.ledger(id, from, to);
    }

    @PostMapping("/vendors")
    @PreAuthorize(AccountsAccess.MANAGE)
    @ResponseStatus(HttpStatus.CREATED)
    public VendorView createVendor(@Valid @RequestBody VendorForm form) {
        return vendors.create(form, AccountsAccess.actor());
    }

    @PutMapping("/vendors/{id}")
    @PreAuthorize(AccountsAccess.MANAGE)
    public VendorView updateVendor(@PathVariable UUID id, @Valid @RequestBody VendorForm form) {
        return vendors.update(id, form, AccountsAccess.actor());
    }

    @DeleteMapping("/vendors/{id}")
    @PreAuthorize(AccountsAccess.MANAGE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteVendor(@PathVariable UUID id) {
        vendors.delete(id, AccountsAccess.actor());
    }

    // ------------------------------------------------------------------ budgets

    @GetMapping("/budgets/{financialYear}")
    @PreAuthorize(AccountsAccess.READ_OR_APPROVE)
    public BudgetsView budgets(@PathVariable String financialYear) {
        return budgets.view(FinancialYear.parse(financialYear, "financialYear"));
    }

    @PutMapping("/budgets/{financialYear}")
    @PreAuthorize(AccountsAccess.MANAGE)
    public BudgetsView saveBudget(@PathVariable String financialYear, @Valid @RequestBody BudgetForm form) {
        return budgets.save(FinancialYear.parse(financialYear, "financialYear"), form, AccountsAccess.actor());
    }

    @DeleteMapping("/budgets/{financialYear}/{budgetId}")
    @PreAuthorize(AccountsAccess.MANAGE)
    public BudgetsView deleteBudget(@PathVariable String financialYear, @PathVariable UUID budgetId) {
        return budgets.delete(FinancialYear.parse(financialYear, "financialYear"), budgetId,
                AccountsAccess.actor());
    }
}
