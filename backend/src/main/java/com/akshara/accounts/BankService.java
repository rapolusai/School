package com.akshara.accounts;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.akshara.accounts.AccountsForms.BankAccountForm;
import com.akshara.accounts.AccountsForms.ClearForm;
import com.akshara.accounts.AccountsForms.OpeningBalanceInput;
import com.akshara.accounts.AccountsForms.OpeningBalancesForm;
import com.akshara.accounts.AccountsForms.StatementImportForm;
import com.akshara.accounts.AccountsForms.StatementLineInput;
import com.akshara.accounts.AccountsViews.AccountBalance;
import com.akshara.accounts.AccountsViews.BankAccountView;
import com.akshara.accounts.AccountsViews.Book;
import com.akshara.accounts.AccountsViews.BookRow;
import com.akshara.accounts.AccountsViews.CandidateView;
import com.akshara.accounts.AccountsViews.CashPosition;
import com.akshara.accounts.AccountsViews.ClearResult;
import com.akshara.accounts.AccountsViews.MatchView;
import com.akshara.accounts.AccountsViews.OpeningBalanceView;
import com.akshara.accounts.AccountsViews.OpeningBalancesView;
import com.akshara.accounts.AccountsViews.PreviewLine;
import com.akshara.accounts.AccountsViews.Reconciliation;
import com.akshara.accounts.AccountsViews.StatementImportResult;
import com.akshara.accounts.AccountsViews.StatementLineView;
import com.akshara.accounts.AccountsViews.StatementPreview;
import com.akshara.accounts.AccountsViews.UnclearedItem;
import com.akshara.accounts.StatementMatcher.Candidate;
import com.akshara.accounts.StatementMatcher.Line;
import com.akshara.accounts.StatementMatcher.Match;
import com.akshara.audit.AuditService;
import com.akshara.audit.AuditService.Actor;
import com.akshara.shared.ApiException;
import com.akshara.shared.TenantContext;

/**
 * Bank accounts with opening balances per financial year, the cash book and bank books built from recorded receipts
 * and payments, today's cash position, and a simple reconciliation: ticking cleared items against a statement date,
 * and importing a CSV statement with a preview matched by amount and reference.
 *
 * <p>Cash fee collections enter the cash book from the fees module's daily summary; fees paid by other means are
 * deposited by the fees desk and are not assigned to a bank account here (they appear in the income reports only).
 */
@Service
@Transactional
public class BankService {

    static final int MAX_STATEMENT_LINES = 2000;
    private static final List<DateTimeFormatter> DATE_FORMATS = List.of(DateTimeFormatter.ISO_LOCAL_DATE,
            DateTimeFormatter.ofPattern("dd/MM/yyyy"), DateTimeFormatter.ofPattern("dd-MM-yyyy"),
            DateTimeFormatter.ofPattern("dd/MM/yy"), DateTimeFormatter.ofPattern("dd-MMM-yyyy", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.ENGLISH));

    private final BankAccountRepository banks;
    private final OpeningBalanceRepository openings;
    private final ExpenseRepository expenses;
    private final IncomeReceiptRepository receipts;
    private final PettyCashEntryRepository petty;
    private final FeeIncomeDayRepository feeDays;
    private final BankStatementLineRepository lines;
    private final PettyCashService pettyCash;
    private final AuditService audit;

    BankService(BankAccountRepository banks, OpeningBalanceRepository openings, ExpenseRepository expenses,
            IncomeReceiptRepository receipts, PettyCashEntryRepository petty, FeeIncomeDayRepository feeDays,
            BankStatementLineRepository lines, PettyCashService pettyCash, AuditService audit) {
        this.banks = banks;
        this.openings = openings;
        this.expenses = expenses;
        this.receipts = receipts;
        this.petty = petty;
        this.feeDays = feeDays;
        this.lines = lines;
        this.pettyCash = pettyCash;
        this.audit = audit;
    }

    // ------------------------------------------------------------------ accounts

    @Transactional(readOnly = true)
    public List<BankAccountView> accounts() {
        TenantContext.require();
        LocalDate today = FinancialYear.today();
        return banks.findAllOrdered().stream().map(b -> view(b, today)).toList();
    }

    public BankAccountView create(BankAccountForm form, Actor actor) {
        TenantContext.require();
        String name = form.name().strip();
        if (banks.nameTaken(name, new UUID(0, 0))) {
            throw ApiException.conflict("A bank account with this name already exists.", "name");
        }
        BankAccount b = new BankAccount(name, form.bankName().strip(), form.accountNumber().strip(),
                form.ifsc().strip().toUpperCase(Locale.ROOT));
        b.update(b.getName(), b.getBankName(), b.getAccountNumber(), b.getIfsc(), form.active());
        banks.saveAndFlush(b);
        audit.record(actor, "bank_account.created", "bank_account", b.getId(), details(b));
        return view(b, FinancialYear.today());
    }

    public BankAccountView update(UUID id, BankAccountForm form, Actor actor) {
        TenantContext.require();
        BankAccount b = banks.findById(id).orElseThrow(() -> ApiException.notFound("Bank account"));
        String name = form.name().strip();
        if (banks.nameTaken(name, id)) {
            throw ApiException.conflict("A bank account with this name already exists.", "name");
        }
        b.update(name, form.bankName().strip(), form.accountNumber().strip(),
                form.ifsc().strip().toUpperCase(Locale.ROOT), form.active());
        banks.saveAndFlush(b);
        audit.record(actor, "bank_account.updated", "bank_account", id, details(b));
        return view(b, FinancialYear.today());
    }

    public void delete(UUID id, Actor actor) {
        TenantContext.require();
        BankAccount b = banks.findById(id).orElseThrow(() -> ApiException.notFound("Bank account"));
        if (expenses.bankAccountUsed(id) || receipts.bankAccountUsed(id)) {
            throw new ApiException(HttpStatus.CONFLICT, "In use",
                    "Payments or receipts went through this account. Mark it inactive instead.");
        }
        banks.delete(b);
        banks.flush();
        audit.record(actor, "bank_account.deleted", "bank_account", id, Map.of("name", b.getName()));
    }

    private BankAccountView view(BankAccount b, LocalDate today) {
        long balance = balance(b.getId(), today);
        int uncleared = expenses.findUncleared(b.getId()).size() + receipts.findUncleared(b.getId()).size();
        return new BankAccountView(b.getId(), b.getName(), b.getBankName(),
                AccountsViews.maskAccount(b.getAccountNumber()), b.getIfsc(), b.isActive(),
                expenses.bankAccountUsed(b.getId()) || receipts.bankAccountUsed(b.getId()), balance, uncleared);
    }

    private static Map<String, Object> details(BankAccount b) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("name", b.getName());
        d.put("bankName", b.getBankName());
        d.put("accountLast4", AccountsViews.maskAccount(b.getAccountNumber()));
        d.put("active", b.isActive());
        return d;
    }

    // ------------------------------------------------------------------ opening balances

    @Transactional(readOnly = true)
    public OpeningBalancesView openingBalances(FinancialYear year) {
        TenantContext.require();
        Map<UUID, Long> byAccount = new HashMap<>();
        long cash = 0;
        for (OpeningBalance o : openings.findByFinancialYear(year.label())) {
            if (o.getBankAccountId() == null) {
                cash = o.getAmountPaise();
            } else {
                byAccount.put(o.getBankAccountId(), o.getAmountPaise());
            }
        }
        List<OpeningBalanceView> accounts = banks.findAllOrdered().stream()
                .map(b -> new OpeningBalanceView(b.getId(), b.getName(), byAccount.getOrDefault(b.getId(), 0L)))
                .toList();
        return new OpeningBalancesView(year.label(), cash, accounts);
    }

    public OpeningBalancesView saveOpeningBalances(FinancialYear year, OpeningBalancesForm form, Actor actor) {
        TenantContext.require();
        Set<UUID> seen = new HashSet<>();
        for (int i = 0; i < form.balances().size(); i++) {
            OpeningBalanceInput in = form.balances().get(i);
            if (in.bankAccountId() != null && banks.findById(in.bankAccountId()).isEmpty()) {
                throw ApiException.badRequest("Pick one of the school's bank accounts.",
                        "balances[" + i + "].bankAccountId");
            }
            if (!seen.add(in.bankAccountId() == null ? new UUID(0, 0) : in.bankAccountId())) {
                throw ApiException.badRequest("This account is listed twice.", "balances[" + i + "].bankAccountId");
            }
            OpeningBalance o = (in.bankAccountId() == null ? openings.findCash(year.label())
                    : openings.findBank(year.label(), in.bankAccountId())).orElse(null);
            if (o == null) {
                openings.save(new OpeningBalance(year.label(), in.bankAccountId(), in.amountPaise()));
            } else {
                o.setAmountPaise(in.amountPaise());
            }
        }
        openings.flush();
        audit.record(actor, "opening_balances.saved", "opening_balance", null, Map.of("financialYear",
                year.label(), "accounts", form.balances().size()));
        return openingBalances(year);
    }

    // ------------------------------------------------------------------ books and balances

    /** The cash book (bankAccountId null) or a bank book for the period, with a running balance. */
    @Transactional(readOnly = true)
    public Book book(UUID bankAccountId, LocalDate from, LocalDate to) {
        TenantContext.require();
        AccountsAccess.period(from, to);
        String name = "Cash";
        if (bankAccountId != null) {
            name = banks.findById(bankAccountId).orElseThrow(() -> ApiException.notFound("Bank account")).getName();
        }
        long opening = balance(bankAccountId, from.minusDays(1));
        List<BookRow> rows = new ArrayList<>();
        long balance = opening;
        long receiptsTotal = 0;
        long paymentsTotal = 0;
        for (BookRow r : movements(bankAccountId, from, to)) {
            balance += r.receiptPaise() - r.paymentPaise();
            receiptsTotal += r.receiptPaise();
            paymentsTotal += r.paymentPaise();
            rows.add(new BookRow(r.date(), r.kind(), r.refId(), r.particulars(), r.reference(), r.receiptPaise(),
                    r.paymentPaise(), balance, r.cleared()));
        }
        return new Book(bankAccountId, name, from, to, opening, rows, receiptsTotal, paymentsTotal, balance);
    }

    @Transactional(readOnly = true)
    public CashPosition cashPosition() {
        TenantContext.require();
        LocalDate today = FinancialYear.today();
        long cash = balance(null, today);
        long pettyBalance = pettyCash.balanceToday();
        List<AccountBalance> bankBalances = banks.findAllOrdered().stream().filter(BankAccount::isActive)
                .map(b -> new AccountBalance(b.getId(), b.getName(), balance(b.getId(), today))).toList();
        long bank = bankBalances.stream().mapToLong(AccountBalance::balancePaise).sum();
        return new CashPosition(today, cash, pettyBalance, bankBalances, bank, cash + pettyBalance + bank);
    }

    /** Cash in hand plus every active bank account at the end of the day (petty cash excluded). */
    long cashAndBank(LocalDate asOf) {
        long total = balance(null, asOf);
        for (BankAccount b : banks.findAllOrdered()) {
            if (b.isActive()) {
                total += balance(b.getId(), asOf);
            }
        }
        return total;
    }

    Map<UUID, String> accountNames() {
        Map<UUID, String> names = new java.util.LinkedHashMap<>();
        banks.findAllOrdered().forEach(b -> names.put(b.getId(), b.getName()));
        return names;
    }

    /** The balance at the end of the day: the year's opening balance plus every movement up to it. */
    long balance(UUID bankAccountId, LocalDate asOf) {
        FinancialYear year = FinancialYear.of(asOf);
        long opening = (bankAccountId == null ? openings.findCash(year.label())
                : openings.findBank(year.label(), bankAccountId)).map(OpeningBalance::getAmountPaise).orElse(0L);
        long balance = opening;
        for (BookRow r : movements(bankAccountId, year.start(), asOf)) {
            balance += r.receiptPaise() - r.paymentPaise();
        }
        return balance;
    }

    /** Every receipt and payment of the cash book or a bank book in the period, in date order, no balance. */
    private List<BookRow> movements(UUID bankAccountId, LocalDate from, LocalDate to) {
        if (to.isBefore(from)) {
            return List.of();
        }
        List<BookRow> rows = new ArrayList<>();
        for (IncomeReceipt r : receipts.findBetween(from, to)) {
            if (!r.issued() || !sameBook(bankAccountId, r.getMode(), r.getBankAccountId())) {
                continue;
            }
            rows.add(new BookRow(r.getReceivedOn(), "INCOME", r.getId(), r.getReceiptNo() + " · " + r.getPayerName(),
                    r.getReference(), r.getAmountPaise(), 0, 0, r.getClearedOn() != null));
        }
        for (Expense e : expenses.findPaidBetween(from, to)) {
            if (!sameBook(bankAccountId, e.getPaymentMode(), e.getBankAccountId())) {
                continue;
            }
            String who = e.getVendorName() == null ? e.getDescription() : e.getVendorName() + " · "
                    + e.getDescription();
            rows.add(new BookRow(e.getPaidOn(), "EXPENSE", e.getId(), e.getNumber() + " · " + who,
                    e.getPaymentReference(), 0, e.getPaidPaise(), 0, e.getClearedOn() != null));
        }
        if (bankAccountId == null) {
            for (FeeIncomeDay d : feeDays.findBetween(from, to)) {
                if (d.getCashPaise() > 0) {
                    rows.add(new BookRow(d.getDay(), "FEES", d.getId(), "Fee collections in cash", null,
                            d.getCashPaise(), 0, 0, true));
                }
            }
            for (PettyCashEntry p : petty.findBetween(from, to)) {
                if (p.getKind() == PettyKind.TOP_UP) {
                    rows.add(new BookRow(p.getEntryDate(), "PETTY_TOP_UP", p.getId(), "Petty cash top-up · "
                            + p.getDescription(), null, 0, p.getAmountPaise(), 0, true));
                }
            }
        }
        rows.sort(Comparator.comparing(BookRow::date).thenComparing(BookRow::kind));
        return rows;
    }

    private static boolean sameBook(UUID bankAccountId, PayMode mode, UUID itemAccount) {
        if (bankAccountId == null) {
            return mode != null && mode.cash();
        }
        return bankAccountId.equals(itemAccount);
    }

    // ------------------------------------------------------------------ reconciliation

    @Transactional(readOnly = true)
    public Reconciliation reconciliation(UUID bankAccountId, LocalDate statementDate) {
        TenantContext.require();
        BankAccount b = banks.findById(bankAccountId).orElseThrow(() -> ApiException.notFound("Bank account"));
        LocalDate date = statementDate == null ? FinancialYear.today() : statementDate;
        long book = balance(bankAccountId, date);
        List<UnclearedItem> payments = expenses.findUncleared(bankAccountId).stream()
                .filter(e -> !e.getPaidOn().isAfter(date))
                .map(e -> new UnclearedItem(e.getId(), "EXPENSE", e.getPaidOn(), e.getNumber() + " · "
                        + (e.getVendorName() == null ? e.getDescription() : e.getVendorName()),
                        e.getPaymentReference(), e.getPaidPaise()))
                .toList();
        List<UnclearedItem> incomes = receipts.findUncleared(bankAccountId).stream()
                .filter(r -> !r.getReceivedOn().isAfter(date))
                .map(r -> new UnclearedItem(r.getId(), "INCOME", r.getReceivedOn(), r.getReceiptNo() + " · "
                        + r.getPayerName(), r.getReference(), r.getAmountPaise()))
                .toList();
        long unclearedPayments = payments.stream().mapToLong(UnclearedItem::amountPaise).sum();
        long unclearedReceipts = incomes.stream().mapToLong(UnclearedItem::amountPaise).sum();
        List<StatementLineView> recent = lines.findByAccount(bankAccountId).stream().limit(200)
                .map(l -> new StatementLineView(l.getId(), l.getStatementDate(), l.getLineDate(),
                        l.getDescription(), l.getReference(), l.getDebitPaise(), l.getCreditPaise(),
                        l.getMatchedExpenseId(), l.getMatchedIncomeId(), null))
                .toList();
        return new Reconciliation(bankAccountId, b.getName(), date, book, payments, incomes, unclearedPayments,
                unclearedReceipts, book + unclearedPayments - unclearedReceipts, recent);
    }

    /** Marks the chosen items cleared on the statement date (items of other accounts or schools are 400). */
    public ClearResult clear(UUID bankAccountId, ClearForm form, Actor actor) {
        TenantContext.require();
        banks.findById(bankAccountId).orElseThrow(() -> ApiException.notFound("Bank account"));
        int cleared = 0;
        for (UUID id : form.expenseIds() == null ? List.<UUID>of() : form.expenseIds()) {
            Expense e = expenses.findById(id).filter(x -> bankAccountId.equals(x.getBankAccountId())
                    && x.getStatus() == ExpenseStatus.PAID).orElseThrow(() -> ApiException.badRequest(
                            "This payment did not go through this account.", "expenseIds"));
            if (e.getClearedOn() == null) {
                e.cleared(form.statementDate());
                cleared++;
            }
        }
        for (UUID id : form.incomeIds() == null ? List.<UUID>of() : form.incomeIds()) {
            IncomeReceipt r = receipts.findById(id).filter(x -> bankAccountId.equals(x.getBankAccountId())
                    && x.issued()).orElseThrow(() -> ApiException.badRequest(
                            "This receipt did not come into this account.", "incomeIds"));
            if (r.getClearedOn() == null) {
                r.cleared(form.statementDate());
                cleared++;
            }
        }
        expenses.flush();
        receipts.flush();
        audit.record(actor, "bank_items.cleared", "bank_account", bankAccountId, Map.of("statementDate",
                form.statementDate().toString(), "cleared", cleared));
        return new ClearResult(cleared);
    }

    /**
     * Reads a bank's CSV statement (date, description, reference?, debit, credit; or date, description, amount with
     * Dr/Cr or a sign) and proposes a match for each line against the account's uncleared items. Nothing is saved.
     */
    @Transactional(readOnly = true)
    public StatementPreview preview(UUID bankAccountId, byte[] csv) {
        TenantContext.require();
        banks.findById(bankAccountId).orElseThrow(() -> ApiException.notFound("Bank account"));
        List<PreviewLine> parsed = parse(csv);
        List<Candidate> candidates = new ArrayList<>();
        List<CandidateView> payments = new ArrayList<>();
        List<CandidateView> incomes = new ArrayList<>();
        Map<UUID, String> labels = new HashMap<>();
        for (Expense e : expenses.findUncleared(bankAccountId)) {
            String label = e.getNumber() + " · " + (e.getVendorName() == null ? e.getDescription()
                    : e.getVendorName());
            candidates.add(new Candidate(e.getId(), true, e.getPaidPaise(), e.getPaymentReference(), label));
            payments.add(new CandidateView(e.getId(), "EXPENSE", e.getPaidOn(), label, e.getPaymentReference(),
                    e.getPaidPaise()));
            labels.put(e.getId(), label);
        }
        for (IncomeReceipt r : receipts.findUncleared(bankAccountId)) {
            String label = r.getReceiptNo() + " · " + r.getPayerName();
            candidates.add(new Candidate(r.getId(), false, r.getAmountPaise(), r.getReference(), label));
            incomes.add(new CandidateView(r.getId(), "INCOME", r.getReceivedOn(), label, r.getReference(),
                    r.getAmountPaise()));
            labels.put(r.getId(), label);
        }
        Set<UUID> paymentIds = payments.stream().map(CandidateView::id).collect(Collectors.toSet());
        List<Line> matchable = parsed.stream().filter(p -> p.problem() == null)
                .map(p -> new Line(p.index(), p.debitPaise(), p.creditPaise(), p.description(), p.reference()))
                .toList();
        Map<Integer, Match> byIndex = StatementMatcher.match(matchable, candidates).stream()
                .collect(Collectors.toMap(Match::lineIndex, Function.identity()));
        List<PreviewLine> out = new ArrayList<>();
        int matched = 0;
        int probable = 0;
        int unmatched = 0;
        for (PreviewLine p : parsed) {
            Match m = byIndex.get(p.index());
            MatchView mv = null;
            if (m != null && m.candidate() != null) {
                boolean isPayment = paymentIds.contains(m.candidate().id());
                mv = new MatchView(isPayment ? m.candidate().id() : null, isPayment ? null : m.candidate().id(),
                        labels.get(m.candidate().id()), m.quality().name());
                if (m.quality() == StatementMatcher.Quality.MATCHED) {
                    matched++;
                } else {
                    probable++;
                }
            } else if (p.problem() == null) {
                unmatched++;
            }
            boolean duplicate = p.problem() == null && lines.exists(bankAccountId, p.date(), p.debitPaise(),
                    p.creditPaise(), p.description());
            out.add(new PreviewLine(p.index(), p.date(), p.description(), p.reference(), p.debitPaise(),
                    p.creditPaise(), mv, duplicate, p.problem()));
        }
        return new StatementPreview(out, payments, incomes, matched, probable, unmatched);
    }

    /** Saves the statement lines as reviewed and clears what they were matched to; duplicates are skipped. */
    public StatementImportResult importStatement(UUID bankAccountId, StatementImportForm form, Actor actor) {
        TenantContext.require();
        banks.findById(bankAccountId).orElseThrow(() -> ApiException.notFound("Bank account"));
        int imported = 0;
        int cleared = 0;
        int skipped = 0;
        Set<UUID> used = new HashSet<>();
        for (int i = 0; i < form.lines().size(); i++) {
            StatementLineInput in = form.lines().get(i);
            if ((in.debitPaise() > 0) == (in.creditPaise() > 0)) {
                throw ApiException.badRequest("A line is either a debit or a credit.", "lines[" + i + "].debitPaise");
            }
            if (in.expenseId() != null && in.incomeId() != null) {
                throw ApiException.badRequest("A line clears one item, not two.", "lines[" + i + "].expenseId");
            }
            String description = in.description().strip();
            String field = "lines[" + i + "].";
            if (lines.exists(bankAccountId, in.date(), in.debitPaise(), in.creditPaise(), description)) {
                skipped++;
                continue;
            }
            UUID expenseId = null;
            UUID incomeId = null;
            if (in.expenseId() != null) {
                Expense e = expenses.findById(in.expenseId()).filter(x -> bankAccountId.equals(x.getBankAccountId())
                        && x.getStatus() == ExpenseStatus.PAID).orElseThrow(() -> ApiException.badRequest(
                                "This payment did not go through this account.", field + "expenseId"));
                if (!used.add(e.getId())) {
                    throw ApiException.badRequest("This payment is matched twice.", "lines[" + i + "].expenseId");
                }
                if (e.getClearedOn() == null) {
                    e.cleared(form.statementDate());
                    cleared++;
                }
                expenseId = e.getId();
            }
            if (in.incomeId() != null) {
                IncomeReceipt r = receipts.findById(in.incomeId()).filter(x -> bankAccountId.equals(
                        x.getBankAccountId()) && x.issued()).orElseThrow(() -> ApiException.badRequest(
                                "This receipt did not come into this account.", field + "incomeId"));
                if (!used.add(r.getId())) {
                    throw ApiException.badRequest("This receipt is matched twice.", "lines[" + i + "].incomeId");
                }
                if (r.getClearedOn() == null) {
                    r.cleared(form.statementDate());
                    cleared++;
                }
                incomeId = r.getId();
            }
            lines.save(new BankStatementLine(bankAccountId, form.statementDate(), in.date(), description,
                    AccountsForms.clean(in.reference()), in.debitPaise(), in.creditPaise(), expenseId, incomeId,
                    actor.name()));
            imported++;
        }
        lines.flush();
        expenses.flush();
        receipts.flush();
        audit.record(actor, "bank_statement.imported", "bank_account", bankAccountId, Map.of("statementDate",
                form.statementDate().toString(), "imported", imported, "cleared", cleared, "skipped", skipped));
        return new StatementImportResult(imported, cleared, skipped);
    }

    // ------------------------------------------------------------------ CSV parsing

    /** Parses the statement into lines; a line that cannot be read keeps its problem and is never matched. */
    static List<PreviewLine> parse(byte[] csv) {
        String text = new String(csv, StandardCharsets.UTF_8);
        if (text.startsWith("﻿")) {
            text = text.substring(1);
        }
        List<List<String>> records = records(text);
        if (records.isEmpty()) {
            throw ApiException.badRequest("The file is empty.", "file");
        }
        List<String> header = records.getFirst().stream().map(h -> h.strip().toLowerCase(Locale.ROOT)).toList();
        int dateCol = find(header, "date", "txn date", "transaction date", "value date");
        int descCol = find(header, "description", "narration", "particulars", "details", "remarks");
        int refCol = find(header, "reference", "ref", "ref no", "cheque", "chq", "utr", "transaction id");
        int debitCol = find(header, "debit", "withdrawal", "withdrawals", "dr", "paid out");
        int creditCol = find(header, "credit", "deposit", "deposits", "cr", "paid in");
        int amountCol = find(header, "amount");
        int drCrCol = find(header, "dr/cr", "type", "cr/dr");
        if (dateCol < 0 || descCol < 0 || (debitCol < 0 && creditCol < 0 && amountCol < 0)) {
            throw ApiException.badRequest("The file needs Date, Description and Debit/Credit (or Amount) columns "
                    + "in its first row.", "file");
        }
        if (records.size() - 1 > MAX_STATEMENT_LINES) {
            throw ApiException.badRequest("A statement can have at most " + MAX_STATEMENT_LINES + " lines.",
                    "file");
        }
        List<PreviewLine> out = new ArrayList<>();
        for (int i = 1; i < records.size(); i++) {
            List<String> r = records.get(i);
            if (r.stream().allMatch(c -> c.isBlank())) {
                continue;
            }
            String problem = null;
            LocalDate date = null;
            long debit = 0;
            long credit = 0;
            try {
                date = date(cell(r, dateCol));
                if (debitCol >= 0 || creditCol >= 0) {
                    debit = paise(cell(r, debitCol));
                    credit = paise(cell(r, creditCol));
                } else {
                    long amount = paise(cell(r, amountCol));
                    String side = cell(r, drCrCol).strip().toUpperCase(Locale.ROOT);
                    if (side.startsWith("D") || (side.isEmpty() && amount < 0)) {
                        debit = Math.abs(amount);
                    } else {
                        credit = Math.abs(amount);
                    }
                }
                if ((debit > 0) == (credit > 0)) {
                    problem = "A line is either a debit or a credit.";
                }
            } catch (DateTimeParseException | NumberFormatException e) {
                problem = "Could not read the date or amount.";
            }
            String description = cell(r, descCol).strip();
            if (description.length() > 300) {
                description = description.substring(0, 300);
            }
            if (description.isEmpty() && problem == null) {
                problem = "The description is empty.";
            }
            String reference = refCol < 0 ? null : AccountsForms.clean(cell(r, refCol));
            if (reference != null && reference.length() > 100) {
                reference = reference.substring(0, 100);
            }
            out.add(new PreviewLine(out.size(), date, description, reference, debit, credit, null, false, problem));
        }
        return out;
    }

    private static int find(List<String> header, String... names) {
        for (String n : names) {
            int i = header.indexOf(n);
            if (i >= 0) {
                return i;
            }
        }
        for (int i = 0; i < header.size(); i++) {
            for (String n : names) {
                if (header.get(i).contains(n)) {
                    return i;
                }
            }
        }
        return -1;
    }

    private static String cell(List<String> row, int col) {
        return col < 0 || col >= row.size() ? "" : row.get(col);
    }

    static LocalDate date(String text) {
        String t = text.strip();
        for (DateTimeFormatter f : DATE_FORMATS) {
            try {
                return LocalDate.parse(t, f);
            } catch (DateTimeParseException e) {
                // try the next format
            }
        }
        throw new DateTimeParseException("Unreadable date", t, 0);
    }

    /** "1,250.50" → 125050; blank → 0; "(300)" → -30000. */
    static long paise(String text) {
        String t = text.strip().replace(",", "").replace("₹", "").replace("Rs.", "").replace("INR", "").strip();
        if (t.isEmpty() || t.equals("-")) {
            return 0;
        }
        boolean negative = false;
        if (t.startsWith("(") && t.endsWith(")")) {
            negative = true;
            t = t.substring(1, t.length() - 1);
        }
        if (!t.matches("^-?[0-9]+(\\.[0-9]{1,2})?$")) {
            throw new NumberFormatException(t);
        }
        if (t.startsWith("-")) {
            negative = !negative;
            t = t.substring(1);
        }
        String[] parts = t.split("\\.");
        long value = Long.parseLong(parts[0]) * 100
                + (parts.length > 1 ? Long.parseLong((parts[1] + "0").substring(0, 2)) : 0);
        return negative ? -value : value;
    }

    /** RFC 4180 records: quoted cells may hold commas, quotes and line breaks. */
    static List<List<String>> records(String text) {
        List<List<String>> out = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new StringReader(text))) {
            List<String> row = new ArrayList<>();
            StringBuilder cell = new StringBuilder();
            boolean quoted = false;
            int c;
            int prev = -1;
            while ((c = reader.read()) >= 0) {
                char ch = (char) c;
                if (quoted) {
                    if (ch == '"') {
                        reader.mark(1);
                        int next = reader.read();
                        if (next == '"') {
                            cell.append('"');
                        } else {
                            quoted = false;
                            if (next >= 0) {
                                reader.reset();
                            }
                        }
                    } else {
                        cell.append(ch);
                    }
                } else if (ch == '"') {
                    quoted = true;
                } else if (ch == ',') {
                    row.add(cell.toString());
                    cell.setLength(0);
                } else if (ch == '\n' || ch == '\r') {
                    if (!(ch == '\n' && prev == '\r')) {
                        row.add(cell.toString());
                        cell.setLength(0);
                        out.add(row);
                        row = new ArrayList<>();
                    }
                } else {
                    cell.append(ch);
                }
                prev = ch;
            }
            if (cell.length() > 0 || !row.isEmpty()) {
                row.add(cell.toString());
                out.add(row);
            }
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return out;
    }
}
