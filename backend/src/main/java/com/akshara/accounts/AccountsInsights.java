package com.akshara.accounts;

import java.time.LocalDate;
import java.time.YearMonth;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.akshara.accounts.AccountsViews.CashPosition;
import com.akshara.accounts.AccountsViews.FinanceCard;
import com.akshara.shared.TenantContext;

/** What other modules may read from accounts: this month's finance figures for the dashboards. */
@Service
@Transactional(readOnly = true)
public class AccountsInsights {

    private final FeeIncomeDayRepository feeDays;
    private final IncomeReceiptRepository receipts;
    private final ExpenseRepository expenses;
    private final PayrollCostRepository payrollCosts;
    private final PettyCashEntryRepository petty;
    private final BankService bank;

    AccountsInsights(FeeIncomeDayRepository feeDays, IncomeReceiptRepository receipts, ExpenseRepository expenses,
            PayrollCostRepository payrollCosts, PettyCashEntryRepository petty, BankService bank) {
        this.feeDays = feeDays;
        this.receipts = receipts;
        this.expenses = expenses;
        this.payrollCosts = payrollCosts;
        this.petty = petty;
        this.bank = bank;
    }

    /** Income, expense (bills approved or paid, payroll and petty spends) and cash in hand for the current month. */
    public FinanceCard financeCard() {
        TenantContext.require();
        LocalDate today = FinancialYear.today();
        YearMonth month = YearMonth.from(today);
        LocalDate from = month.atDay(1);
        LocalDate to = month.atEndOfMonth();
        long fee = feeDays.totalBetween(from, to);
        long other = receipts.findBetween(from, to).stream().filter(IncomeReceipt::issued)
                .mapToLong(IncomeReceipt::getAmountPaise).sum();
        long expense = expenses.findBetween(from, to).stream()
                .filter(e -> e.getStatus() == ExpenseStatus.APPROVED || e.getStatus() == ExpenseStatus.PAID)
                .mapToLong(Expense::getTotalPaise).sum();
        long payroll = payrollCosts.findBetween(from, to).stream().mapToLong(PayrollCost::costPaise).sum();
        long pettySpend = petty.findBetween(from, to).stream().filter(p -> p.getKind() == PettyKind.SPENT)
                .mapToLong(PettyCashEntry::getAmountPaise).sum();
        var submitted = expenses.findSubmitted();
        CashPosition cash = bank.cashPosition();
        return new FinanceCard(month.toString(), fee + other, fee, other, expense + payroll + pettySpend, payroll,
                cash.cashPaise() + cash.pettyCashPaise(), cash.bankPaise(), submitted.size(),
                submitted.stream().mapToLong(Expense::getTotalPaise).sum());
    }
}
