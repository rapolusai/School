/**
 * Types mirroring docs/api/phase-2-accounts.md (expenses and accounts: the school's management accounts, not a
 * statutory ledger). Keep in sync with the contract. Money is in paise; years are Indian financial years "2026-27".
 */

import type { FileRef } from "./homework";
import type { PlainDate } from "./school";

export type HeadKind = "INCOME" | "EXPENSE";
export type ExpenseStatus = "DRAFT" | "SUBMITTED" | "APPROVED" | "REJECTED" | "PAID";
export const EXPENSE_STATUSES: readonly ExpenseStatus[] = ["DRAFT", "SUBMITTED", "APPROVED", "REJECTED", "PAID"];
export type PayMode = "CASH" | "BANK" | "UPI" | "CHEQUE";
export const PAY_MODES: readonly PayMode[] = ["CASH", "BANK", "UPI", "CHEQUE"];
export type IncomeKind = "DONATION" | "GRANT" | "RENTAL" | "MISC";
export const INCOME_KINDS: readonly IncomeKind[] = ["DONATION", "GRANT", "RENTAL", "MISC"];
export type PettyKind = "TOP_UP" | "SPENT";
export type BudgetMode = "WARN" | "BLOCK";
export type BudgetSpread = "EVEN" | "ENTERED";
export type ApprovalAction = "APPROVED" | "ESCALATED" | "REJECTED";
export type BudgetStatus = "NONE" | "OK" | "NEAR" | "OVER";
/** What the signed-in approver may do with a submitted expense. */
export type Decision = "APPROVE" | "ESCALATE" | "NOT_YOURS" | "NOBODY_ABOVE";
export const TDS_SECTIONS = ["194C", "194J", "194H", "194I", "194Q"] as const;
export type TdsSection = (typeof TDS_SECTIONS)[number];

/* ------------------------------------------------------------------ settings and chart */

export type PersonRef = { id: string; name: string };
export type ApproverView = { userId: string; name: string; limitPaise: number | null; canApprove: boolean };

export type AccountsSettingsView = {
  budgetMode: BudgetMode;
  donation80gRegistered: boolean;
  donation80gNumber: string | null;
  pettyCashFloatPaise: number;
  saved: boolean;
  updatedAt: string | null;
  approvers: ApproverView[];
  /** everyone who could be an approver (holds accounts.approve) */
  candidates: PersonRef[];
};

export type AccountsSettingsRequest = {
  budgetMode: BudgetMode;
  donation80gRegistered: boolean;
  donation80gNumber: string | null;
  pettyCashFloatPaise: number;
};

export type ApproversRequest = { approvers: { userId: string; limitPaise: number | null }[] };

export type HeadView = {
  id: string;
  code: string;
  name: string;
  kind: HeadKind;
  parentId: string | null;
  parentName: string | null;
  gstApplicable: boolean;
  feeHeadId: string | null;
  systemCode: string | null;
  active: boolean;
  displayOrder: number;
  inUse: boolean;
};

export type HeadRequest = {
  code: string;
  name: string;
  kind: HeadKind;
  parentId: string | null;
  gstApplicable: boolean;
  active: boolean;
  displayOrder: number;
};

export type CostCentreView = { id: string; code: string; name: string; active: boolean; inUse: boolean };
export type CostCentreRequest = { code: string; name: string; active: boolean };

/* ------------------------------------------------------------------ vendors */

export type VendorView = {
  id: string;
  name: string;
  gstin: string | null;
  panMasked: string | null;
  phoneMasked: string | null;
  email: string | null;
  address: string | null;
  bankAccountMasked: string | null;
  bankIfsc: string | null;
  tdsSection: TdsSection | null;
  tdsRatePercent: number | null;
  active: boolean;
  inUse: boolean;
};

export type VendorRequest = {
  name: string;
  gstin: string | null;
  pan: string | null;
  phone: string | null;
  email: string | null;
  address: string | null;
  bankAccountNumber: string | null;
  bankIfsc: string | null;
  tdsSection: TdsSection | null;
  tdsRatePercent: number | null;
  active: boolean;
};

export type VendorLedgerRow = {
  expenseId: string;
  number: string | null;
  date: PlainDate;
  description: string;
  status: ExpenseStatus;
  billPaise: number;
  gstPaise: number;
  tdsPaise: number;
  paidPaise: number;
  paidOn: PlainDate | null;
  mode: PayMode | null;
};

export type VendorLedger = {
  vendor: VendorView;
  from: PlainDate;
  to: PlainDate;
  rows: VendorLedgerRow[];
  billedPaise: number;
  gstPaise: number;
  tdsPaise: number;
  paidPaise: number;
  outstandingPaise: number;
};

/* ------------------------------------------------------------------ bank and cash */

export type BankAccountView = {
  id: string;
  name: string;
  bankName: string;
  accountNumberMasked: string;
  ifsc: string;
  active: boolean;
  inUse: boolean;
  balancePaise: number;
  unclearedCount: number;
};

export type BankAccountRequest = { name: string; bankName: string; accountNumber: string; ifsc: string; active: boolean };

export type OpeningBalancesView = {
  financialYear: string;
  cashPaise: number;
  accounts: { bankAccountId: string; name: string; amountPaise: number }[];
};

export type OpeningBalancesRequest = { balances: { bankAccountId: string | null; amountPaise: number }[] };

export type BookRowKind = "EXPENSE" | "INCOME" | "FEES" | "PETTY_TOP_UP" | "OPENING";

export type BookRow = {
  date: PlainDate;
  kind: BookRowKind;
  refId: string;
  particulars: string;
  reference: string | null;
  receiptPaise: number;
  paymentPaise: number;
  balancePaise: number;
  cleared: boolean;
};

export type Book = {
  bankAccountId: string | null;
  name: string;
  from: PlainDate;
  to: PlainDate;
  openingPaise: number;
  rows: BookRow[];
  receiptsPaise: number;
  paymentsPaise: number;
  closingPaise: number;
};

export type CashPosition = {
  asOf: PlainDate;
  cashPaise: number;
  pettyCashPaise: number;
  banks: { id: string; name: string; balancePaise: number }[];
  bankPaise: number;
  totalPaise: number;
};

export type UnclearedItem = {
  id: string;
  kind: "EXPENSE" | "INCOME";
  date: PlainDate;
  label: string;
  reference: string | null;
  amountPaise: number;
};

export type StatementLineView = {
  id: string;
  statementDate: PlainDate;
  date: PlainDate;
  description: string;
  reference: string | null;
  debitPaise: number;
  creditPaise: number;
  matchedExpenseId: string | null;
  matchedIncomeId: string | null;
  matchedLabel: string | null;
};

export type Reconciliation = {
  bankAccountId: string;
  name: string;
  statementDate: PlainDate;
  bookBalancePaise: number;
  unclearedPayments: UnclearedItem[];
  unclearedReceipts: UnclearedItem[];
  unclearedPaymentsPaise: number;
  unclearedReceiptsPaise: number;
  expectedStatementBalancePaise: number;
  recentLines: StatementLineView[];
};

export type ClearRequest = { statementDate: PlainDate; expenseIds: string[]; incomeIds: string[] };

export type MatchQuality = "MATCHED" | "PROBABLE" | "NONE";

export type MatchView = {
  expenseId: string | null;
  incomeId: string | null;
  label: string;
  quality: MatchQuality;
};

export type PreviewLine = {
  index: number;
  date: PlainDate | null;
  description: string;
  reference: string | null;
  debitPaise: number;
  creditPaise: number;
  match: MatchView | null;
  duplicate: boolean;
  problem: string | null;
};

export type CandidateView = {
  id: string;
  kind: "EXPENSE" | "INCOME";
  date: PlainDate;
  label: string;
  reference: string | null;
  amountPaise: number;
};

export type StatementPreview = {
  lines: PreviewLine[];
  payments: CandidateView[];
  receipts: CandidateView[];
  matched: number;
  probable: number;
  unmatched: number;
};

export type StatementLineRequest = {
  date: PlainDate;
  description: string;
  reference: string | null;
  debitPaise: number;
  creditPaise: number;
  expenseId: string | null;
  incomeId: string | null;
};

export type StatementImportRequest = { statementDate: PlainDate; lines: StatementLineRequest[] };
export type StatementImportResult = { imported: number; cleared: number; skipped: number };

/* ------------------------------------------------------------------ expenses */

export type ApprovalView = {
  id: string;
  action: ApprovalAction;
  approverName: string;
  limitPaise: number | null;
  note: string | null;
  at: string;
};

export type BudgetCheck = {
  budgetPaise: number;
  committedPaise: number;
  afterPaise: number;
  percentAfter: number;
  status: BudgetStatus;
  mode: BudgetMode;
};

export type ExpenseRow = {
  id: string;
  number: string | null;
  status: ExpenseStatus;
  expenseDate: PlainDate;
  headId: string;
  headCode: string;
  headName: string;
  costCentreId: string | null;
  costCentreName: string | null;
  vendorId: string | null;
  vendorName: string | null;
  amountPaise: number;
  gstPaise: number;
  totalPaise: number;
  tdsPaise: number;
  paidPaise: number;
  description: string;
  requestedByName: string;
  budgetWarning: boolean;
  escalatedAbovePaise: number | null;
  paidOn: PlainDate | null;
  paymentMode: PayMode | null;
  attachmentCount: number;
  submittedAt: string | null;
};

export type ExpenseList = {
  expenses: ExpenseRow[];
  draftCount: number;
  submittedCount: number;
  approvedCount: number;
  paidCount: number;
  rejectedCount: number;
};

export type ApprovalQueue = {
  expenses: ExpenseRow[];
  myLimitPaise: number | null;
  limited: boolean;
  /** the ids the signed-in approver can act on */
  actionable: string[];
};

export type ExpenseDetail = ExpenseRow & {
  financialYear: string | null;
  tdsSection: TdsSection | null;
  tdsRatePercent: number | null;
  billNumber: string | null;
  recurringId: string | null;
  createdAt: string;
  approvedByName: string | null;
  approvedAt: string | null;
  rejectedByName: string | null;
  rejectedAt: string | null;
  rejectReason: string | null;
  bankAccountId: string | null;
  bankAccountName: string | null;
  paymentReference: string | null;
  paidByName: string | null;
  paidAt: string | null;
  clearedOn: PlainDate | null;
  files: FileRef[];
  approvals: ApprovalView[];
  budget: BudgetCheck;
  myDecision: Decision | null;
  canEdit: boolean;
  version: number;
};

export type ExpenseRequest = {
  headId: string;
  costCentreId: string | null;
  vendorId: string | null;
  vendorName: string | null;
  expenseDate: PlainDate;
  amountPaise: number;
  gstPaise: number;
  deductTds: boolean;
  description: string;
  billNumber: string | null;
};

export type ExpensePaidRequest = { paidOn: PlainDate; mode: PayMode; bankAccountId: string | null; reference: string | null };

export type RecurringView = {
  id: string;
  name: string;
  headId: string;
  headName: string;
  costCentreId: string | null;
  costCentreName: string | null;
  vendorId: string | null;
  vendorName: string | null;
  amountPaise: number;
  gstPaise: number;
  dayOfMonth: number;
  description: string | null;
  active: boolean;
  lastGeneratedMonth: PlainDate | null;
};

export type RecurringRequest = {
  name: string;
  headId: string;
  costCentreId: string | null;
  vendorId: string | null;
  amountPaise: number;
  gstPaise: number;
  dayOfMonth: number;
  description: string | null;
  active: boolean;
};

export type GenerateResult = { month: string; created: number; skipped: number; expenses: ExpenseRow[] };

/* ------------------------------------------------------------------ petty cash and income */

export type PettyRow = {
  id: string;
  date: PlainDate;
  kind: PettyKind;
  amountPaise: number;
  headId: string | null;
  headName: string | null;
  description: string;
  recordedByName: string;
  balanceAfterPaise: number;
};

export type PettyCashBook = {
  floatPaise: number;
  balancePaise: number;
  topUpNeededPaise: number;
  from: PlainDate;
  to: PlainDate;
  openingPaise: number;
  entries: PettyRow[];
  topUpsPaise: number;
  spentPaise: number;
};

export type PettyCashRequest = {
  entryDate: PlainDate;
  kind: PettyKind;
  amountPaise: number;
  headId: string | null;
  description: string;
};

export type IncomeRow = {
  id: string;
  receiptNo: string;
  kind: IncomeKind;
  headId: string;
  headName: string;
  receivedOn: PlainDate;
  amountPaise: number;
  payerName: string;
  mode: PayMode;
  reference: string | null;
  status: "ISSUED" | "CANCELLED";
  eightyGNumber: string | null;
  description: string | null;
};

export type SchoolHeader = {
  name: string;
  address: string | null;
  phone: string | null;
  contactEmail: string | null;
  board: string | null;
  udiseCode: string | null;
};

export type IncomeReceiptView = IncomeRow & {
  financialYear: string;
  amountInWords: string;
  payerPanMasked: string | null;
  payerAddress: string | null;
  bankAccountId: string | null;
  bankAccountName: string | null;
  eightyGRegistration: string | null;
  cancelReason: string | null;
  cancelledByName: string | null;
  cancelledAt: string | null;
  receivedByName: string;
  createdAt: string;
  clearedOn: PlainDate | null;
  school: SchoolHeader;
};

export type IncomeRequest = {
  kind: IncomeKind;
  headId: string;
  receivedOn: PlainDate;
  amountPaise: number;
  payerName: string;
  payerPan: string | null;
  payerAddress: string | null;
  mode: PayMode;
  bankAccountId: string | null;
  reference: string | null;
  description: string | null;
};

/* ------------------------------------------------------------------ budgets and the other modules */

export type BudgetRow = {
  headId: string;
  headCode: string;
  headName: string;
  kind: HeadKind;
  budgetId: string | null;
  annualPaise: number;
  spread: BudgetSpread;
  months: number[];
  actualPaise: number;
  actualMonths: number[];
  percentUsed: number;
  status: BudgetStatus;
};

export type BudgetsView = {
  financialYear: string;
  mode: BudgetMode;
  rows: BudgetRow[];
  budgetPaise: number;
  actualPaise: number;
  percentUsed: number;
  nearCount: number;
  overCount: number;
};

export type BudgetRequest = { headId: string; annualPaise: number; spread: BudgetSpread; months: number[] | null };

export type FeeDay = {
  day: PlainDate;
  accountsPaise: number;
  accountsCount: number;
  feesPaise: number;
  feesCount: number;
  differencePaise: number;
};

export type FeeReconciliation = {
  from: PlainDate;
  to: PlainDate;
  days: FeeDay[];
  accountsPaise: number;
  feesPaise: number;
  differencePaise: number;
  daysWithDifferences: number;
};

export type PayrollCostView = {
  month: string;
  runId: string;
  staffCount: number;
  grossPaise: number;
  employerPaise: number;
  costPaise: number;
  netPaise: number;
  approvedAt: string;
};

/* ------------------------------------------------------------------ reports */

export type MonthLine = {
  month: string;
  feeIncomePaise: number;
  otherIncomePaise: number;
  incomePaise: number;
  expensePaise: number;
  payrollPaise: number;
  pettyCashPaise: number;
  totalExpensePaise: number;
  netPaise: number;
};

export type IncomeVsExpense = { financialYear: string; months: MonthLine[]; totals: MonthLine };

export type HeadLine = { headId: string | null; code: string | null; name: string; amountPaise: number; count: number };

export type ReceiptsAndPayments = {
  from: PlainDate;
  to: PlainDate;
  openingPaise: number;
  receipts: HeadLine[];
  receiptsPaise: number;
  payments: HeadLine[];
  paymentsPaise: number;
  closingPaise: number;
};

export type HeadWiseRow = {
  headId: string;
  code: string;
  name: string;
  amountPaise: number;
  gstPaise: number;
  count: number;
  costCentres: HeadLine[];
};

export type HeadWiseExpense = {
  from: PlainDate;
  to: PlainDate;
  rows: HeadWiseRow[];
  amountPaise: number;
  gstPaise: number;
  count: number;
};

export type GstRow = {
  vendorId: string | null;
  vendorName: string;
  gstin: string | null;
  bills: number;
  taxablePaise: number;
  gstPaise: number;
};

export type GstSummary = {
  from: PlainDate;
  to: PlainDate;
  rows: GstRow[];
  taxablePaise: number;
  gstPaise: number;
  withoutGstinPaise: number;
};

export type DayBookRow = {
  kind: "FEES" | "INCOME" | "EXPENSE" | "PETTY_TOP_UP" | "PETTY_SPENT";
  refId: string;
  number: string | null;
  particulars: string;
  mode: string | null;
  account: string | null;
  receiptPaise: number;
  paymentPaise: number;
};

export type DayBook = { date: PlainDate; rows: DayBookRow[]; receiptsPaise: number; paymentsPaise: number };

/** This month's finance figures on the School Admin's and Principal's dashboards (accounts.read). */
export type FinanceCard = {
  month: string;
  incomePaise: number;
  feeIncomePaise: number;
  otherIncomePaise: number;
  expensePaise: number;
  payrollPaise: number;
  cashInHandPaise: number;
  bankPaise: number;
  awaitingApproval: number;
  awaitingApprovalPaise: number;
};
