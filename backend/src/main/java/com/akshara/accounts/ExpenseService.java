package com.akshara.accounts;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.akshara.accounts.AccountsForms.ApproveForm;
import com.akshara.accounts.AccountsForms.ExpenseForm;
import com.akshara.accounts.AccountsForms.GenerateForm;
import com.akshara.accounts.AccountsForms.PaidForm;
import com.akshara.accounts.AccountsForms.RecurringForm;
import com.akshara.accounts.AccountsForms.RejectForm;
import com.akshara.accounts.AccountsViews.ApprovalQueue;
import com.akshara.accounts.AccountsViews.ApprovalView;
import com.akshara.accounts.AccountsViews.BudgetCheck;
import com.akshara.accounts.AccountsViews.ExpenseDetail;
import com.akshara.accounts.AccountsViews.ExpenseList;
import com.akshara.accounts.AccountsViews.ExpenseRow;
import com.akshara.accounts.AccountsViews.FileRef;
import com.akshara.accounts.AccountsViews.GenerateResult;
import com.akshara.accounts.AccountsViews.RecurringView;
import com.akshara.audit.AuditService;
import com.akshara.audit.AuditService.Actor;
import com.akshara.files.FileOwner;
import com.akshara.files.FileStore;
import com.akshara.files.IncomingFile;
import com.akshara.files.StoredFileInfo;
import com.akshara.shared.ApiException;
import com.akshara.shared.Permissions;
import com.akshara.shared.TenantContext;

/**
 * Expense requests from draft to payment. Anyone with accounts.manage records and submits; an approver
 * (accounts.approve) approves within their limit, passes a bigger one on, or rejects with a reason; accounts.manage
 * then records the payment, after which the expense is locked. Approvals warn (or refuse, by the settings) when the
 * head's budget would be exceeded. Recurring expenses are generated as drafts for a month. Every step is audited.
 */
@Service
@Transactional
public class ExpenseService {

    static final String MODULE = "accounts";
    static final String BILL = "expense-bill";
    static final int MAX_FILES = 5;

    private final ExpenseRepository expenses;
    private final ExpenseApprovalRepository approvals;
    private final RecurringExpenseRepository recurring;
    private final BankAccountRepository banks;
    private final ChartService chart;
    private final VendorService vendors;
    private final BudgetService budgets;
    private final AccountsSettingsService settings;
    private final IncomeService numbers;
    private final FileStore files;
    private final AuditService audit;

    ExpenseService(ExpenseRepository expenses, ExpenseApprovalRepository approvals,
            RecurringExpenseRepository recurring, BankAccountRepository banks, ChartService chart,
            VendorService vendors, BudgetService budgets, AccountsSettingsService settings, IncomeService numbers,
            FileStore files, AuditService audit) {
        this.expenses = expenses;
        this.approvals = approvals;
        this.recurring = recurring;
        this.banks = banks;
        this.chart = chart;
        this.vendors = vendors;
        this.budgets = budgets;
        this.settings = settings;
        this.numbers = numbers;
        this.files = files;
        this.audit = audit;
    }

    static FileOwner owner(UUID expenseId) {
        return new FileOwner(MODULE, BILL, expenseId);
    }

    // ------------------------------------------------------------------ list and detail

    @Transactional(readOnly = true)
    public ExpenseList list(ExpenseStatus status, LocalDate from, LocalDate to) {
        TenantContext.require();
        List<Expense> all = expenses.findAllNewestFirst();
        Map<ExpenseStatus, Long> counts = all.stream()
                .collect(Collectors.groupingBy(Expense::getStatus, Collectors.counting()));
        List<ExpenseRow> rows = rows(all.stream()
                .filter(e -> status == null || e.getStatus() == status)
                .filter(e -> from == null || !e.getExpenseDate().isBefore(from))
                .filter(e -> to == null || !e.getExpenseDate().isAfter(to))
                .toList());
        return new ExpenseList(rows, counts.getOrDefault(ExpenseStatus.DRAFT, 0L),
                counts.getOrDefault(ExpenseStatus.SUBMITTED, 0L), counts.getOrDefault(ExpenseStatus.APPROVED, 0L),
                counts.getOrDefault(ExpenseStatus.PAID, 0L), counts.getOrDefault(ExpenseStatus.REJECTED, 0L));
    }

    /** The submitted expenses, oldest first, and which of them the signed-in approver can act on. */
    @Transactional(readOnly = true)
    public ApprovalQueue queue(UUID userId) {
        TenantContext.require();
        List<Expense> submitted = expenses.findSubmitted();
        Optional<Long> limit = settings.limitOf(userId);
        boolean limited = limit.isPresent() && limit.get() != null;
        List<UUID> actionable = submitted.stream()
                .filter(e -> Approvals.mayAct(limit.orElse(null), e.getEscalatedAbovePaise()))
                .map(Expense::getId).toList();
        return new ApprovalQueue(rows(submitted), limit.orElse(null), limited, actionable);
    }

    @Transactional(readOnly = true)
    public ExpenseDetail detail(UUID id, UUID viewerId, boolean viewerApproves) {
        TenantContext.require();
        return detail(expenses.findById(id).orElseThrow(() -> ApiException.notFound("Expense")), viewerId,
                viewerApproves);
    }

    // ------------------------------------------------------------------ draft, submit, edit

    public ExpenseDetail create(ExpenseForm form, boolean submit, Actor actor) {
        TenantContext.require();
        Expense e = new Expense(fields(form), actor, null, null);
        expenses.saveAndFlush(e);
        audit.record(actor, "expense.created", "expense", e.getId(), details(e));
        if (submit) {
            submit(e, actor);
        }
        return detail(e, actor.id(), false);
    }

    public ExpenseDetail update(UUID id, ExpenseForm form, Actor actor) {
        TenantContext.require();
        Expense e = editable(id, actor);
        e.update(fields(form));
        expenses.flush();
        audit.record(actor, "expense.updated", "expense", e.getId(), details(e));
        return detail(e, actor.id(), false);
    }

    /** Sends a draft (or a rejected expense, after changes) for approval. */
    public ExpenseDetail submit(UUID id, Actor actor) {
        TenantContext.require();
        Expense e = editable(id, actor);
        submit(e, actor);
        return detail(e, actor.id(), false);
    }

    public void delete(UUID id, Actor actor) {
        TenantContext.require();
        Expense e = editable(id, actor);
        String description = e.getDescription();
        int removed = files.deleteAll(owner(e.getId()));
        expenses.delete(e);
        expenses.flush();
        audit.record(actor, "expense.deleted", "expense", id, Map.of("description", description, "files", removed));
    }

    private void submit(Expense e, Actor actor) {
        BudgetCheck check = budgets.check(e.getHeadId(), FinancialYear.of(e.getExpenseDate()), e.getTotalPaise());
        String number = e.getNumber() != null ? e.getNumber()
                : "EXP/" + e.getFinancialYear() + "/" + String.format("%06d",
                        numbers.nextSeq(TenantContext.require(), "EXP", e.getFinancialYear()));
        e.submitted(number, Instant.now(), "OVER".equals(check.status()) || "NEAR".equals(check.status()));
        expenses.flush();
        Map<String, Object> details = details(e);
        details.put("budgetStatus", check.status());
        audit.record(actor, "expense.submitted", "expense", e.getId(), details);
    }

    // ------------------------------------------------------------------ approval chain

    /**
     * Approves within the approver's limit, or passes the expense on when the total is above it (409 when nobody has
     * a larger limit, or when the budget mode is BLOCK and the head's budget would be exceeded).
     */
    public ExpenseDetail approve(UUID id, ApproveForm form, Actor actor) {
        TenantContext.require();
        Expense e = submitted(id);
        Long limit = settings.limitOf(actor.id()).orElse(null);
        Approvals.Outcome outcome = Approvals.decide(limit, e.getEscalatedAbovePaise(), e.getTotalPaise(),
                settings.largestOtherLimit(actor.id()));
        String note = AccountsForms.clean(form == null ? null : form.note());
        Instant now = Instant.now();
        switch (outcome) {
            case NOT_YOURS -> throw new ApiException(HttpStatus.CONFLICT, "Passed on",
                    "This expense was passed on to an approver with a larger limit than yours.");
            case NOBODY_ABOVE -> throw new ApiException(HttpStatus.CONFLICT, "Above your limit",
                    "This expense is above your approval limit and nobody has a larger one. Ask the School Admin to "
                            + "change the limits.");
            case ESCALATE -> {
                e.escalated(limit);
                approvals.save(new ExpenseApproval(e.getId(), ApprovalAction.ESCALATED, actor, limit, note, now));
                expenses.flush();
                Map<String, Object> details = details(e);
                details.put("limitPaise", limit);
                audit.record(actor, "expense.escalated", "expense", e.getId(), details);
            }
            case APPROVE -> {
                BudgetCheck check = budgets.check(e.getHeadId(), FinancialYear.of(e.getExpenseDate()),
                        e.getTotalPaise());
                if ("OVER".equals(check.status()) && check.mode() == BudgetMode.BLOCK) {
                    throw new ApiException(HttpStatus.CONFLICT, "Over budget",
                            "Approving this would take the head over its budget for the year, and the settings "
                                    + "block that. Raise the budget or reject the expense.");
                }
                e.approved(actor, now, !"OK".equals(check.status()) && !"NONE".equals(check.status()));
                approvals.save(new ExpenseApproval(e.getId(), ApprovalAction.APPROVED, actor, limit, note, now));
                expenses.flush();
                Map<String, Object> details = details(e);
                details.put("budgetStatus", check.status());
                audit.record(actor, "expense.approved", "expense", e.getId(), details);
            }
            default -> throw new IllegalStateException();
        }
        return detail(e, actor.id(), true);
    }

    public ExpenseDetail reject(UUID id, RejectForm form, Actor actor) {
        TenantContext.require();
        Expense e = submitted(id);
        Long limit = settings.limitOf(actor.id()).orElse(null);
        if (!Approvals.mayAct(limit, e.getEscalatedAbovePaise())) {
            throw new ApiException(HttpStatus.CONFLICT, "Passed on",
                    "This expense was passed on to an approver with a larger limit than yours.");
        }
        String reason = form.reason().strip();
        Instant now = Instant.now();
        e.rejected(actor, now, reason);
        approvals.save(new ExpenseApproval(e.getId(), ApprovalAction.REJECTED, actor, limit, reason, now));
        expenses.flush();
        Map<String, Object> details = details(e);
        details.put("reason", reason);
        audit.record(actor, "expense.rejected", "expense", e.getId(), details);
        return detail(e, actor.id(), true);
    }

    // ------------------------------------------------------------------ payment

    /** Records how an approved expense was paid (409 unless approved, 400 for a future date or no bank account). */
    public ExpenseDetail markPaid(UUID id, PaidForm form, Actor actor) {
        TenantContext.require();
        Expense e = expenses.findForUpdate(id).orElseThrow(() -> ApiException.notFound("Expense"));
        if (e.getStatus() != ExpenseStatus.APPROVED) {
            throw new ApiException(HttpStatus.CONFLICT, e.getStatus() == ExpenseStatus.PAID ? "Already paid"
                    : "Not approved", e.getStatus() == ExpenseStatus.PAID ? "This expense is already paid."
                            : "An expense is paid once it is approved.");
        }
        LocalDate on = AccountsAccess.notFuture(form.paidOn(), "paidOn");
        if (on.isBefore(e.getExpenseDate())) {
            throw ApiException.badRequest("The payment date cannot be before the expense date.", "paidOn");
        }
        UUID bankAccountId = numbers.bankAccountFor(form.mode(), form.bankAccountId());
        String reference = AccountsForms.clean(form.reference());
        if (form.mode() == PayMode.CHEQUE && reference == null) {
            throw ApiException.badRequest("Give the cheque number.", "reference");
        }
        e.paid(on, form.mode(), bankAccountId, reference, actor, Instant.now());
        expenses.flush();
        Map<String, Object> details = details(e);
        details.put("paidOn", on.toString());
        details.put("mode", form.mode().name());
        if (reference != null) {
            details.put("reference", reference);
        }
        audit.record(actor, "expense.paid", "expense", e.getId(), details);
        return detail(e, actor.id(), false);
    }

    // ------------------------------------------------------------------ attachments

    public ExpenseDetail addFile(UUID id, IncomingFile file, Actor actor) {
        TenantContext.require();
        Expense e = expenses.findById(id).orElseThrow(() -> ApiException.notFound("Expense"));
        if (e.getStatus().locked()) {
            throw locked();
        }
        if (files.list(owner(e.getId())).size() >= MAX_FILES) {
            throw ApiException.conflict("An expense can have at most " + MAX_FILES + " files.", "file");
        }
        StoredFileInfo stored = files.put(owner(e.getId()), file, actor.id());
        audit.record(actor, "expense.file_added", "expense", e.getId(), Map.of("description", e.getDescription(),
                "file", stored.name()));
        return detail(e, actor.id(), false);
    }

    public ExpenseDetail removeFile(UUID id, UUID fileId, Actor actor) {
        TenantContext.require();
        Expense e = expenses.findById(id).orElseThrow(() -> ApiException.notFound("Expense"));
        if (e.getStatus().locked()) {
            throw locked();
        }
        StoredFileInfo info = files.list(owner(e.getId())).stream().filter(f -> f.id().equals(fileId)).findFirst()
                .orElseThrow(() -> ApiException.notFound("File"));
        files.delete(owner(e.getId()), fileId);
        audit.record(actor, "expense.file_removed", "expense", e.getId(), Map.of("description", e.getDescription(),
                "file", info.name()));
        return detail(e, actor.id(), false);
    }

    boolean exists(UUID id) {
        return expenses.existsById(id);
    }

    // ------------------------------------------------------------------ recurring expenses

    @Transactional(readOnly = true)
    public List<RecurringView> recurring() {
        TenantContext.require();
        Map<UUID, Head> heads = chart.byId();
        Map<UUID, CostCentre> centres = chart.costCentresById();
        Map<UUID, Vendor> vendorsById = vendors.byId();
        return recurring.findAllOrdered().stream().map(r -> view(r, heads, centres, vendorsById)).toList();
    }

    public RecurringView createRecurring(RecurringForm form, Actor actor) {
        TenantContext.require();
        RecurringExpense r = recurring.saveAndFlush(new RecurringExpense(recurringFields(form)));
        audit.record(actor, "recurring_expense.created", "recurring_expense", r.getId(), Map.of("name", r.getName(),
                "amountPaise", r.getAmountPaise()));
        return view(r, chart.byId(), chart.costCentresById(), vendors.byId());
    }

    public RecurringView updateRecurring(UUID id, RecurringForm form, Actor actor) {
        TenantContext.require();
        RecurringExpense r = recurring.findById(id).orElseThrow(() -> ApiException.notFound("Recurring expense"));
        r.update(recurringFields(form));
        recurring.flush();
        audit.record(actor, "recurring_expense.updated", "recurring_expense", id, Map.of("name", r.getName(),
                "amountPaise", r.getAmountPaise(), "active", r.isActive()));
        return view(r, chart.byId(), chart.costCentresById(), vendors.byId());
    }

    public void deleteRecurring(UUID id, Actor actor) {
        TenantContext.require();
        RecurringExpense r = recurring.findById(id).orElseThrow(() -> ApiException.notFound("Recurring expense"));
        recurring.delete(r);
        recurring.flush();
        audit.record(actor, "recurring_expense.deleted", "recurring_expense", id, Map.of("name", r.getName()));
    }

    /** Creates a draft for every active recurring expense that has none for the month yet (up to this month). */
    public GenerateResult generate(GenerateForm form, Actor actor) {
        TenantContext.require();
        YearMonth month = YearMonth.parse(form.month());
        if (month.isAfter(YearMonth.from(FinancialYear.today()))) {
            throw ApiException.badRequest("Drafts can be generated up to the current month.", "month");
        }
        List<Expense> created = new ArrayList<>();
        int skipped = 0;
        Map<UUID, Vendor> vendorsById = vendors.byId();
        for (RecurringExpense r : recurring.findAllOrdered()) {
            if (!r.isActive() || expenses.generated(r.getId(), month.atDay(1))) {
                skipped++;
                continue;
            }
            Vendor v = r.getVendorId() == null ? null : vendorsById.get(r.getVendorId());
            LocalDate date = month.atDay(Math.min(r.getDayOfMonth(), month.lengthOfMonth()));
            if (date.isAfter(FinancialYear.today())) {
                date = FinancialYear.today();
            }
            long tds = v == null ? 0 : AccountsMath.tds(r.getAmountPaise(), v.getTdsRatePercent());
            Expense e = new Expense(new Expense.Fields(r.getHeadId(), r.getCostCentreId(), r.getVendorId(),
                    v == null ? null : v.getName(), date, r.getAmountPaise(), r.getGstPaise(),
                    v == null ? null : v.getTdsSection(), v == null ? null : v.getTdsRatePercent(), tds,
                    r.getDescription() == null ? r.getName() + " for " + month : r.getDescription() + " (" + month
                            + ")", null), actor, r.getId(), month.atDay(1));
            expenses.save(e);
            r.generated(month.atDay(1));
            created.add(e);
        }
        expenses.flush();
        recurring.flush();
        audit.record(actor, "recurring_expenses.generated", "recurring_expense", null, Map.of("month",
                month.toString(), "created", created.size(), "skipped", skipped));
        return new GenerateResult(month.toString(), created.size(), skipped, rows(created));
    }

    private RecurringExpense.Fields recurringFields(RecurringForm form) {
        Head head = chart.activeHead(form.headId(), HeadKind.EXPENSE, "headId");
        CostCentre centre = chart.activeCostCentre(form.costCentreId(), "costCentreId");
        Vendor vendor = vendors.active(form.vendorId(), "vendorId");
        long gst = form.gstPaise() == null ? 0 : form.gstPaise();
        if (gst > 0 && !head.isGstApplicable()) {
            throw ApiException.badRequest("GST does not apply to this head.", "gstPaise");
        }
        return new RecurringExpense.Fields(form.name().strip(), head.getId(),
                centre == null ? null : centre.getId(), vendor == null ? null : vendor.getId(), form.amountPaise(),
                gst, form.dayOfMonth(), AccountsForms.clean(form.description()), form.active());
    }

    // ------------------------------------------------------------------ helpers

    private Expense.Fields fields(ExpenseForm form) {
        Head head = chart.activeHead(form.headId(), HeadKind.EXPENSE, "headId");
        CostCentre centre = chart.activeCostCentre(form.costCentreId(), "costCentreId");
        Vendor vendor = vendors.active(form.vendorId(), "vendorId");
        LocalDate date = AccountsAccess.notFuture(form.expenseDate(), "expenseDate");
        long gst = form.gstPaise() == null ? 0 : form.gstPaise();
        if (gst > 0 && !head.isGstApplicable()) {
            throw ApiException.badRequest("GST does not apply to this head.", "gstPaise");
        }
        if (gst > form.amountPaise()) {
            throw ApiException.badRequest("GST cannot be more than the amount.", "gstPaise");
        }
        String section = null;
        BigDecimal rate = null;
        long tds = 0;
        if (form.deductTds()) {
            if (vendor == null || vendor.getTdsSection() == null) {
                throw ApiException.badRequest("TDS needs a vendor with a TDS section.", "deductTds");
            }
            section = vendor.getTdsSection();
            rate = vendor.getTdsRatePercent();
            tds = AccountsMath.tds(form.amountPaise(), rate);
        }
        String vendorName = vendor != null ? vendor.getName() : AccountsForms.clean(form.vendorName());
        return new Expense.Fields(head.getId(), centre == null ? null : centre.getId(),
                vendor == null ? null : vendor.getId(), vendorName, date, form.amountPaise(), gst, section, rate, tds,
                form.description().strip(), AccountsForms.clean(form.billNumber()));
    }

    /** A draft or rejected expense the caller may change: its requester, or anyone with accounts.manage. */
    private Expense editable(UUID id, Actor actor) {
        Expense e = expenses.findForUpdate(id).orElseThrow(() -> ApiException.notFound("Expense"));
        if (e.getStatus() == ExpenseStatus.PAID) {
            throw locked();
        }
        if (e.getStatus() != ExpenseStatus.DRAFT && e.getStatus() != ExpenseStatus.REJECTED) {
            throw new ApiException(HttpStatus.CONFLICT, "Already submitted",
                    "This expense is " + e.getStatus().name().toLowerCase() + " and cannot be changed.");
        }
        if (e.getStatus() == ExpenseStatus.REJECTED) {
            e.reopened();
        }
        return e;
    }

    private Expense submitted(UUID id) {
        Expense e = expenses.findForUpdate(id).orElseThrow(() -> ApiException.notFound("Expense"));
        if (e.getStatus() != ExpenseStatus.SUBMITTED) {
            throw new ApiException(HttpStatus.CONFLICT, "Not waiting for approval",
                    "This expense is " + e.getStatus().name().toLowerCase() + ", not waiting for approval.");
        }
        return e;
    }

    private static ApiException locked() {
        return new ApiException(HttpStatus.CONFLICT, "Expense paid",
                "This expense is paid and cannot change. Record a correction as a new expense.");
    }

    private Map<String, Object> details(Expense e) {
        Map<String, Object> d = new LinkedHashMap<>();
        if (e.getNumber() != null) {
            d.put("number", e.getNumber());
        }
        d.put("description", e.getDescription());
        d.put("totalPaise", e.getTotalPaise());
        d.put("status", e.getStatus().name());
        if (e.getVendorName() != null) {
            d.put("vendor", e.getVendorName());
        }
        return d;
    }

    List<ExpenseRow> rows(List<Expense> list) {
        Map<UUID, Head> heads = chart.byId();
        Map<UUID, CostCentre> centres = chart.costCentresById();
        Map<UUID, List<StoredFileInfo>> attachments = list.isEmpty() ? Map.of()
                : files.list(MODULE, BILL, list.stream().map(Expense::getId).toList());
        return list.stream().map(e -> row(e, heads, centres, attachments.getOrDefault(e.getId(), List.of()).size()))
                .toList();
    }

    static ExpenseRow row(Expense e, Map<UUID, Head> heads, Map<UUID, CostCentre> centres, int attachments) {
        return new ExpenseRow(e.getId(), e.getNumber(), e.getStatus(), e.getExpenseDate(), e.getHeadId(),
                ChartService.codeOf(heads, e.getHeadId()), ChartService.nameOf(heads, e.getHeadId()),
                e.getCostCentreId(), ChartService.costCentreName(centres, e.getCostCentreId()), e.getVendorId(),
                e.getVendorName(), e.getAmountPaise(), e.getGstPaise(), e.getTotalPaise(), e.getTdsPaise(),
                e.getPaidPaise(), e.getDescription(), e.getRequestedByName(), e.isBudgetWarning(),
                e.getEscalatedAbovePaise(), e.getPaidOn(), e.getPaymentMode(), attachments, e.getSubmittedAt());
    }

    ExpenseDetail detail(Expense e, UUID viewerId, boolean viewerApproves) {
        Map<UUID, Head> heads = chart.byId();
        Map<UUID, CostCentre> centres = chart.costCentresById();
        List<FileRef> refs = files.list(owner(e.getId())).stream()
                .map(f -> new FileRef(f.id(), f.name(), f.contentType(), f.size())).toList();
        List<ApprovalView> history = approvals.findByExpense(e.getId()).stream()
                .map(a -> new ApprovalView(a.getId(), a.getAction(), a.getApproverName(), a.getLimitPaise(),
                        a.getNote(), a.getCreatedAt()))
                .toList();
        BudgetCheck check = budgets.check(e.getHeadId(), FinancialYear.of(e.getExpenseDate()),
                e.getStatus() == ExpenseStatus.APPROVED || e.getStatus() == ExpenseStatus.PAID ? 0
                        : e.getTotalPaise());
        String myDecision = null;
        if (e.getStatus() == ExpenseStatus.SUBMITTED && viewerApproves && viewerId != null) {
            myDecision = Approvals.decide(settings.limitOf(viewerId).orElse(null), e.getEscalatedAbovePaise(),
                    e.getTotalPaise(), settings.largestOtherLimit(viewerId)).name();
        }
        String bankName = e.getBankAccountId() == null ? null
                : banks.findById(e.getBankAccountId()).map(BankAccount::getName).orElse(null);
        boolean canEdit = e.getStatus() == ExpenseStatus.DRAFT || e.getStatus() == ExpenseStatus.REJECTED;
        return new ExpenseDetail(e.getId(), e.getNumber(), e.getFinancialYear(), e.getStatus(), e.getExpenseDate(),
                e.getHeadId(), ChartService.codeOf(heads, e.getHeadId()), ChartService.nameOf(heads, e.getHeadId()),
                e.getCostCentreId(), ChartService.costCentreName(centres, e.getCostCentreId()), e.getVendorId(),
                e.getVendorName(), e.getAmountPaise(), e.getGstPaise(), e.getTotalPaise(), e.getTdsSection(),
                e.getTdsRatePercent(), e.getTdsPaise(), e.getPaidPaise(), e.getDescription(), e.getBillNumber(),
                e.getRecurringId(), e.isBudgetWarning(), e.getEscalatedAbovePaise(), e.getRequestedByName(),
                e.getCreatedAt(), e.getSubmittedAt(), e.getApprovedByName(), e.getApprovedAt(),
                e.getRejectedByName(), e.getRejectedAt(), e.getRejectReason(), e.getPaidOn(), e.getPaymentMode(),
                e.getBankAccountId(), bankName, e.getPaymentReference(), e.getPaidByName(), e.getPaidAt(),
                e.getClearedOn(), refs, history, check, myDecision, canEdit, e.getVersion());
    }

    private static RecurringView view(RecurringExpense r, Map<UUID, Head> heads, Map<UUID, CostCentre> centres,
            Map<UUID, Vendor> vendorsById) {
        Vendor v = r.getVendorId() == null ? null : vendorsById.get(r.getVendorId());
        return new RecurringView(r.getId(), r.getName(), r.getHeadId(), ChartService.nameOf(heads, r.getHeadId()),
                r.getCostCentreId(), ChartService.costCentreName(centres, r.getCostCentreId()), r.getVendorId(),
                v == null ? null : v.getName(), r.getAmountPaise(), r.getGstPaise(), r.getDayOfMonth(),
                r.getDescription(), r.isActive(), r.getLastGeneratedMonth());
    }

    static boolean canRead(java.util.Set<String> permissions) {
        return permissions.contains(Permissions.ACCOUNTS_READ) || permissions.contains(Permissions.ACCOUNTS_MANAGE)
                || permissions.contains(Permissions.ACCOUNTS_APPROVE);
    }

    static Function<Expense, UUID> id() {
        return Expense::getId;
    }
}
