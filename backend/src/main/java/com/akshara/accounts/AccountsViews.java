package com.akshara.accounts;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.akshara.platform.SchoolProfile;

/** Response bodies of the accounts API (docs/api/phase-2-accounts.md). */
public final class AccountsViews {

    private AccountsViews() {
    }

    // ------------------------------------------------------------------ masking

    /** "XXXXXX234F": a PAN with only its last four characters. */
    static String maskPan(String pan) {
        return pan == null ? null : "XXXXXX" + pan.substring(pan.length() - 4);
    }

    /** "XXXXXXXX9012": an account number with only its last four digits. */
    static String maskAccount(String account) {
        return account == null ? null : "X".repeat(Math.max(0, account.length() - 4))
                + account.substring(Math.max(0, account.length() - 4));
    }

    /** "XXXXXX1234": a phone number with only its last four digits. */
    static String maskPhone(String phone) {
        return maskAccount(phone);
    }

    // ------------------------------------------------------------------ settings

    public record PersonRef(UUID id, String name) {
    }

    public record ApproverView(UUID userId, String name, Long limitPaise, boolean canApprove) {
    }

    public record SettingsView(BudgetMode budgetMode, boolean donation80gRegistered, String donation80gNumber,
            long pettyCashFloatPaise, boolean saved, Instant updatedAt, List<ApproverView> approvers,
            List<PersonRef> candidates) {
    }

    // ------------------------------------------------------------------ chart

    public record HeadView(UUID id, String code, String name, HeadKind kind, UUID parentId, String parentName,
            boolean gstApplicable, UUID feeHeadId, String systemCode, boolean active, int displayOrder,
            boolean inUse) {
    }

    public record CostCentreView(UUID id, String code, String name, boolean active, boolean inUse) {
    }

    // ------------------------------------------------------------------ vendors

    public record VendorView(UUID id, String name, String gstin, String panMasked, String phoneMasked, String email,
            String address, String bankAccountMasked, String bankIfsc, String tdsSection, BigDecimal tdsRatePercent,
            boolean active, boolean inUse) {
    }

    public record VendorLedgerRow(UUID expenseId, String number, LocalDate date, String description,
            ExpenseStatus status, long billPaise, long gstPaise, long tdsPaise, long paidPaise, LocalDate paidOn,
            PayMode mode) {
    }

    public record VendorLedger(VendorView vendor, LocalDate from, LocalDate to, List<VendorLedgerRow> rows,
            long billedPaise, long gstPaise, long tdsPaise, long paidPaise, long outstandingPaise) {
    }

    // ------------------------------------------------------------------ bank and cash

    public record BankAccountView(UUID id, String name, String bankName, String accountNumberMasked, String ifsc,
            boolean active, boolean inUse, long balancePaise, int unclearedCount) {
    }

    public record OpeningBalanceView(UUID bankAccountId, String name, long amountPaise) {
    }

    public record OpeningBalancesView(String financialYear, long cashPaise, List<OpeningBalanceView> accounts) {
    }

    /** A line of the cash book or a bank book. {@code kind} is EXPENSE, INCOME, FEES, PETTY_TOP_UP or OPENING. */
    public record BookRow(LocalDate date, String kind, UUID refId, String particulars, String reference,
            long receiptPaise, long paymentPaise, long balancePaise, boolean cleared) {
    }

    public record Book(UUID bankAccountId, String name, LocalDate from, LocalDate to, long openingPaise,
            List<BookRow> rows, long receiptsPaise, long paymentsPaise, long closingPaise) {
    }

    public record AccountBalance(UUID id, String name, long balancePaise) {
    }

    public record CashPosition(LocalDate asOf, long cashPaise, long pettyCashPaise, List<AccountBalance> banks,
            long bankPaise, long totalPaise) {
    }

    /** What the bank should show on the statement date: the book balance adjusted for uncleared items. */
    public record Reconciliation(UUID bankAccountId, String name, LocalDate statementDate, long bookBalancePaise,
            List<UnclearedItem> unclearedPayments, List<UnclearedItem> unclearedReceipts, long unclearedPaymentsPaise,
            long unclearedReceiptsPaise, long expectedStatementBalancePaise, List<StatementLineView> recentLines) {
    }

    public record UnclearedItem(UUID id, String kind, LocalDate date, String label, String reference,
            long amountPaise) {
    }

    public record StatementLineView(UUID id, LocalDate statementDate, LocalDate date, String description,
            String reference, long debitPaise, long creditPaise, UUID expenseId, UUID incomeId, String matchedLabel) {
    }

    public record MatchView(UUID expenseId, UUID incomeId, String label, String quality) {
    }

    public record PreviewLine(int index, LocalDate date, String description, String reference, long debitPaise,
            long creditPaise, MatchView match, boolean duplicate, String problem) {
    }

    public record CandidateView(UUID id, String kind, LocalDate date, String label, String reference,
            long amountPaise) {
    }

    public record StatementPreview(List<PreviewLine> lines, List<CandidateView> payments,
            List<CandidateView> receipts, int matched, int probable, int unmatched) {
    }

    public record StatementImportResult(int imported, int cleared, int skipped) {
    }

    public record ClearResult(int cleared) {
    }

    // ------------------------------------------------------------------ expenses

    public record FileRef(UUID id, String name, String contentType, long size) {
    }

    public record ApprovalView(UUID id, ApprovalAction action, String approverName, Long limitPaise, String note,
            Instant at) {
    }

    /** Where the head's budget stands if this expense counts: the mode decides whether OVER blocks. */
    public record BudgetCheck(long budgetPaise, long committedPaise, long afterPaise, int percentAfter,
            String status, BudgetMode mode) {
    }

    public record ExpenseRow(UUID id, String number, ExpenseStatus status, LocalDate expenseDate, UUID headId,
            String headCode, String headName, UUID costCentreId, String costCentreName, UUID vendorId,
            String vendorName, long amountPaise, long gstPaise, long totalPaise, long tdsPaise, long paidPaise,
            String description, String requestedByName, boolean budgetWarning, Long escalatedAbovePaise,
            LocalDate paidOn, PayMode paymentMode, int attachmentCount, Instant submittedAt) {
    }

    public record ExpenseList(List<ExpenseRow> expenses, long draftCount, long submittedCount, long approvedCount,
            long paidCount, long rejectedCount) {
    }

    /** The submitted expenses with whether the signed-in approver can act on each (their limit decides). */
    public record ApprovalQueue(List<ExpenseRow> expenses, Long myLimitPaise, boolean limited,
            List<UUID> actionable) {
    }

    public record ExpenseDetail(UUID id, String number, String financialYear, ExpenseStatus status,
            LocalDate expenseDate, UUID headId, String headCode, String headName, UUID costCentreId,
            String costCentreName, UUID vendorId, String vendorName, long amountPaise, long gstPaise,
            long totalPaise, String tdsSection, BigDecimal tdsRatePercent, long tdsPaise, long paidPaise,
            String description, String billNumber, UUID recurringId, boolean budgetWarning,
            Long escalatedAbovePaise, String requestedByName, Instant createdAt, Instant submittedAt,
            String approvedByName, Instant approvedAt, String rejectedByName, Instant rejectedAt,
            String rejectReason, LocalDate paidOn, PayMode paymentMode, UUID bankAccountId, String bankAccountName,
            String paymentReference, String paidByName, Instant paidAt, LocalDate clearedOn, List<FileRef> files,
            List<ApprovalView> approvals, BudgetCheck budget, String myDecision, boolean canEdit, long version) {
    }

    public record RecurringView(UUID id, String name, UUID headId, String headName, UUID costCentreId,
            String costCentreName, UUID vendorId, String vendorName, long amountPaise, long gstPaise, int dayOfMonth,
            String description, boolean active, LocalDate lastGeneratedMonth) {
    }

    public record GenerateResult(String month, int created, int skipped, List<ExpenseRow> expenses) {
    }

    // ------------------------------------------------------------------ petty cash

    public record PettyRow(UUID id, LocalDate date, PettyKind kind, long amountPaise, UUID headId, String headName,
            String description, String recordedByName, long balanceAfterPaise) {
    }

    public record PettyCashBook(long floatPaise, long balancePaise, long topUpNeededPaise, LocalDate from,
            LocalDate to, long openingPaise, List<PettyRow> entries, long topUpsPaise, long spentPaise) {
    }

    // ------------------------------------------------------------------ income

    public record IncomeRow(UUID id, String receiptNo, IncomeKind kind, UUID headId, String headName,
            LocalDate receivedOn, long amountPaise, String payerName, PayMode mode, String reference, String status,
            String eightyGNumber, String description) {
    }

    public record SchoolHeader(String name, String address, String phone, String contactEmail, String board,
            String udiseCode) {

        static SchoolHeader of(SchoolProfile p) {
            return p == null ? new SchoolHeader("", null, null, null, null, null)
                    : new SchoolHeader(p.name(), p.address(), p.phone(), p.contactEmail(),
                            p.board() == null ? null : p.board().name(), p.udiseCode());
        }
    }

    public record IncomeReceiptView(UUID id, String receiptNo, String financialYear, IncomeKind kind, UUID headId,
            String headName, LocalDate receivedOn, long amountPaise, String amountInWords, String payerName,
            String payerPanMasked, String payerAddress, PayMode mode, UUID bankAccountId, String bankAccountName,
            String reference, String eightyGNumber, String eightyGRegistration, String description, String status,
            String cancelReason, String cancelledByName, Instant cancelledAt, String receivedByName,
            Instant createdAt, LocalDate clearedOn, SchoolHeader school) {
    }

    // ------------------------------------------------------------------ budgets

    public record BudgetRow(UUID headId, String headCode, String headName, HeadKind kind, UUID budgetId,
            long annualPaise, BudgetSpread spread, List<Long> months, long actualPaise, List<Long> actualMonths,
            int percentUsed, String status) {
    }

    public record BudgetsView(String financialYear, BudgetMode mode, List<BudgetRow> rows, long budgetPaise,
            long actualPaise, int percentUsed, int nearCount, int overCount) {
    }

    // ------------------------------------------------------------------ fee reconciliation and the other modules

    public record FeeDay(LocalDate day, long accountsPaise, int accountsCount, long feesPaise, long feesCount,
            long differencePaise) {
    }

    public record FeeReconciliation(LocalDate from, LocalDate to, List<FeeDay> days, long accountsPaise,
            long feesPaise, long differencePaise, int daysWithDifferences) {
    }

    public record PayrollCostView(String month, UUID runId, int staffCount, long grossPaise, long employerPaise,
            long costPaise, long netPaise, Instant approvedAt) {
    }

    // ------------------------------------------------------------------ reports

    public record MonthLine(String month, long feeIncomePaise, long otherIncomePaise, long incomePaise,
            long expensePaise, long payrollPaise, long pettyCashPaise, long totalExpensePaise, long netPaise) {
    }

    public record IncomeVsExpense(String financialYear, List<MonthLine> months, MonthLine totals) {
    }

    public record HeadLine(UUID headId, String code, String name, long amountPaise, long count) {
    }

    public record ReceiptsAndPayments(LocalDate from, LocalDate to, long openingPaise, List<HeadLine> receipts,
            long receiptsPaise, List<HeadLine> payments, long paymentsPaise, long closingPaise) {
    }

    public record HeadWiseRow(UUID headId, String code, String name, long amountPaise, long gstPaise,
            long count, List<HeadLine> costCentres) {
    }

    public record HeadWiseExpense(LocalDate from, LocalDate to, List<HeadWiseRow> rows, long amountPaise,
            long gstPaise, long count) {
    }

    public record GstRow(UUID vendorId, String vendorName, String gstin, long bills, long taxablePaise,
            long gstPaise) {
    }

    public record GstSummary(LocalDate from, LocalDate to, List<GstRow> rows, long taxablePaise, long gstPaise,
            long withoutGstinPaise) {
    }

    public record DayBookRow(String kind, UUID refId, String number, String particulars, String mode,
            String account, long receiptPaise, long paymentPaise) {
    }

    public record DayBook(LocalDate date, List<DayBookRow> rows, long receiptsPaise, long paymentsPaise) {
    }

    // ------------------------------------------------------------------ the dashboard card

    /** This month's figures for the School Admin's and Principal's dashboards (accounts.read). */
    public record FinanceCard(String month, long incomePaise, long feeIncomePaise, long otherIncomePaise,
            long expensePaise, long payrollPaise, long cashInHandPaise, long bankPaise, long awaitingApproval,
            long awaitingApprovalPaise) {
    }
}
