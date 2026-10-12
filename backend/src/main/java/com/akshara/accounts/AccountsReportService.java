package com.akshara.accounts;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.akshara.accounts.AccountsCsv.Download;
import com.akshara.accounts.AccountsCsv.Money;
import com.akshara.accounts.AccountsViews.Book;
import com.akshara.accounts.AccountsViews.BookRow;
import com.akshara.accounts.AccountsViews.BudgetRow;
import com.akshara.accounts.AccountsViews.BudgetsView;
import com.akshara.accounts.AccountsViews.DayBook;
import com.akshara.accounts.AccountsViews.DayBookRow;
import com.akshara.accounts.AccountsViews.GstRow;
import com.akshara.accounts.AccountsViews.GstSummary;
import com.akshara.accounts.AccountsViews.HeadLine;
import com.akshara.accounts.AccountsViews.HeadWiseExpense;
import com.akshara.accounts.AccountsViews.HeadWiseRow;
import com.akshara.accounts.AccountsViews.IncomeVsExpense;
import com.akshara.accounts.AccountsViews.MonthLine;
import com.akshara.accounts.AccountsViews.ReceiptsAndPayments;
import com.akshara.accounts.AccountsViews.VendorLedger;
import com.akshara.accounts.AccountsViews.VendorLedgerRow;
import com.akshara.shared.TenantContext;

/**
 * The accounts reports, each as a view and as a CSV. Income is counted when received (fee income per day, other
 * income by receipt date), expenses by their bill date once approved or paid, payroll by its month and petty cash
 * spends by entry date; receipts and payments and the books are cash-basis (by payment date).
 */
@Service
@Transactional(readOnly = true)
public class AccountsReportService {

    private final ExpenseRepository expenses;
    private final IncomeReceiptRepository receipts;
    private final FeeIncomeDayRepository feeDays;
    private final PayrollCostRepository payrollCosts;
    private final PettyCashEntryRepository petty;
    private final VendorRepository vendors;
    private final ChartService chart;
    private final BudgetService budgets;
    private final BankService bank;
    private final VendorService vendorService;

    AccountsReportService(ExpenseRepository expenses, IncomeReceiptRepository receipts,
            FeeIncomeDayRepository feeDays, PayrollCostRepository payrollCosts, PettyCashEntryRepository petty,
            VendorRepository vendors, ChartService chart, BudgetService budgets, BankService bank,
            VendorService vendorService) {
        this.expenses = expenses;
        this.receipts = receipts;
        this.feeDays = feeDays;
        this.payrollCosts = payrollCosts;
        this.petty = petty;
        this.vendors = vendors;
        this.chart = chart;
        this.budgets = budgets;
        this.bank = bank;
        this.vendorService = vendorService;
    }

    // ------------------------------------------------------------------ income vs expense

    public IncomeVsExpense incomeVsExpense(FinancialYear year) {
        TenantContext.require();
        long[] fee = new long[13];
        long[] other = new long[13];
        long[] expense = new long[13];
        long[] payroll = new long[13];
        long[] pettySpend = new long[13];
        for (FeeIncomeDay d : feeDays.findBetween(year.start(), year.end())) {
            fee[year.monthIndex(d.getDay())] += d.getAmountPaise();
        }
        for (IncomeReceipt r : receipts.findBetween(year.start(), year.end())) {
            if (r.issued()) {
                other[year.monthIndex(r.getReceivedOn())] += r.getAmountPaise();
            }
        }
        for (Expense e : expenses.findCommittedInYear(year.label())) {
            expense[year.monthIndex(e.getExpenseDate())] += e.getTotalPaise();
        }
        for (PayrollCost p : payrollCosts.findBetween(year.start(), year.end())) {
            payroll[year.monthIndex(p.getMonth())] += p.costPaise();
        }
        for (PettyCashEntry p : petty.findBetween(year.start(), year.end())) {
            if (p.getKind() == PettyKind.SPENT) {
                pettySpend[year.monthIndex(p.getEntryDate())] += p.getAmountPaise();
            }
        }
        List<MonthLine> months = new ArrayList<>();
        List<java.time.YearMonth> labels = year.months();
        for (int m = 1; m <= 12; m++) {
            months.add(line(labels.get(m - 1).toString(), fee[m], other[m], expense[m], payroll[m],
                    pettySpend[m]));
        }
        MonthLine totals = line("TOTAL", sum(fee), sum(other), sum(expense), sum(payroll), sum(pettySpend));
        return new IncomeVsExpense(year.label(), months, totals);
    }

    private static MonthLine line(String month, long fee, long other, long expense, long payroll, long pettySpend) {
        long income = fee + other;
        long total = expense + payroll + pettySpend;
        return new MonthLine(month, fee, other, income, expense, payroll, pettySpend, total, income - total);
    }

    private static long sum(long[] values) {
        long s = 0;
        for (long v : values) {
            s += v;
        }
        return s;
    }

    public Download incomeVsExpenseCsv(FinancialYear year) {
        IncomeVsExpense r = incomeVsExpense(year);
        AccountsCsv csv = new AccountsCsv("Month", "Fee income", "Other income", "Total income", "Expenses",
                "Payroll", "Petty cash", "Total expense", "Net");
        List<MonthLine> all = new ArrayList<>(r.months());
        all.add(r.totals());
        for (MonthLine m : all) {
            csv.row(m.month(), new Money(m.feeIncomePaise()), new Money(m.otherIncomePaise()),
                    new Money(m.incomePaise()), new Money(m.expensePaise()), new Money(m.payrollPaise()),
                    new Money(m.pettyCashPaise()), new Money(m.totalExpensePaise()), new Money(m.netPaise()));
        }
        return csv.download("income-vs-expense-" + year.label() + ".csv");
    }

    // ------------------------------------------------------------------ receipts and payments

    /** Cash basis: what came in and went out of cash and bank in the period, by head. */
    public ReceiptsAndPayments receiptsAndPayments(LocalDate from, LocalDate to) {
        TenantContext.require();
        AccountsAccess.period(from, to);
        Map<UUID, Head> heads = chart.byId();
        Map<UUID, long[]> in = new LinkedHashMap<>();
        Map<UUID, long[]> out = new LinkedHashMap<>();
        UUID feesHead = chart.feesHeadId().orElse(null);
        for (FeeIncomeDay d : feeDays.findBetween(from, to)) {
            if (d.getAmountPaise() != 0) {
                add(in, feesHead, d.getAmountPaise(), d.getReceiptCount());
            }
        }
        for (IncomeReceipt r : receipts.findBetween(from, to)) {
            if (r.issued()) {
                add(in, r.getHeadId(), r.getAmountPaise(), 1);
            }
        }
        for (Expense e : expenses.findPaidBetween(from, to)) {
            add(out, e.getHeadId(), e.getPaidPaise(), 1);
        }
        for (PettyCashEntry p : petty.findBetween(from, to)) {
            if (p.getKind() == PettyKind.SPENT) {
                add(out, p.getHeadId(), p.getAmountPaise(), 1);
            }
        }
        List<HeadLine> receiptLines = lines(in, heads);
        List<HeadLine> paymentLines = lines(out, heads);
        long receiptsTotal = receiptLines.stream().mapToLong(HeadLine::amountPaise).sum();
        long paymentsTotal = paymentLines.stream().mapToLong(HeadLine::amountPaise).sum();
        long opening = bank.cashAndBank(from.minusDays(1));
        return new ReceiptsAndPayments(from, to, opening, receiptLines, receiptsTotal, paymentLines, paymentsTotal,
                opening + receiptsTotal - paymentsTotal);
    }

    private static void add(Map<UUID, long[]> map, UUID headId, long paise, long count) {
        long[] t = map.computeIfAbsent(headId, k -> new long[2]);
        t[0] += paise;
        t[1] += count;
    }

    private static List<HeadLine> lines(Map<UUID, long[]> map, Map<UUID, Head> heads) {
        List<HeadLine> list = new ArrayList<>();
        map.forEach((id, t) -> list.add(new HeadLine(id, ChartService.codeOf(heads, id),
                id == null ? "Fees" : ChartService.nameOf(heads, id), t[0], t[1])));
        list.sort((a, b) -> Long.compare(b.amountPaise(), a.amountPaise()));
        return list;
    }

    public Download receiptsAndPaymentsCsv(LocalDate from, LocalDate to) {
        ReceiptsAndPayments r = receiptsAndPayments(from, to);
        AccountsCsv csv = new AccountsCsv("Side", "Code", "Head", "Count", "Amount");
        csv.row("Opening balance", "", "Cash and bank", "", new Money(r.openingPaise()));
        for (HeadLine l : r.receipts()) {
            csv.row("Receipt", l.code(), l.name(), l.count(), new Money(l.amountPaise()));
        }
        csv.row("Receipts", "", "Total", "", new Money(r.receiptsPaise()));
        for (HeadLine l : r.payments()) {
            csv.row("Payment", l.code(), l.name(), l.count(), new Money(l.amountPaise()));
        }
        csv.row("Payments", "", "Total", "", new Money(r.paymentsPaise()));
        csv.row("Closing balance", "", "Cash and bank", "", new Money(r.closingPaise()));
        return csv.download("receipts-and-payments-" + from + "-" + to + ".csv");
    }

    // ------------------------------------------------------------------ budget vs actual

    public BudgetsView budgetVsActual(FinancialYear year) {
        return budgets.view(year);
    }

    public Download budgetVsActualCsv(FinancialYear year) {
        BudgetsView v = budgets.view(year);
        AccountsCsv csv = new AccountsCsv("Code", "Head", "Kind", "Budget", "Actual", "Variance", "Used %",
                "Status");
        for (BudgetRow r : v.rows()) {
            csv.row(r.headCode(), r.headName(), r.kind().name(), new Money(r.annualPaise()),
                    new Money(r.actualPaise()), new Money(r.annualPaise() - r.actualPaise()), r.percentUsed(),
                    r.status());
        }
        csv.row("", "Total expense heads", "", new Money(v.budgetPaise()), new Money(v.actualPaise()),
                new Money(v.budgetPaise() - v.actualPaise()), v.percentUsed(), "");
        return csv.download("budget-vs-actual-" + year.label() + ".csv");
    }

    // ------------------------------------------------------------------ head-wise expense

    /** Approved and paid expenses of the period by head (bill date), each broken down by cost centre. */
    public HeadWiseExpense headWise(LocalDate from, LocalDate to) {
        TenantContext.require();
        AccountsAccess.period(from, to);
        Map<UUID, Head> heads = chart.byId();
        Map<UUID, CostCentre> centres = chart.costCentresById();
        Map<UUID, long[]> totals = new LinkedHashMap<>();
        Map<UUID, Map<UUID, long[]>> byCentre = new LinkedHashMap<>();
        for (Expense e : expenses.findBetween(from, to)) {
            if (e.getStatus() != ExpenseStatus.APPROVED && e.getStatus() != ExpenseStatus.PAID) {
                continue;
            }
            long[] t = totals.computeIfAbsent(e.getHeadId(), k -> new long[3]);
            t[0] += e.getTotalPaise();
            t[1] += e.getGstPaise();
            t[2]++;
            add(byCentre.computeIfAbsent(e.getHeadId(), k -> new LinkedHashMap<>()), e.getCostCentreId(),
                    e.getTotalPaise(), 1);
        }
        List<HeadWiseRow> rows = new ArrayList<>();
        totals.forEach((id, t) -> {
            List<HeadLine> cc = new ArrayList<>();
            byCentre.get(id).forEach((c, v) -> cc.add(new HeadLine(c, c == null ? "" : centres.get(c).getCode(),
                    c == null ? "No cost centre" : ChartService.costCentreName(centres, c), v[0], v[1])));
            cc.sort((a, b) -> Long.compare(b.amountPaise(), a.amountPaise()));
            rows.add(new HeadWiseRow(id, ChartService.codeOf(heads, id), ChartService.nameOf(heads, id), t[0], t[1],
                    t[2], cc));
        });
        rows.sort((a, b) -> Long.compare(b.amountPaise(), a.amountPaise()));
        return new HeadWiseExpense(from, to, rows, rows.stream().mapToLong(HeadWiseRow::amountPaise).sum(),
                rows.stream().mapToLong(HeadWiseRow::gstPaise).sum(),
                rows.stream().mapToLong(HeadWiseRow::count).sum());
    }

    public Download headWiseCsv(LocalDate from, LocalDate to) {
        HeadWiseExpense r = headWise(from, to);
        AccountsCsv csv = new AccountsCsv("Code", "Head", "Cost centre", "Bills", "GST", "Amount");
        for (HeadWiseRow h : r.rows()) {
            csv.row(h.code(), h.name(), "", h.count(), new Money(h.gstPaise()), new Money(h.amountPaise()));
            for (HeadLine c : h.costCentres()) {
                csv.row("", "", c.name(), c.count(), "", new Money(c.amountPaise()));
            }
        }
        csv.row("", "Total", "", r.count(), new Money(r.gstPaise()), new Money(r.amountPaise()));
        return csv.download("head-wise-expense-" + from + "-" + to + ".csv");
    }

    // ------------------------------------------------------------------ vendor ledger

    public VendorLedger vendorLedger(UUID vendorId, LocalDate from, LocalDate to) {
        return vendorService.ledger(vendorId, from, to);
    }

    public Download vendorLedgerCsv(UUID vendorId, LocalDate from, LocalDate to) {
        VendorLedger l = vendorService.ledger(vendorId, from, to);
        AccountsCsv csv = new AccountsCsv("Date", "Number", "Description", "Status", "Bill", "GST", "TDS", "Paid",
                "Paid on", "Mode");
        for (VendorLedgerRow r : l.rows()) {
            csv.row(r.date(), r.number(), r.description(), r.status().name(), new Money(r.billPaise()),
                    new Money(r.gstPaise()), new Money(r.tdsPaise()), new Money(r.paidPaise()), r.paidOn(),
                    r.mode() == null ? "" : r.mode().name());
        }
        csv.row("", "", "Total", "", new Money(l.billedPaise()), new Money(l.gstPaise()), new Money(l.tdsPaise()),
                new Money(l.paidPaise()), "", "");
        csv.row("", "", "Outstanding", "", new Money(l.outstandingPaise()), "", "", "", "", "");
        return csv.download("vendor-ledger-" + from + "-" + to + ".csv");
    }

    // ------------------------------------------------------------------ cash and bank books

    public Book book(UUID bankAccountId, LocalDate from, LocalDate to) {
        return bank.book(bankAccountId, from, to);
    }

    public Download bookCsv(UUID bankAccountId, LocalDate from, LocalDate to) {
        Book b = bank.book(bankAccountId, from, to);
        AccountsCsv csv = new AccountsCsv("Date", "Kind", "Particulars", "Reference", "Receipt", "Payment",
                "Balance", "Cleared");
        csv.row(from, "OPENING", "Opening balance", "", "", "", new Money(b.openingPaise()), "");
        for (BookRow r : b.rows()) {
            csv.row(r.date(), r.kind(), r.particulars(), r.reference(), new Money(r.receiptPaise()),
                    new Money(r.paymentPaise()), new Money(r.balancePaise()), r.cleared() ? "Yes" : "No");
        }
        csv.row(to, "CLOSING", "Closing balance", "", new Money(b.receiptsPaise()), new Money(b.paymentsPaise()),
                new Money(b.closingPaise()), "");
        String name = bankAccountId == null ? "cash-book" : "bank-book";
        return csv.download(name + "-" + from + "-" + to + ".csv");
    }

    // ------------------------------------------------------------------ GST summary

    /** Input GST on approved and paid purchases of the period (bill date), by vendor; no credit is claimed here. */
    public GstSummary gstSummary(LocalDate from, LocalDate to) {
        TenantContext.require();
        AccountsAccess.period(from, to);
        Map<UUID, Vendor> byVendor = vendors.findAllOrdered().stream()
                .collect(java.util.stream.Collectors.toMap(Vendor::getId, Function.identity()));
        Map<String, long[]> totals = new LinkedHashMap<>();
        Map<String, GstRow> keys = new LinkedHashMap<>();
        long withoutGstin = 0;
        for (Expense e : expenses.findBetween(from, to)) {
            if (e.getGstPaise() <= 0
                    || (e.getStatus() != ExpenseStatus.APPROVED && e.getStatus() != ExpenseStatus.PAID)) {
                continue;
            }
            Vendor v = e.getVendorId() == null ? null : byVendor.get(e.getVendorId());
            String gstin = v == null ? null : v.getGstin();
            String name = v != null ? v.getName() : e.getVendorName() == null ? "Unnamed vendor" : e.getVendorName();
            String key = v != null ? v.getId().toString() : "name:" + name.toLowerCase();
            keys.putIfAbsent(key, new GstRow(v == null ? null : v.getId(), name, gstin, 0, 0, 0));
            long[] t = totals.computeIfAbsent(key, k -> new long[3]);
            t[0]++;
            t[1] += e.getAmountPaise();
            t[2] += e.getGstPaise();
            if (gstin == null) {
                withoutGstin += e.getGstPaise();
            }
        }
        List<GstRow> rows = new ArrayList<>();
        keys.forEach((k, r) -> {
            long[] t = totals.get(k);
            rows.add(new GstRow(r.vendorId(), r.vendorName(), r.gstin(), t[0], t[1], t[2]));
        });
        rows.sort((a, b) -> Long.compare(b.gstPaise(), a.gstPaise()));
        return new GstSummary(from, to, rows, rows.stream().mapToLong(GstRow::taxablePaise).sum(),
                rows.stream().mapToLong(GstRow::gstPaise).sum(), withoutGstin);
    }

    public Download gstSummaryCsv(LocalDate from, LocalDate to) {
        GstSummary s = gstSummary(from, to);
        AccountsCsv csv = new AccountsCsv("Vendor", "GSTIN", "Bills", "Taxable value", "GST");
        for (GstRow r : s.rows()) {
            csv.row(r.vendorName(), r.gstin(), r.bills(), new Money(r.taxablePaise()), new Money(r.gstPaise()));
        }
        csv.row("Total", "", "", new Money(s.taxablePaise()), new Money(s.gstPaise()));
        csv.row("GST on bills without a GSTIN", "", "", "", new Money(s.withoutGstinPaise()));
        return csv.download("gst-summary-" + from + "-" + to + ".csv");
    }

    // ------------------------------------------------------------------ day book

    /** Everything received and paid on a day: fee collections, other income, expenses paid and petty cash. */
    public DayBook dayBook(LocalDate date) {
        TenantContext.require();
        if (date == null) {
            throw com.akshara.shared.ApiException.badRequest("Give the date.", "date");
        }
        Map<UUID, Head> heads = chart.byId();
        Map<UUID, String> bankNames = new LinkedHashMap<>();
        bank.accountNames().forEach(bankNames::put);
        List<DayBookRow> rows = new ArrayList<>();
        feeDays.findByDay(date).filter(d -> d.getAmountPaise() != 0).ifPresent(d -> rows.add(new DayBookRow("FEES",
                d.getId(), "", "Fee collections (" + d.getReceiptCount() + " receipts)", "", "",
                d.getAmountPaise(), 0)));
        for (IncomeReceipt r : receipts.findBetween(date, date)) {
            if (r.issued()) {
                rows.add(new DayBookRow("INCOME", r.getId(), r.getReceiptNo(),
                        ChartService.nameOf(heads, r.getHeadId()) + " - " + r.getPayerName(), r.getMode().name(),
                        r.getBankAccountId() == null ? "Cash" : bankNames.get(r.getBankAccountId()),
                        r.getAmountPaise(), 0));
            }
        }
        for (Expense e : expenses.findPaidBetween(date, date)) {
            rows.add(new DayBookRow("EXPENSE", e.getId(), e.getNumber(),
                    ChartService.nameOf(heads, e.getHeadId()) + " - " + e.getDescription(),
                    e.getPaymentMode().name(),
                    e.getBankAccountId() == null ? "Cash" : bankNames.get(e.getBankAccountId()), 0,
                    e.getPaidPaise()));
        }
        for (PettyCashEntry p : petty.findBetween(date, date)) {
            boolean topUp = p.getKind() == PettyKind.TOP_UP;
            rows.add(new DayBookRow(topUp ? "PETTY_TOP_UP" : "PETTY_SPENT", p.getId(), "",
                    (topUp ? "Petty cash top-up" : ChartService.nameOf(heads, p.getHeadId())) + " - "
                            + p.getDescription(), "CASH", "Petty cash", 0, p.getAmountPaise()));
        }
        return new DayBook(date, rows, rows.stream().mapToLong(DayBookRow::receiptPaise).sum(),
                rows.stream().mapToLong(DayBookRow::paymentPaise).sum());
    }

    public Download dayBookCsv(LocalDate date) {
        DayBook d = dayBook(date);
        AccountsCsv csv = new AccountsCsv("Kind", "Number", "Particulars", "Mode", "Account", "Receipt", "Payment");
        for (DayBookRow r : d.rows()) {
            csv.row(r.kind(), r.number(), r.particulars(), r.mode(), r.account(), new Money(r.receiptPaise()),
                    new Money(r.paymentPaise()));
        }
        csv.row("", "", "Total", "", "", new Money(d.receiptsPaise()), new Money(d.paymentsPaise()));
        return csv.download("day-book-" + date + ".csv");
    }
}
