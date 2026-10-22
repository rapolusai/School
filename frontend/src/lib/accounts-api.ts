import { apiFetch } from "./api";
import { sendForm } from "./files-api";
import { downloadXlsx, reportQuery, type ReportParams } from "./reports-api";
import type {
  AccountsSettingsRequest,
  AccountsSettingsView,
  ApprovalQueue,
  ApproversRequest,
  BankAccountRequest,
  BankAccountView,
  Book,
  BudgetRequest,
  BudgetsView,
  CashPosition,
  ClearRequest,
  CostCentreRequest,
  CostCentreView,
  DayBook,
  ExpenseDetail,
  ExpenseList,
  ExpenseRequest,
  ExpenseStatus,
  FeeReconciliation,
  GenerateResult,
  GstSummary,
  HeadRequest,
  HeadView,
  HeadWiseExpense,
  IncomeReceiptView,
  IncomeRequest,
  IncomeRow,
  IncomeVsExpense,
  OpeningBalancesRequest,
  OpeningBalancesView,
  ExpensePaidRequest,
  PayrollCostView,
  PettyCashBook,
  PettyCashRequest,
  ReceiptsAndPayments,
  Reconciliation,
  RecurringRequest,
  RecurringView,
  StatementImportRequest,
  StatementImportResult,
  StatementPreview,
  VendorLedger,
  VendorRequest,
  VendorView,
} from "./types";

/* Endpoint helpers for docs/api/phase-2-accounts.md. */

const id = (value: string) => encodeURIComponent(value);
const q = (params: ReportParams) => reportQuery(params);

export type Period = { from: string; to: string };
export type YearParams = { financialYear?: string };

/** The reports' CSV downloads (opened with the signed-in user's token; the server sets the file name). */
export const accountsPaths = {
  incomeVsExpenseCsv: (p: YearParams) => `/api/accounts/reports/income-vs-expense.csv${q(p)}`,
  receiptsAndPaymentsCsv: (p: Period) => `/api/accounts/reports/receipts-and-payments.csv${q(p)}`,
  budgetVsActualCsv: (p: YearParams) => `/api/accounts/reports/budget-vs-actual.csv${q(p)}`,
  headWiseCsv: (p: Period) => `/api/accounts/reports/head-wise.csv${q(p)}`,
  vendorLedgerCsv: (p: Period & { vendorId: string }) => `/api/accounts/reports/vendor-ledger.csv${q(p)}`,
  booksCsv: (p: Period & { bankAccountId?: string }) => `/api/accounts/reports/books.csv${q(p)}`,
  gstSummaryCsv: (p: Period) => `/api/accounts/reports/gst-summary.csv${q(p)}`,
  dayBookCsv: (p: { date: string }) => `/api/accounts/reports/day-book.csv${q(p)}`,
};

/** Downloads a CSV with the signed-in user's token; the same authenticated download the Excel reports use. */
export function downloadAccountsFile(path: string, fallbackName: string): Promise<string> {
  return downloadXlsx(path, fallbackName);
}

export const accountsApi = {
  settings: () => apiFetch<AccountsSettingsView>("/api/accounts/settings"),
  saveSettings: (body: AccountsSettingsRequest) =>
    apiFetch<AccountsSettingsView>("/api/accounts/settings", { method: "PUT", body }),
  saveApprovers: (body: ApproversRequest) =>
    apiFetch<AccountsSettingsView>("/api/accounts/settings/approvers", { method: "PUT", body }),

  heads: () => apiFetch<HeadView[]>("/api/accounts/heads"),
  addStandardHeads: () => apiFetch<HeadView[]>("/api/accounts/heads/standard", { method: "POST" }),
  createHead: (body: HeadRequest) => apiFetch<HeadView>("/api/accounts/heads", { method: "POST", body }),
  updateHead: (headId: string, body: HeadRequest) =>
    apiFetch<HeadView>(`/api/accounts/heads/${id(headId)}`, { method: "PUT", body }),

  costCentres: () => apiFetch<CostCentreView[]>("/api/accounts/cost-centres"),
  createCostCentre: (body: CostCentreRequest) =>
    apiFetch<CostCentreView>("/api/accounts/cost-centres", { method: "POST", body }),
  updateCostCentre: (centreId: string, body: CostCentreRequest) =>
    apiFetch<CostCentreView>(`/api/accounts/cost-centres/${id(centreId)}`, { method: "PUT", body }),
  deleteCostCentre: (centreId: string) =>
    apiFetch<void>(`/api/accounts/cost-centres/${id(centreId)}`, { method: "DELETE" }),

  vendors: () => apiFetch<VendorView[]>("/api/accounts/vendors"),
  vendor: (vendorId: string) => apiFetch<VendorView>(`/api/accounts/vendors/${id(vendorId)}`),
  vendorLedger: (vendorId: string, p: Period) =>
    apiFetch<VendorLedger>(`/api/accounts/vendors/${id(vendorId)}/ledger${q(p)}`),
  createVendor: (body: VendorRequest) => apiFetch<VendorView>("/api/accounts/vendors", { method: "POST", body }),
  updateVendor: (vendorId: string, body: VendorRequest) =>
    apiFetch<VendorView>(`/api/accounts/vendors/${id(vendorId)}`, { method: "PUT", body }),
  deleteVendor: (vendorId: string) => apiFetch<void>(`/api/accounts/vendors/${id(vendorId)}`, { method: "DELETE" }),

  bankAccounts: () => apiFetch<BankAccountView[]>("/api/accounts/bank-accounts"),
  createBankAccount: (body: BankAccountRequest) =>
    apiFetch<BankAccountView>("/api/accounts/bank-accounts", { method: "POST", body }),
  updateBankAccount: (accountId: string, body: BankAccountRequest) =>
    apiFetch<BankAccountView>(`/api/accounts/bank-accounts/${id(accountId)}`, { method: "PUT", body }),
  deleteBankAccount: (accountId: string) =>
    apiFetch<void>(`/api/accounts/bank-accounts/${id(accountId)}`, { method: "DELETE" }),
  openingBalances: (financialYear: string) =>
    apiFetch<OpeningBalancesView>(`/api/accounts/opening-balances/${id(financialYear)}`),
  saveOpeningBalances: (financialYear: string, body: OpeningBalancesRequest) =>
    apiFetch<OpeningBalancesView>(`/api/accounts/opening-balances/${id(financialYear)}`, { method: "PUT", body }),
  book: (p: Period & { bankAccountId?: string }) => apiFetch<Book>(`/api/accounts/books${q(p)}`),
  cashPosition: () => apiFetch<CashPosition>("/api/accounts/cash-position"),
  reconciliation: (accountId: string, statementDate: string) =>
    apiFetch<Reconciliation>(`/api/accounts/bank-accounts/${id(accountId)}/reconciliation${q({ statementDate })}`),
  clear: (accountId: string, body: ClearRequest) =>
    apiFetch<{ cleared: number }>(`/api/accounts/bank-accounts/${id(accountId)}/clear`, { method: "POST", body }),
  previewStatement: (accountId: string, file: File) => {
    const form = new FormData();
    form.append("file", file);
    return sendForm<StatementPreview>(`/api/accounts/bank-accounts/${id(accountId)}/statement/preview`, form);
  },
  importStatement: (accountId: string, body: StatementImportRequest) =>
    apiFetch<StatementImportResult>(`/api/accounts/bank-accounts/${id(accountId)}/statement/import`, {
      method: "POST",
      body,
    }),

  expenses: (p: { status?: ExpenseStatus | ""; from?: string; to?: string } = {}) =>
    apiFetch<ExpenseList>(`/api/accounts/expenses${q(p)}`),
  queue: () => apiFetch<ApprovalQueue>("/api/accounts/expenses/queue"),
  expense: (expenseId: string) => apiFetch<ExpenseDetail>(`/api/accounts/expenses/${id(expenseId)}`),
  createExpense: (body: ExpenseRequest, submit: boolean) =>
    apiFetch<ExpenseDetail>(`/api/accounts/expenses${q({ submit })}`, { method: "POST", body }),
  updateExpense: (expenseId: string, body: ExpenseRequest) =>
    apiFetch<ExpenseDetail>(`/api/accounts/expenses/${id(expenseId)}`, { method: "PUT", body }),
  submitExpense: (expenseId: string) =>
    apiFetch<ExpenseDetail>(`/api/accounts/expenses/${id(expenseId)}/submit`, { method: "POST" }),
  approveExpense: (expenseId: string, note: string | null) =>
    apiFetch<ExpenseDetail>(`/api/accounts/expenses/${id(expenseId)}/approve`, { method: "POST", body: { note } }),
  rejectExpense: (expenseId: string, reason: string) =>
    apiFetch<ExpenseDetail>(`/api/accounts/expenses/${id(expenseId)}/reject`, { method: "POST", body: { reason } }),
  markPaid: (expenseId: string, body: ExpensePaidRequest) =>
    apiFetch<ExpenseDetail>(`/api/accounts/expenses/${id(expenseId)}/paid`, { method: "POST", body }),
  deleteExpense: (expenseId: string) => apiFetch<void>(`/api/accounts/expenses/${id(expenseId)}`, { method: "DELETE" }),
  addAttachment: (expenseId: string, file: File) => {
    const form = new FormData();
    form.append("file", file);
    return sendForm<ExpenseDetail>(`/api/accounts/expenses/${id(expenseId)}/attachments`, form);
  },
  removeAttachment: (expenseId: string, fileId: string) =>
    apiFetch<ExpenseDetail>(`/api/accounts/expenses/${id(expenseId)}/attachments/${id(fileId)}`, {
      method: "DELETE",
    }),

  recurring: () => apiFetch<RecurringView[]>("/api/accounts/recurring"),
  createRecurring: (body: RecurringRequest) =>
    apiFetch<RecurringView>("/api/accounts/recurring", { method: "POST", body }),
  updateRecurring: (recurringId: string, body: RecurringRequest) =>
    apiFetch<RecurringView>(`/api/accounts/recurring/${id(recurringId)}`, { method: "PUT", body }),
  deleteRecurring: (recurringId: string) =>
    apiFetch<void>(`/api/accounts/recurring/${id(recurringId)}`, { method: "DELETE" }),
  generateRecurring: (month: string) =>
    apiFetch<GenerateResult>("/api/accounts/recurring/generate", { method: "POST", body: { month } }),

  pettyCash: (p: Period) => apiFetch<PettyCashBook>(`/api/accounts/petty-cash${q(p)}`),
  recordPettyCash: (body: PettyCashRequest) =>
    apiFetch<PettyCashBook>("/api/accounts/petty-cash", { method: "POST", body }),

  income: (p: Partial<Period> = {}) => apiFetch<IncomeRow[]>(`/api/accounts/income${q(p)}`),
  receipt: (receiptId: string) => apiFetch<IncomeReceiptView>(`/api/accounts/income/${id(receiptId)}`),
  recordIncome: (body: IncomeRequest) => apiFetch<IncomeReceiptView>("/api/accounts/income", { method: "POST", body }),
  cancelIncome: (receiptId: string, reason: string) =>
    apiFetch<IncomeReceiptView>(`/api/accounts/income/${id(receiptId)}/cancel`, { method: "POST", body: { reason } }),

  budgets: (financialYear: string) => apiFetch<BudgetsView>(`/api/accounts/budgets/${id(financialYear)}`),
  saveBudget: (financialYear: string, body: BudgetRequest) =>
    apiFetch<BudgetsView>(`/api/accounts/budgets/${id(financialYear)}`, { method: "PUT", body }),
  deleteBudget: (financialYear: string, budgetId: string) =>
    apiFetch<BudgetsView>(`/api/accounts/budgets/${id(financialYear)}/${id(budgetId)}`, { method: "DELETE" }),

  feeReconciliation: (p: Period) => apiFetch<FeeReconciliation>(`/api/accounts/fee-reconciliation${q(p)}`),
  rebuildFeeIncome: (p: Period) =>
    apiFetch<FeeReconciliation>(`/api/accounts/fee-reconciliation/rebuild${q(p)}`, { method: "POST" }),
  payrollCosts: (p: YearParams) => apiFetch<PayrollCostView[]>(`/api/accounts/payroll-costs${q(p)}`),

  incomeVsExpense: (p: YearParams) => apiFetch<IncomeVsExpense>(`/api/accounts/reports/income-vs-expense${q(p)}`),
  receiptsAndPayments: (p: Period) =>
    apiFetch<ReceiptsAndPayments>(`/api/accounts/reports/receipts-and-payments${q(p)}`),
  budgetVsActual: (p: YearParams) => apiFetch<BudgetsView>(`/api/accounts/reports/budget-vs-actual${q(p)}`),
  headWise: (p: Period) => apiFetch<HeadWiseExpense>(`/api/accounts/reports/head-wise${q(p)}`),
  gstSummary: (p: Period) => apiFetch<GstSummary>(`/api/accounts/reports/gst-summary${q(p)}`),
  dayBook: (date: string) => apiFetch<DayBook>(`/api/accounts/reports/day-book${q({ date })}`),
};
