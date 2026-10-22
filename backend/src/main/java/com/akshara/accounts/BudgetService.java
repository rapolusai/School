package com.akshara.accounts;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.akshara.accounts.AccountsForms.BudgetForm;
import com.akshara.accounts.AccountsViews.BudgetCheck;
import com.akshara.accounts.AccountsViews.BudgetRow;
import com.akshara.accounts.AccountsViews.BudgetsView;
import com.akshara.audit.AuditService;
import com.akshara.audit.AuditService.Actor;
import com.akshara.shared.ApiException;
import com.akshara.shared.TenantContext;

/**
 * Yearly budgets per head, per month (spread evenly or entered), and actual against budget. "Actual" for an expense
 * head is its approved and paid expenses by expense date, plus petty cash spent under it and (for the salaries head)
 * the approved payroll cost; for an income head it is its receipts, plus fee receipts under the fee income heads.
 */
@Service
@Transactional
public class BudgetService {

    private final BudgetRepository budgets;
    private final BudgetMonthRepository months;
    private final HeadRepository heads;
    private final ExpenseRepository expenses;
    private final PettyCashEntryRepository petty;
    private final IncomeReceiptRepository receipts;
    private final FeeIncomeDayRepository feeDays;
    private final PayrollCostRepository payroll;
    private final AccountsSettingsService settings;
    private final AuditService audit;

    BudgetService(BudgetRepository budgets, BudgetMonthRepository months, HeadRepository heads,
            ExpenseRepository expenses, PettyCashEntryRepository petty, IncomeReceiptRepository receipts,
            FeeIncomeDayRepository feeDays, PayrollCostRepository payroll, AccountsSettingsService settings,
            AuditService audit) {
        this.budgets = budgets;
        this.months = months;
        this.heads = heads;
        this.expenses = expenses;
        this.petty = petty;
        this.receipts = receipts;
        this.feeDays = feeDays;
        this.payroll = payroll;
        this.settings = settings;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public BudgetsView view(FinancialYear year) {
        TenantContext.require();
        Map<UUID, Budget> byHead = budgets.findByYear(year.label()).stream()
                .collect(Collectors.toMap(Budget::getHeadId, Function.identity()));
        Map<UUID, List<BudgetMonth>> monthsByBudget = byHead.isEmpty() ? Map.of()
                : months.findByBudgets(byHead.values().stream().map(Budget::getId).toList()).stream()
                        .collect(Collectors.groupingBy(BudgetMonth::getBudgetId));
        Map<UUID, long[]> actuals = actuals(year);
        List<BudgetRow> rows = new ArrayList<>();
        long budgetTotal = 0;
        long actualTotal = 0;
        int near = 0;
        int over = 0;
        for (Head h : heads.findAllOrdered()) {
            Budget b = byHead.get(h.getId());
            long[] actual = actuals.getOrDefault(h.getId(), new long[12]);
            long actualSum = 0;
            for (long a : actual) {
                actualSum += a;
            }
            if (b == null && !h.isActive() && actualSum == 0) {
                continue;
            }
            List<Long> monthly = b == null ? AccountsMath.spreadEvenly(0) : monthsOf(b, monthsByBudget);
            long annual = b == null ? 0 : b.getAnnualPaise();
            String status = AccountsMath.budgetStatus(actualSum, annual);
            if (h.getKind() == HeadKind.EXPENSE) {
                budgetTotal += annual;
                actualTotal += actualSum;
                if ("NEAR".equals(status)) {
                    near++;
                } else if ("OVER".equals(status)) {
                    over++;
                }
            }
            List<Long> actualList = new ArrayList<>(12);
            for (long a : actual) {
                actualList.add(a);
            }
            rows.add(new BudgetRow(h.getId(), h.getCode(), h.getName(), h.getKind(), b == null ? null : b.getId(),
                    annual, b == null ? BudgetSpread.EVEN : b.getSpread(), monthly, actualSum, actualList,
                    AccountsMath.percentUsed(actualSum, annual), status));
        }
        return new BudgetsView(year.label(), settings.budgetMode(), rows, budgetTotal, actualTotal,
                AccountsMath.percentUsed(actualTotal, budgetTotal), near, over);
    }

    public BudgetsView save(FinancialYear year, BudgetForm form, Actor actor) {
        TenantContext.require();
        Head head = heads.findById(form.headId()).orElse(null);
        if (head == null) {
            throw ApiException.badRequest("Pick a head.", "headId");
        }
        List<Long> monthly;
        if (form.spread() == BudgetSpread.ENTERED) {
            if (form.months() == null || form.months().size() != 12) {
                throw ApiException.badRequest("Give an amount for each of the 12 months.", "months");
            }
            long sum = form.months().stream().mapToLong(Long::longValue).sum();
            if (sum != form.annualPaise()) {
                throw ApiException.badRequest("The months add up to a different amount than the year.", "months");
            }
            monthly = form.months();
        } else {
            monthly = AccountsMath.spreadEvenly(form.annualPaise());
        }
        Budget b = budgets.findByYearAndHead(year.label(), head.getId()).orElse(null);
        if (b == null) {
            b = budgets.save(new Budget(year.label(), head.getId(), form.annualPaise(), form.spread()));
        } else {
            b.update(form.annualPaise(), form.spread());
            months.deleteByBudget(b.getId());
        }
        budgets.flush();
        for (int i = 0; i < 12; i++) {
            months.save(new BudgetMonth(b.getId(), i + 1, monthly.get(i)));
        }
        months.flush();
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("financialYear", year.label());
        details.put("head", head.getName());
        details.put("annualPaise", form.annualPaise());
        details.put("spread", form.spread().name());
        audit.record(actor, "budget.saved", "budget", b.getId(), details);
        return view(year);
    }

    public BudgetsView delete(FinancialYear year, UUID budgetId, Actor actor) {
        TenantContext.require();
        Budget b = budgets.findById(budgetId).filter(x -> x.getFinancialYear().equals(year.label()))
                .orElseThrow(() -> ApiException.notFound("Budget"));
        months.deleteByBudget(b.getId());
        budgets.delete(b);
        budgets.flush();
        audit.record(actor, "budget.deleted", "budget", budgetId, Map.of("financialYear", year.label(),
                "head", ChartService.nameOf(headsById(), b.getHeadId())));
        return view(year);
    }

    /** Where the head's budget would stand with {@code extraPaise} more committed to it. */
    BudgetCheck check(UUID headId, FinancialYear year, long extraPaise) {
        long annual = budgets.findByYearAndHead(year.label(), headId).map(Budget::getAnnualPaise).orElse(0L);
        long committed = expenses.committedForHead(headId, year.label());
        long after = committed + extraPaise;
        return new BudgetCheck(annual, committed, after, AccountsMath.percentUsed(after, annual),
                AccountsMath.budgetStatus(after, annual), settings.budgetMode());
    }

    private List<Long> monthsOf(Budget b, Map<UUID, List<BudgetMonth>> monthsByBudget) {
        long[] out = new long[12];
        for (BudgetMonth m : monthsByBudget.getOrDefault(b.getId(), List.of())) {
            out[m.getMonthIndex() - 1] = m.getAmountPaise();
        }
        List<Long> list = new ArrayList<>(12);
        for (long v : out) {
            list.add(v);
        }
        return list;
    }

    /** Actual amounts per head and month index (0–11) in the year. */
    Map<UUID, long[]> actuals(FinancialYear year) {
        Map<UUID, long[]> out = new HashMap<>();
        for (Expense e : expenses.findCommittedInYear(year.label())) {
            add(out, e.getHeadId(), year.monthIndex(e.getExpenseDate()), e.getTotalPaise());
        }
        for (PettyCashEntry p : petty.findBetween(year.start(), year.end())) {
            if (p.getKind() == PettyKind.SPENT && p.getHeadId() != null) {
                add(out, p.getHeadId(), year.monthIndex(p.getEntryDate()), p.getAmountPaise());
            }
        }
        for (IncomeReceipt r : receipts.findBetween(year.start(), year.end())) {
            if (r.issued()) {
                add(out, r.getHeadId(), year.monthIndex(r.getReceivedOn()), r.getAmountPaise());
            }
        }
        heads.findBySystemCode("FEES").ifPresent(fees -> {
            for (FeeIncomeDay d : feeDays.findBetween(year.start(), year.end())) {
                add(out, fees.getId(), year.monthIndex(d.getDay()), d.getAmountPaise());
            }
        });
        heads.findBySystemCode("SALARIES").ifPresent(salaries -> {
            for (PayrollCost p : payroll.findBetween(year.start(), year.end())) {
                add(out, salaries.getId(), year.monthIndex(p.getMonth()), p.costPaise());
            }
        });
        return out;
    }

    private static void add(Map<UUID, long[]> out, UUID headId, int monthIndex, long paise) {
        if (monthIndex < 1) {
            return;
        }
        out.computeIfAbsent(headId, k -> new long[12])[monthIndex - 1] += paise;
    }

    private Map<UUID, Head> headsById() {
        return heads.findAll().stream().collect(Collectors.toMap(Head::getId, Function.identity()));
    }

    static Comparator<BudgetRow> byKindAndName() {
        return Comparator.comparing(BudgetRow::kind).thenComparing(BudgetRow::headName);
    }

    static LocalDate firstOf(FinancialYear year) {
        return year.start();
    }
}
