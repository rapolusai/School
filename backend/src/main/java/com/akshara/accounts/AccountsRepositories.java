package com.akshara.accounts;

/*
 * The accounts module's Spring Data repositories, one interface per entity, in one file because each is a few
 * queries; all package-private.
 */

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

interface AccountsSettingsRepository extends JpaRepository<AccountsSettings, UUID> {

    /** The school's settings row (row-level security and the tenant filter leave at most one). */
    @Query("select s from AccountsSettings s")
    List<AccountsSettings> findForSchool();
}

interface ApproverRepository extends JpaRepository<Approver, UUID> {

    @Query("select a from Approver a order by a.limitPaise nulls last, a.userName")
    List<Approver> findAllOrdered();

    Optional<Approver> findByUserId(UUID userId);
}

interface HeadRepository extends JpaRepository<Head, UUID> {

    @Query("select h from Head h order by h.kind, h.displayOrder, lower(h.name)")
    List<Head> findAllOrdered();

    @Query("select count(h) > 0 from Head h where h.code = ?1")
    boolean codeTaken(String code);

    @Query("select count(h) > 0 from Head h where lower(h.name) = lower(?1) and h.id <> ?2")
    boolean nameTaken(String name, UUID exceptId);

    Optional<Head> findBySystemCode(String systemCode);

    Optional<Head> findByFeeHeadId(UUID feeHeadId);

    @Query("select count(h) > 0 from Head h where h.parentId = ?1")
    boolean hasChildren(UUID parentId);
}

interface CostCentreRepository extends JpaRepository<CostCentre, UUID> {

    @Query("select c from CostCentre c order by lower(c.name)")
    List<CostCentre> findAllOrdered();

    @Query("select count(c) > 0 from CostCentre c where c.code = ?1")
    boolean codeTaken(String code);

    @Query("select count(c) > 0 from CostCentre c where lower(c.name) = lower(?1) and c.id <> ?2")
    boolean nameTaken(String name, UUID exceptId);
}

interface VendorRepository extends JpaRepository<Vendor, UUID> {

    @Query("select v from Vendor v order by lower(v.name)")
    List<Vendor> findAllOrdered();

    @Query("select count(v) > 0 from Vendor v where lower(v.name) = lower(?1) and v.id <> ?2")
    boolean nameTaken(String name, UUID exceptId);
}

interface BankAccountRepository extends JpaRepository<BankAccount, UUID> {

    @Query("select b from BankAccount b order by lower(b.name)")
    List<BankAccount> findAllOrdered();

    @Query("select count(b) > 0 from BankAccount b where lower(b.name) = lower(?1) and b.id <> ?2")
    boolean nameTaken(String name, UUID exceptId);
}

interface OpeningBalanceRepository extends JpaRepository<OpeningBalance, UUID> {

    List<OpeningBalance> findByFinancialYear(String financialYear);

    @Query("select o from OpeningBalance o where o.financialYear = ?1 and o.bankAccountId = ?2")
    Optional<OpeningBalance> findBank(String financialYear, UUID bankAccountId);

    @Query("select o from OpeningBalance o where o.financialYear = ?1 and o.bankAccountId is null")
    Optional<OpeningBalance> findCash(String financialYear);
}

interface RecurringExpenseRepository extends JpaRepository<RecurringExpense, UUID> {

    @Query("select r from RecurringExpense r order by lower(r.name)")
    List<RecurringExpense> findAllOrdered();
}

interface ExpenseRepository extends JpaRepository<Expense, UUID> {

    /** Reads and locks the expense, so two people never approve or pay it at the same time. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from Expense e where e.id = ?1")
    Optional<Expense> findForUpdate(UUID id);

    @Query("select e from Expense e order by e.expenseDate desc, e.createdAt desc")
    List<Expense> findAllNewestFirst();

    @Query("select e from Expense e where e.status = com.akshara.accounts.ExpenseStatus.SUBMITTED "
            + "order by e.submittedAt")
    List<Expense> findSubmitted();

    @Query("select e from Expense e where e.expenseDate between ?1 and ?2 order by e.expenseDate, e.createdAt")
    List<Expense> findBetween(LocalDate from, LocalDate to);

    @Query("select e from Expense e where e.status = com.akshara.accounts.ExpenseStatus.PAID "
            + "and e.paidOn between ?1 and ?2 order by e.paidOn, e.paidAt")
    List<Expense> findPaidBetween(LocalDate from, LocalDate to);

    @Query("select e from Expense e where e.vendorId = ?1 order by e.expenseDate, e.createdAt")
    List<Expense> findByVendor(UUID vendorId);

    /** Approved or paid expenses of a head in the year: what counts against its budget. */
    @Query("select coalesce(sum(e.totalPaise), 0) from Expense e where e.headId = ?1 and e.financialYear = ?2 "
            + "and e.status in (com.akshara.accounts.ExpenseStatus.APPROVED, com.akshara.accounts.ExpenseStatus.PAID)")
    long committedForHead(UUID headId, String financialYear);

    @Query("select e from Expense e where e.financialYear = ?1 and e.status in "
            + "(com.akshara.accounts.ExpenseStatus.APPROVED, com.akshara.accounts.ExpenseStatus.PAID)")
    List<Expense> findCommittedInYear(String financialYear);

    /** Paid through a bank account and not yet cleared. */
    @Query("select e from Expense e where e.status = com.akshara.accounts.ExpenseStatus.PAID "
            + "and e.bankAccountId = ?1 and e.clearedOn is null order by e.paidOn")
    List<Expense> findUncleared(UUID bankAccountId);

    @Query("select count(e) > 0 from Expense e where e.headId = ?1")
    boolean headUsed(UUID headId);

    @Query("select count(e) > 0 from Expense e where e.costCentreId = ?1")
    boolean costCentreUsed(UUID costCentreId);

    @Query("select count(e) > 0 from Expense e where e.vendorId = ?1")
    boolean vendorUsed(UUID vendorId);

    @Query("select count(e) > 0 from Expense e where e.bankAccountId = ?1")
    boolean bankAccountUsed(UUID bankAccountId);

    @Query("select count(e) > 0 from Expense e where e.recurringId = ?1 and e.recurringMonth = ?2")
    boolean generated(UUID recurringId, LocalDate month);
}

interface ExpenseApprovalRepository extends JpaRepository<ExpenseApproval, UUID> {

    @Query("select a from ExpenseApproval a where a.expenseId = ?1 order by a.createdAt")
    List<ExpenseApproval> findByExpense(UUID expenseId);
}

interface PettyCashEntryRepository extends JpaRepository<PettyCashEntry, UUID> {

    @Query("select p from PettyCashEntry p order by p.entryDate, p.createdAt")
    List<PettyCashEntry> findAllOrdered();

    @Query("select p from PettyCashEntry p where p.entryDate between ?1 and ?2 order by p.entryDate, p.createdAt")
    List<PettyCashEntry> findBetween(LocalDate from, LocalDate to);

    @Query("select coalesce(sum(case when p.kind = com.akshara.accounts.PettyKind.TOP_UP then p.amountPaise "
            + "else -p.amountPaise end), 0) from PettyCashEntry p where p.entryDate < ?1")
    long balanceBefore(LocalDate date);

    @Query("select count(p) > 0 from PettyCashEntry p where p.headId = ?1")
    boolean headUsed(UUID headId);
}

interface IncomeReceiptRepository extends JpaRepository<IncomeReceipt, UUID> {

    @Query("select r from IncomeReceipt r order by r.receivedOn desc, r.seq desc")
    List<IncomeReceipt> findAllNewestFirst();

    /** The receipt locked until the transaction ends, so two cancellations cannot both pass the status check. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from IncomeReceipt r where r.id = ?1")
    Optional<IncomeReceipt> findForUpdate(UUID id);

    @Query("select r from IncomeReceipt r where r.receivedOn between ?1 and ?2 order by r.receivedOn, r.seq")
    List<IncomeReceipt> findBetween(LocalDate from, LocalDate to);

    @Query("select r from IncomeReceipt r where r.status = 'ISSUED' and r.bankAccountId = ?1 "
            + "and r.clearedOn is null order by r.receivedOn")
    List<IncomeReceipt> findUncleared(UUID bankAccountId);

    @Query("select count(r) > 0 from IncomeReceipt r where r.headId = ?1")
    boolean headUsed(UUID headId);

    @Query("select count(r) > 0 from IncomeReceipt r where r.bankAccountId = ?1")
    boolean bankAccountUsed(UUID bankAccountId);
}

interface FeeIncomeDayRepository extends JpaRepository<FeeIncomeDay, UUID> {

    Optional<FeeIncomeDay> findByDay(LocalDate day);

    @Query("select d from FeeIncomeDay d where d.day between ?1 and ?2 order by d.day")
    List<FeeIncomeDay> findBetween(LocalDate from, LocalDate to);

    @Query("select coalesce(sum(d.amountPaise), 0) from FeeIncomeDay d where d.day between ?1 and ?2")
    long totalBetween(LocalDate from, LocalDate to);

    @Modifying
    @Query("delete from FeeIncomeDay d where d.day between ?1 and ?2")
    int deleteBetween(LocalDate from, LocalDate to);
}

interface PayrollCostRepository extends JpaRepository<PayrollCost, UUID> {

    Optional<PayrollCost> findByMonth(LocalDate month);

    @Query("select p from PayrollCost p where p.month between ?1 and ?2 order by p.month")
    List<PayrollCost> findBetween(LocalDate from, LocalDate to);
}

interface BudgetRepository extends JpaRepository<Budget, UUID> {

    @Query("select b from Budget b where b.financialYear = ?1")
    List<Budget> findByYear(String financialYear);

    @Query("select b from Budget b where b.financialYear = ?1 and b.headId = ?2")
    Optional<Budget> findByYearAndHead(String financialYear, UUID headId);

    @Query("select count(b) > 0 from Budget b where b.headId = ?1")
    boolean headUsed(UUID headId);
}

interface BudgetMonthRepository extends JpaRepository<BudgetMonth, UUID> {

    @Query("select m from BudgetMonth m where m.budgetId = ?1 order by m.monthIndex")
    List<BudgetMonth> findByBudget(UUID budgetId);

    @Query("select m from BudgetMonth m where m.budgetId in ?1")
    List<BudgetMonth> findByBudgets(List<UUID> budgetIds);

    @Modifying
    @Query("delete from BudgetMonth m where m.budgetId = ?1")
    int deleteByBudget(UUID budgetId);
}

interface BankStatementLineRepository extends JpaRepository<BankStatementLine, UUID> {

    @Query("select l from BankStatementLine l where l.bankAccountId = ?1 order by l.lineDate desc, l.createdAt desc")
    List<BankStatementLine> findByAccount(UUID bankAccountId);

    @Query("select count(l) > 0 from BankStatementLine l where l.bankAccountId = ?1 and l.lineDate = ?2 "
            + "and l.debitPaise = ?3 and l.creditPaise = ?4 and l.description = ?5")
    boolean exists(UUID bankAccountId, LocalDate lineDate, long debit, long credit, String description);
}
