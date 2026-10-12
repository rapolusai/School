package com.akshara.accounts;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.akshara.accounts.AccountsForms.CancelForm;
import com.akshara.accounts.AccountsForms.IncomeForm;
import com.akshara.accounts.AccountsForms.PettyCashForm;
import com.akshara.accounts.AccountsViews.FeeReconciliation;
import com.akshara.accounts.AccountsViews.IncomeReceiptView;
import com.akshara.accounts.AccountsViews.IncomeRow;
import com.akshara.accounts.AccountsViews.PayrollCostView;
import com.akshara.accounts.AccountsViews.PettyCashBook;

/** Other income with receipts, the petty cash book, fee income reconciliation and payroll cost. */
@RestController
@RequestMapping("/api/accounts")
@PreAuthorize(AccountsAccess.READ)
public class IncomeController {

    private final IncomeService income;
    private final PettyCashService pettyCash;
    private final AccountsEvents events;

    IncomeController(IncomeService income, PettyCashService pettyCash, AccountsEvents events) {
        this.income = income;
        this.pettyCash = pettyCash;
        this.events = events;
    }

    @GetMapping("/income")
    public List<IncomeRow> income(@RequestParam(required = false) LocalDate from,
            @RequestParam(required = false) LocalDate to) {
        return income.list(from, to);
    }

    @GetMapping("/income/{id}")
    public IncomeReceiptView receipt(@PathVariable UUID id) {
        return income.get(id);
    }

    @PostMapping("/income")
    @PreAuthorize(AccountsAccess.MANAGE)
    @ResponseStatus(HttpStatus.CREATED)
    public IncomeReceiptView record(@Valid @RequestBody IncomeForm form) {
        return income.record(form, AccountsAccess.actor());
    }

    @PostMapping("/income/{id}/cancel")
    @PreAuthorize(AccountsAccess.MANAGE)
    public IncomeReceiptView cancel(@PathVariable UUID id, @Valid @RequestBody CancelForm form) {
        return income.cancel(id, form, AccountsAccess.actor());
    }

    @GetMapping("/petty-cash")
    public PettyCashBook pettyCash(@RequestParam LocalDate from, @RequestParam LocalDate to) {
        return pettyCash.book(from, to);
    }

    @PostMapping("/petty-cash")
    @PreAuthorize(AccountsAccess.MANAGE)
    @ResponseStatus(HttpStatus.CREATED)
    public PettyCashBook recordPettyCash(@Valid @RequestBody PettyCashForm form) {
        return pettyCash.record(form, AccountsAccess.actor());
    }

    @GetMapping("/fee-reconciliation")
    public FeeReconciliation feeReconciliation(@RequestParam LocalDate from, @RequestParam LocalDate to) {
        return events.feeReconciliation(from, to);
    }

    @PostMapping("/fee-reconciliation/rebuild")
    @PreAuthorize(AccountsAccess.MANAGE)
    public FeeReconciliation rebuild(@RequestParam LocalDate from, @RequestParam LocalDate to) {
        return events.rebuildFeeIncome(from, to, AccountsAccess.actor());
    }

    @GetMapping("/payroll-costs")
    public List<PayrollCostView> payrollCosts(@RequestParam(required = false) String financialYear) {
        return events.payrollCosts(FinancialYear.parseOrCurrent(financialYear, "financialYear"));
    }
}
