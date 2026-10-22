# Phase 2 API: expenses and accounts

Same conventions as [Phase 0](phase-0.md) and [Phase 1](phase-1.md): JSON under `/api`, a Bearer access token,
RFC 9457 problem details with `errors.<field>`, ids from another school in a path answer `404` and in a body answer
`400` on that field, conflicts answer `409`, dates are `"YYYY-MM-DD"` days in India (Asia/Kolkata), times are
instants (ISO-8601), and every change is written to the audit trail. As in payroll: **money is in paise** (`long`),
months are `"YYYY-MM"`, and financial years are Indian, `"2026-27"` (1 April 2026 to 31 March 2027).

**These are the school's management accounts, not a statutory double-entry ledger.** They answer "what did we take
in, what did we spend, what is in the bank and the cash box, and are we within budget" for the people running the
school. There are no journals, no debits and credits, no trial balance and no year-end closing. The CSV exports are
for the school's accountant, who keeps the statutory books; the TDS and GST figures are data the school enters and
must verify with them.

**In this slice:** a chart of heads (income and expense, one level deep, with the fee heads mirrored from the fees
module), cost centres, vendors (GSTIN, PAN, phone and bank stored masked, TDS section and rate as editable data),
expenses with an approval chain by amount, payment with a mode and reference, bills attached through the files
module, recurring expenses that become monthly drafts, a petty cash book with a float, other income (donations with
80G receipt numbers, grants, rentals, miscellaneous) with printable receipts, fee income per day from the fees
module's `FeePaymentReceived` event with a reconciliation screen, payroll cost per month from `PayrollApproved`,
yearly budgets per head with a 90% warning and a warn-or-block switch, bank accounts with opening balances per
financial year, cash and bank books, reconciliation (tick cleared, or import a statement CSV with a preview), the
cash position, eight reports with CSV downloads, a finance card on the School Admin and Principal dashboards, and
demo data. Migration `V21__accounts.sql`; module `com.akshara.accounts`.

**Not in this slice:** double-entry posting, a general ledger or trial balance, balance sheet and income statement,
depreciation and fixed assets, GST returns or TDS returns (the summaries are for the accountant), bank feeds (the
statement is a CSV the school downloads from net banking), multi-currency, and writing anything to the fees or
payroll tables (the accounts only listen to their events).

## Permissions

| Permission | Who has it (seeded roles) | Allows |
| --- | --- | --- |
| `accounts.read` (new) | School Admin, Principal, Accountant | everything under `/api/accounts` and the reports, read-only |
| `accounts.manage` (new) | School Admin, Accountant | settings and approvers, heads, cost centres, vendors, bank accounts and opening balances, recording and editing expenses, submitting, marking paid, attachments, recurring expenses, petty cash, income receipts and cancellations, budgets, clearing and importing bank statements, rebuilding fee income |
| `accounts.approve` (new) | School Admin, Principal | the approval queue, approving (or passing on) and rejecting submitted expenses; also lets a principal without `accounts.read` see settings, heads, vendors, bank accounts, budgets, the cash position and each expense |

`V21__accounts.sql` appends the codes to the School Admin, Principal and Accountant roles of existing schools
(idempotent); new schools get them from `RoleCatalog`. Approval limits are per approver (see Settings).

## Settings and approvers — `/api/accounts/settings`

| Method | Path | Permission | Success |
| --- | --- | --- | --- |
| GET | `/settings` | read or approve | `200 SettingsView` (defaults with `saved: false` until first saved) |
| PUT | `/settings` | `accounts.manage` | `200 SettingsView` |
| PUT | `/settings/approvers` | `accounts.manage` | `200 SettingsView`: the whole list replaces the previous one |

```ts
type BudgetMode = "WARN" | "BLOCK";
type SettingsForm = { budgetMode: BudgetMode; donation80gRegistered: boolean;
  donation80gNumber: string | null;                       // ≤ 60, required when registered
  pettyCashFloatPaise: number };                          // ≥ 0
type ApproversForm = { approvers: { userId: string; limitPaise: number | null }[] };   // ≤ 20, null = no limit
type ApproverView = { userId: string; name: string; limitPaise: number | null;
  canApprove: boolean };                                  // false once the person lost accounts.approve
type SettingsView = SettingsForm & { saved: boolean; updatedAt: string | null; approvers: ApproverView[];
  candidates: { id: string; name: string }[] };           // everyone who holds accounts.approve
```

An approver who is not in the list has no limit. An approver in the list with `limitPaise: null` has no limit
either. A `userId` without `accounts.approve` is `400 errors["approvers[i].userId"]`.

## Chart of heads and cost centres — `/api/accounts/heads`, `/api/accounts/cost-centres`

| Method | Path | Permission | Success |
| --- | --- | --- | --- |
| GET | `/heads` | read or approve | `200 HeadView[]` by kind, parent and order |
| POST | `/heads` | `accounts.manage` | `201 HeadView` |
| POST | `/heads/standard` | `accounts.manage` | `200 HeadView[]`: adds the standard heads the school lacks, and an income sub-head under Fee income for every fee head of the fees module |
| PUT | `/heads/{id}` | `accounts.manage` | `200 HeadView` (kind never changes, `400 errors.kind`; a system head keeps its code) |
| GET | `/cost-centres` | read or approve | `200 CostCentreView[]` |
| POST | `/cost-centres` | `accounts.manage` | `201 CostCentreView` |
| PUT | `/cost-centres/{id}` | `accounts.manage` | `200 CostCentreView` |
| DELETE | `/cost-centres/{id}` | `accounts.manage` | `204`; `409` once an expense uses it (make it inactive instead) |

```ts
type HeadKind = "INCOME" | "EXPENSE";
type HeadForm = { code: string;                           // ^[A-Za-z][A-Za-z0-9-]{1,19}$, stored upper case
  name: string; kind: HeadKind; parentId: string | null;  // one level: a parent has no parent
  gstApplicable: boolean; active: boolean; displayOrder: number };   // 0–999
type HeadView = HeadForm & { id: string; parentName: string | null;
  feeHeadId: string | null;                               // mirrored from the fees module; read-only
  systemCode: string | null;                              // "FEES", "ELECTRICITY" … for the standard heads
  inUse: boolean };
type CostCentreForm = { code: string; name: string; active: boolean };
type CostCentreView = CostCentreForm & { id: string; inUse: boolean };
```

Standard heads: income `INC-FEES` Fee income, `INC-DON` Donations, `INC-GRANT` Grants, `INC-TRANS` Transport
income, `INC-HOSTEL` Hostel income, `INC-RENT` Rentals, `INC-OTHER` Other income; expense `EXP-SAL` Salaries and
wages, `EXP-ELEC` Electricity, `EXP-WATER` Water, `EXP-REPAIR` Repairs and maintenance, `EXP-STAT` Stationery and
printing, `EXP-FUEL` Transport fuel, `EXP-EVENT` Events and functions, `EXP-RENT` Rent, `EXP-TAX` Taxes and fees,
`EXP-PETTY` Petty expenses, `EXP-OTHER` Other expenses. A duplicate code or name is `409`. An inactive head is
refused on new expenses (`400 errors.headId`).

## Vendors — `/api/accounts/vendors`

| Method | Path | Permission | Success |
| --- | --- | --- | --- |
| GET | `/vendors` | read or approve | `200 VendorView[]` |
| GET | `/vendors/{id}` | read or approve | `200 VendorView` |
| GET | `/vendors/{id}/ledger?from&to` | `accounts.read` | `200 VendorLedger` |
| POST | `/vendors` | `accounts.manage` | `201 VendorView` |
| PUT | `/vendors/{id}` | `accounts.manage` | `200 VendorView`; a blank PAN, phone or account number keeps the stored one |
| DELETE | `/vendors/{id}` | `accounts.manage` | `204`; `409` once an expense names it |

```ts
type TdsSection = "194C" | "194J" | "194H" | "194I" | "194Q";
type VendorForm = { name: string; gstin: string | null;   // 15 characters, checked against the GSTIN pattern
  pan: string | null; phone: string | null;               // 8–15 digits
  email: string | null; address: string | null;
  bankAccountNumber: string | null; bankIfsc: string | null;   // together or neither
  tdsSection: TdsSection | null; tdsRatePercent: number | null;   // 0–30, both or neither
  active: boolean };
type VendorView = { id: string; name: string; gstin: string | null; panMasked: string | null;   // "AABXXXXX3R"
  phoneMasked: string | null; email: string | null; address: string | null;
  bankAccountMasked: string | null; bankIfsc: string | null;
  tdsSection: TdsSection | null; tdsRatePercent: number | null; active: boolean; inUse: boolean };
type VendorLedger = { vendor: VendorView; from: string; to: string;
  rows: { expenseId: string; number: string | null; date: string; description: string; status: ExpenseStatus;
    billPaise: number; gstPaise: number; tdsPaise: number; paidPaise: number; paidOn: string | null;
    mode: PayMode | null }[];
  billedPaise: number; gstPaise: number; tdsPaise: number; paidPaise: number; outstandingPaise: number };
```

PAN, phone and bank account are stored and always shown masked; there is no unmasked read. The TDS section and
rate are **data**: the screens pre-fill 194C 2% (1% for an individual or HUF), 194J 10% and say "verify with your
accountant". TDS is worked out on the amount before GST.

## Expenses — `/api/accounts/expenses`

| Method | Path | Permission | Success |
| --- | --- | --- | --- |
| GET | `/expenses?status&from&to` | read or approve | `200 ExpenseList` newest first, with counts per status |
| GET | `/expenses/queue` | `accounts.approve` | `200 ApprovalQueue`: submitted expenses, oldest first |
| GET | `/expenses/{id}` | read or approve | `200 ExpenseDetail` |
| POST | `/expenses?submit=false` | `accounts.manage` | `201 ExpenseDetail`: a draft, or submitted at once with `submit=true` |
| PUT | `/expenses/{id}` | `accounts.manage` | `200 ExpenseDetail` (drafts and rejected only) |
| POST | `/expenses/{id}/submit` | `accounts.manage` | `200 ExpenseDetail`, status `SUBMITTED`, number `EXP/2026-27/000001` |
| POST | `/expenses/{id}/approve` | `accounts.approve` | `200 ExpenseDetail`: `{ note? }`; approved, or passed on when above the approver's limit |
| POST | `/expenses/{id}/reject` | `accounts.approve` | `200 ExpenseDetail`: `{ reason }` (required) |
| POST | `/expenses/{id}/paid` | `accounts.manage` | `200 ExpenseDetail`, status `PAID`; locked from then on |
| DELETE | `/expenses/{id}` | `accounts.manage` | `204` (drafts and rejected only; removes the files) |
| POST | `/expenses/{id}/attachments` | `accounts.manage` | `200 ExpenseDetail`: multipart `file` (PDF or photo, 5 MB, at most 5 per expense; not once paid) |
| DELETE | `/expenses/{id}/attachments/{fileId}` | `accounts.manage` | `200 ExpenseDetail` (not once paid) |

```ts
type ExpenseStatus = "DRAFT" | "SUBMITTED" | "APPROVED" | "REJECTED" | "PAID";
type PayMode = "CASH" | "BANK" | "UPI" | "CHEQUE";
type ExpenseForm = { headId: string; costCentreId: string | null;
  vendorId: string | null; vendorName: string | null;     // a one-off payee when there is no vendor (≤ 120)
  expenseDate: string;                                    // not in the future
  amountPaise: number;                                    // before GST, > 0
  gstPaise: number;                                       // as on the bill; 0 unless the head takes GST, ≤ amount
  deductTds: boolean;                                     // needs a vendor with a TDS section
  description: string; billNumber: string | null };
type PaidForm = { paidOn: string;                         // on or after the expense date, not in the future
  mode: PayMode; bankAccountId: string | null;            // required unless CASH
  reference: string | null };                             // required for CHEQUE (the cheque number)
type ExpenseRow = { id: string; number: string | null; status: ExpenseStatus; expenseDate: string;
  headId: string; headCode: string; headName: string; costCentreId: string | null; costCentreName: string | null;
  vendorId: string | null; vendorName: string | null; amountPaise: number; gstPaise: number;
  totalPaise: number;                                     // amount + GST
  tdsPaise: number; paidPaise: number;                    // total − TDS kept back
  description: string; requestedByName: string; budgetWarning: boolean;
  escalatedAbovePaise: number | null;                     // the limit of the approver who last passed it on
  paidOn: string | null; paymentMode: PayMode | null; attachmentCount: number; submittedAt: string | null };
type ExpenseList = { expenses: ExpenseRow[]; draftCount: number; submittedCount: number; approvedCount: number;
  paidCount: number; rejectedCount: number };
type ApprovalQueue = { expenses: ExpenseRow[]; myLimitPaise: number | null; limited: boolean;
  actionable: string[] };                                 // the ids this approver may act on
type BudgetCheck = { budgetPaise: number; committedPaise: number; afterPaise: number; percentAfter: number;
  status: "NONE" | "OK" | "NEAR" | "OVER"; mode: BudgetMode };
type Decision = "APPROVE" | "ESCALATE" | "NOT_YOURS" | "NOBODY_ABOVE";
type ExpenseDetail = ExpenseRow & { financialYear: string | null; tdsSection: TdsSection | null;
  tdsRatePercent: number | null; billNumber: string | null; recurringId: string | null; createdAt: string;
  approvedByName: string | null; approvedAt: string | null; rejectedByName: string | null;
  rejectedAt: string | null; rejectReason: string | null; bankAccountId: string | null;
  bankAccountName: string | null; paymentReference: string | null; paidByName: string | null;
  paidAt: string | null; clearedOn: string | null; files: FileRef[];
  approvals: { id: string; action: "APPROVED" | "ESCALATED" | "REJECTED"; approverName: string;
    limitPaise: number | null; note: string | null; at: string }[];
  budget: BudgetCheck;
  myDecision: Decision | null;                            // for the signed-in approver while SUBMITTED
  canEdit: boolean; version: number };
```

**The approval chain.** An approver has a limit (or none). Approving an expense whose total is within the limit
approves it. Above the limit, the same call **passes it on** (`ESCALATED`): the expense stays `SUBMITTED` with
`escalatedAbovePaise` set to that approver's limit, and only approvers whose limit is above it (or who have none)
may act next. When nobody has a larger limit the approver sees `NOBODY_ABOVE` and the call is `409` until the
limits change. Anyone in the chain can reject, with a reason; a rejected expense can be edited and submitted again
(it keeps its number).

**Budgets at approval.** `budget` is the head's budget for the expense's financial year against what is already
committed (submitted, approved and paid expenses of the year, by expense date) plus this one. `NEAR` from 90%,
`OVER` above 100%. In `WARN` mode the approver sees the warning; in `BLOCK` mode approving an `OVER` expense is
`409` until the budget is raised or the mode changed. Rejecting is always allowed.

**Paid is locked.** Nothing on a `PAID` expense can change (`409`), including its attachments. Marking paid records
the mode, the bank account (so the bank book shows it) and the reference; a cash payment goes in the cash book.

## Recurring expenses — `/api/accounts/recurring`

| Method | Path | Permission | Success |
| --- | --- | --- | --- |
| GET | `/recurring` | `accounts.read` | `200 RecurringView[]` |
| POST | `/recurring` | `accounts.manage` | `201 RecurringView` |
| PUT | `/recurring/{id}` | `accounts.manage` | `200 RecurringView` |
| DELETE | `/recurring/{id}` | `accounts.manage` | `204` (drafts already created stay) |
| POST | `/recurring/generate` | `accounts.manage` | `200 { month, created, skipped, expenses: ExpenseRow[] }`: `{ month: "YYYY-MM" }` creates a **draft** per active item that has none for the month |

```ts
type RecurringForm = { name: string; headId: string; costCentreId: string | null; vendorId: string | null;
  amountPaise: number; gstPaise: number; dayOfMonth: number;   // 1–28
  description: string | null; active: boolean };
type RecurringView = RecurringForm & { id: string; headName: string; costCentreName: string | null;
  vendorName: string | null; lastGeneratedMonth: string | null };
```

Nothing is created on its own: an accountant presses "Create this month's drafts", checks them and sends each for
approval. A month in the future is `400 errors.month`.

## Petty cash — `/api/accounts/petty-cash`

| Method | Path | Permission | Success |
| --- | --- | --- | --- |
| GET | `/petty-cash?from&to` | `accounts.read` | `200 PettyCashBook` |
| POST | `/petty-cash` | `accounts.manage` | `201 PettyCashBook` (the book for the entry's month) |

```ts
type PettyCashForm = { entryDate: string; kind: "TOP_UP" | "SPENT"; amountPaise: number;
  headId: string | null;                                  // an expense head, required for SPENT
  description: string };
type PettyCashBook = { floatPaise: number; balancePaise: number;   // in the box today
  topUpNeededPaise: number;                               // float − balance, never negative
  from: string; to: string; openingPaise: number;
  entries: { id: string; date: string; kind: "TOP_UP" | "SPENT"; amountPaise: number; headId: string | null;
    headName: string | null; description: string; recordedByName: string; balanceAfterPaise: number }[];
  topUpsPaise: number; spentPaise: number };
```

A top-up moves cash from the cash book into the box; a spend is recorded against a head and counts as an expense of
that head in the reports and budgets, with no approval (petty cash is for small things; the float is the control).

## Other income — `/api/accounts/income`

| Method | Path | Permission | Success |
| --- | --- | --- | --- |
| GET | `/income?from&to` | `accounts.read` | `200 IncomeRow[]` newest first |
| GET | `/income/{id}` | `accounts.read` | `200 IncomeReceiptView` (with the school header for printing) |
| POST | `/income` | `accounts.manage` | `201 IncomeReceiptView`, receipt `INC/2026-27/000001` |
| POST | `/income/{id}/cancel` | `accounts.manage` | `200 IncomeReceiptView`: `{ reason }`; the number is not reused |

```ts
type IncomeKind = "DONATION" | "GRANT" | "RENTAL" | "MISC";
type IncomeForm = { kind: IncomeKind; headId: string;    // an income head that is not a fee head
  receivedOn: string; amountPaise: number; payerName: string;
  payerPan: string | null;                                // required for a DONATION when the school has 80G
  payerAddress: string | null; mode: PayMode; bankAccountId: string | null;   // required unless CASH
  reference: string | null; description: string | null };
type IncomeRow = { id: string; receiptNo: string; kind: IncomeKind; headId: string; headName: string;
  receivedOn: string; amountPaise: number; payerName: string; mode: PayMode; reference: string | null;
  status: "ISSUED" | "CANCELLED"; eightyGNumber: string | null; description: string | null };
type IncomeReceiptView = IncomeRow & { financialYear: string; amountInWords: string;   // "Rupees … Only"
  payerPanMasked: string | null; payerAddress: string | null; bankAccountId: string | null;
  bankAccountName: string | null; eightyGRegistration: string | null; cancelReason: string | null;
  cancelledByName: string | null; cancelledAt: string | null; receivedByName: string; createdAt: string;
  clearedOn: string | null;
  school: { name: string; address: string | null; phone: string | null; contactEmail: string | null;
    board: string | null; udiseCode: string | null } };
```

When the school is registered under section 80G, a donation receipt also gets `80G/2026-27/000001` and prints the
registration number and the 80G note. A cancelled receipt stays on record and counts for nothing.

## Fee income and payroll cost — `/api/accounts/fee-reconciliation`, `/api/accounts/payroll-costs`

| Method | Path | Permission | Success |
| --- | --- | --- | --- |
| GET | `/fee-reconciliation?from&to` | `accounts.read` | `200 FeeReconciliation` |
| POST | `/fee-reconciliation/rebuild?from&to` | `accounts.manage` | `200 FeeReconciliation`: replaces the period's days with the fees module's figures |
| GET | `/payroll-costs?financialYear` | `accounts.read` | `200 PayrollCostView[]` by month |

```ts
type FeeReconciliation = { from: string; to: string;
  days: { day: string; accountsPaise: number; accountsCount: number; feesPaise: number; feesCount: number;
    differencePaise: number }[];
  accountsPaise: number; feesPaise: number; differencePaise: number; daysWithDifferences: number };
type PayrollCostView = { month: string; runId: string; staffCount: number; grossPaise: number;
  employerPaise: number; costPaise: number;               // gross + employer contributions
  netPaise: number; approvedAt: string };
```

Fee income is **not entered**: every `FeePaymentReceived` adds to that day's `fee_income_day` row (amount, count, and
the cash part). The reconciliation compares those rows with `FeeInsights.collectedPerDay` from the fees module and
flags any day that differs (a missed event, a refund recorded in fees); rebuilding copies the fees figures over.
Payroll cost is recorded once per `PayrollApproved` (a run approved twice is not counted twice). Neither listener
writes to the fees or payroll tables.

## Budgets — `/api/accounts/budgets`

| Method | Path | Permission | Success |
| --- | --- | --- | --- |
| GET | `/budgets/{financialYear}` | read or approve | `200 BudgetsView`: every active head, with or without a budget |
| PUT | `/budgets/{financialYear}` | `accounts.manage` | `200 BudgetsView`: sets (or replaces) one head's budget |
| DELETE | `/budgets/{financialYear}/{budgetId}` | `accounts.manage` | `200 BudgetsView` |

```ts
type BudgetForm = { headId: string; annualPaise: number; spread: "EVEN" | "ENTERED";
  months: number[] | null };                              // 12 amounts (April first) adding up to the year, for ENTERED
type BudgetRow = { headId: string; headCode: string; headName: string; kind: HeadKind; budgetId: string | null;
  annualPaise: number; spread: "EVEN" | "ENTERED"; months: number[]; actualPaise: number; actualMonths: number[];
  percentUsed: number; status: "NONE" | "OK" | "NEAR" | "OVER" };
type BudgetsView = { financialYear: string; mode: BudgetMode; rows: BudgetRow[]; budgetPaise: number;
  actualPaise: number; percentUsed: number; nearCount: number; overCount: number };
```

Actuals per head: expenses that are submitted, approved or paid (by expense date) plus petty cash spends for expense
heads; income receipts, and fee income for the Fee income head, for income heads. `NEAR` from 90% of the year.

## Bank and cash — `/api/accounts/bank-accounts`, `/api/accounts/opening-balances`, `/api/accounts/books`

| Method | Path | Permission | Success |
| --- | --- | --- | --- |
| GET | `/bank-accounts` | read or approve | `200 BankAccountView[]` with today's book balance |
| POST | `/bank-accounts` | `accounts.manage` | `201 BankAccountView` |
| PUT | `/bank-accounts/{id}` | `accounts.manage` | `200 BankAccountView` (a blank number keeps the stored one) |
| DELETE | `/bank-accounts/{id}` | `accounts.manage` | `204`; `409` once anything is booked on it |
| GET | `/opening-balances/{financialYear}` | `accounts.read` | `200 OpeningBalancesView`: cash and each account on 1 April |
| PUT | `/opening-balances/{financialYear}` | `accounts.manage` | `200 OpeningBalancesView`: `{ balances: { bankAccountId: string | null; amountPaise }[] }` (`null` = cash) |
| GET | `/books?bankAccountId&from&to` | `accounts.read` | `200 Book`: the cash book, or the account's bank book |
| GET | `/cash-position` | read or approve | `200 CashPosition` as of today |
| GET | `/bank-accounts/{id}/reconciliation?statementDate` | `accounts.read` | `200 Reconciliation` |
| POST | `/bank-accounts/{id}/clear` | `accounts.manage` | `200 { cleared }`: `{ statementDate, expenseIds, incomeIds }` ticks them cleared on that date |
| POST | `/bank-accounts/{id}/statement/preview` | `accounts.manage` | `200 StatementPreview`: multipart `file` (CSV ≤ 2 MB, ≤ 2,000 lines); nothing is saved |
| POST | `/bank-accounts/{id}/statement/import` | `accounts.manage` | `200 { imported, cleared, skipped }` |

```ts
type BankAccountForm = { name: string; bankName: string; accountNumber: string;   // 6–18 digits
  ifsc: string; active: boolean };
type BankAccountView = { id: string; name: string; bankName: string; accountNumberMasked: string;   // "XXXXXXXX5678"
  ifsc: string; active: boolean; inUse: boolean; balancePaise: number; unclearedCount: number };
type OpeningBalancesView = { financialYear: string; cashPaise: number;
  accounts: { bankAccountId: string; name: string; amountPaise: number }[] };
type Book = { bankAccountId: string | null; name: string; from: string; to: string; openingPaise: number;
  rows: { date: string; kind: "EXPENSE" | "INCOME" | "FEES" | "PETTY_TOP_UP" | "OPENING"; refId: string;
    particulars: string; reference: string | null; receiptPaise: number; paymentPaise: number;
    balancePaise: number; cleared: boolean }[];
  receiptsPaise: number; paymentsPaise: number; closingPaise: number };
type CashPosition = { asOf: string; cashPaise: number; pettyCashPaise: number;
  banks: { id: string; name: string; balancePaise: number }[]; bankPaise: number; totalPaise: number };
type Reconciliation = { bankAccountId: string; name: string; statementDate: string; bookBalancePaise: number;
  unclearedPayments: UnclearedItem[]; unclearedReceipts: UnclearedItem[]; unclearedPaymentsPaise: number;
  unclearedReceiptsPaise: number;
  expectedStatementBalancePaise: number;                  // book + uncleared payments − uncleared receipts
  recentLines: StatementLineView[] };
type UnclearedItem = { id: string; kind: "EXPENSE" | "INCOME"; date: string; label: string;
  reference: string | null; amountPaise: number };
type StatementPreview = { lines: { index: number; date: string | null; description: string; reference: string | null;
    debitPaise: number; creditPaise: number;
    match: { expenseId: string | null; incomeId: string | null; label: string;
      quality: "MATCHED" | "PROBABLE" | "NONE" } | null;
    duplicate: boolean;                                   // already imported (same date, amounts and reference)
    problem: string | null }[];                           // a line that could not be read, in words
  payments: CandidateView[]; receipts: CandidateView[];   // what the accountant may match by hand
  matched: number; probable: number; unmatched: number };
type StatementImportForm = { statementDate: string; lines: { date: string; description: string;
  reference: string | null; debitPaise: number; creditPaise: number;
  expenseId: string | null; incomeId: string | null }[] };   // the preview's lines with the final choices
```

The cash book is the opening balance of the financial year plus cash fee collections (from the fee income days),
cash income receipts, less cash expenses and petty cash top-ups. A bank book is the account's opening balance plus
income receipts booked on it less expenses paid from it. **Fee payments by bank, UPI or cheque are not assigned to a
bank account** (the fees module does not know the school's accounts), so they appear in the receipts and the income
reports but not in a bank book; the accountant sees them on the statement import as unmatched credits.

**Statement CSV.** The first row names the columns; recognised headings are `Date` (also `Txn date`, `Value date`),
`Description` (`Narration`, `Particulars`, `Remarks`), `Reference` (`Ref no`, `Cheque`, `UTR`, `Transaction id`),
`Debit`/`Withdrawal` and `Credit`/`Deposit`, or a single `Amount` with a `Dr/Cr` column (or a sign). Dates may be
`YYYY-MM-DD`, `DD/MM/YYYY`, `DD-MM-YYYY`, `DD/MM/YY`, `DD-MMM-YYYY` or `DD MMM YYYY`. A line is `MATCHED` when exactly one uncleared item has
the same amount on the same side and the line mentions its reference or cheque number, `PROBABLE` when the amount
alone finds exactly one, else `NONE`; each item is used once. Importing saves the lines and marks the chosen items
cleared on the statement date; duplicates and unreadable lines are skipped.

## Reports — `/api/accounts/reports` (all `accounts.read`)

| Method | Path | Success |
| --- | --- | --- |
| GET | `/income-vs-expense?financialYear` | `200 { financialYear, months: MonthLine[], totals: MonthLine }`: fee income, other income, bills (by expense date, submitted or later), payroll cost, petty cash spends, net, per month |
| GET | `/receipts-and-payments?from&to` | `200 ReceiptsAndPayments`: cash basis by head, with the opening and closing cash and bank |
| GET | `/budget-vs-actual?financialYear` | `200 BudgetsView` |
| GET | `/head-wise?from&to` | `200 HeadWiseExpense`: expenses by head with the cost centres under each |
| GET | `/vendor-ledger?vendorId&from&to` | `200 VendorLedger` |
| GET | `/books?bankAccountId&from&to` | `200 Book` |
| GET | `/gst-summary?from&to` | `200 GstSummary`: GST on bills by vendor and GSTIN, and the part from vendors without one |
| GET | `/day-book?date` | `200 DayBook`: everything received and paid on one day |
| GET | `…​.csv` | the same eight as CSV (UTF-8 with BOM, CRLF, `Content-Disposition` with the file name; cells starting with `=`, `+`, `-`, `@` are prefixed so spreadsheets do not run them) |

```ts
type MonthLine = { month: string; feeIncomePaise: number; otherIncomePaise: number; incomePaise: number;
  expensePaise: number; payrollPaise: number; pettyCashPaise: number; totalExpensePaise: number; netPaise: number };
type HeadLine = { headId: string | null; code: string | null; name: string; amountPaise: number; count: number };
type ReceiptsAndPayments = { from: string; to: string; openingPaise: number; receipts: HeadLine[];
  receiptsPaise: number; payments: HeadLine[]; paymentsPaise: number; closingPaise: number };
type HeadWiseExpense = { from: string; to: string; rows: { headId: string; code: string; name: string;
    amountPaise: number; gstPaise: number; count: number; costCentres: HeadLine[] }[];
  amountPaise: number; gstPaise: number; count: number };
type GstSummary = { from: string; to: string; rows: { vendorId: string | null; vendorName: string; gstin: string | null;
    bills: number; taxablePaise: number; gstPaise: number }[];
  taxablePaise: number; gstPaise: number; withoutGstinPaise: number };
type DayBook = { date: string; rows: { kind: "FEES" | "INCOME" | "EXPENSE" | "PETTY_TOP_UP" | "PETTY_SPENT";
    refId: string; number: string | null; particulars: string; mode: string | null; account: string | null;
    receiptPaise: number; paymentPaise: number }[];
  receiptsPaise: number; paymentsPaise: number };
```

A period is at most 400 days (`400 errors.to`); a financial year is `"YYYY-YY"` (`400`), the current one when
left out. The income vs expense report is on an accrual-ish basis for bills (expense date, once submitted) and
cash basis for the rest; receipts and payments is cash basis throughout (paid date for bills).

## Dashboard

`GET /api/dashboard` gains `accounts: FinanceCard | null` (null without `accounts.read`), shown as the finance card
on the School Admin's and Principal's dashboards:

```ts
type FinanceCard = { month: string; incomePaise: number; feeIncomePaise: number; otherIncomePaise: number;
  expensePaise: number;                                   // approved and paid bills + payroll cost + petty spends
  payrollPaise: number; cashInHandPaise: number;          // cash book + petty cash box
  bankPaise: number; awaitingApproval: number; awaitingApprovalPaise: number };
```

## Events consumed

`fees.FeePaymentReceived` → `fee_income_day` (per day: amount, count, cash part). `payroll.PayrollApproved` →
`payroll_cost` (per run: gross, employer contributions, net, staff count). Both listeners select the school from
the event before their transaction starts, so row-level security sees the right tenant. The accounts module
publishes no events of its own and reads the fees module only through `FeeInsights`, `FeeReportService` and
`FeeSetupService.heads()` (read-only, for the reconciliation and the mirrored fee heads).

## Audit actions

`accounts_settings.updated`, `accounts_approvers.updated`, `account_head.created`, `account_head.updated`,
`account_heads.standard_added`, `cost_centre.created`, `cost_centre.updated`, `cost_centre.deleted`,
`vendor.created`, `vendor.updated` (name, GSTIN and TDS data; never the PAN, phone or account number), `vendor.deleted`,
`bank_account.created`, `bank_account.updated`, `bank_account.deleted`, `opening_balances.saved`,
`bank_items.cleared`, `bank_statement.imported`, `expense.created`, `expense.updated`, `expense.submitted` (with
the budget status), `expense.deleted`, `expense.escalated`, `expense.approved`, `expense.rejected`, `expense.paid`,
`expense.file_added`, `expense.file_removed`, `recurring_expense.created`, `recurring_expense.updated`,
`recurring_expense.deleted`, `recurring_expenses.generated`, `petty_cash.recorded`, `income_receipt.issued`,
`income_receipt.cancelled`, `budget.saved`, `budget.deleted`, `fee_income.rebuilt`.

## Data and isolation

Schema `accounts`: `settings`, `approver`, `head`, `cost_centre`, `vendor`, `bank_account`, `opening_balance`,
`number_counter`, `recurring_expense`, `expense`, `expense_approval`, `petty_cash_entry`, `income_receipt`,
`fee_income_day`, `payroll_cost`, `budget`, `budget_month`, `bank_statement_line`. Every table carries `tenant_id`
with the `tenant_isolation` policy (`USING` and `WITH CHECK` on `platform.current_tenant_id()`), grants to the
runtime role only, and composite `(tenant_id, …)` foreign keys. Expense and receipt numbers come from
`number_counter` per school, kind and financial year (`EXP/2026-27/000001`, `INC/…`, `80G/…`), taken with a row
lock so two accountants never get the same number. Bills live in the files module under the expense as owner;
only people with `accounts.read` or `accounts.approve` may download them. Aadhaar is never stored; PAN, phone and
bank account numbers are stored and shown masked.

## Web

| Route | Permission | Screen |
| --- | --- | --- |
| `/app/accounts` | `accounts.read` | Overview: this month's income, expenses, net, cash and bank, the queue; fee income reconciliation (rebuild); payroll cost by month |
| `/app/accounts/expenses`, `/app/accounts/expenses/{id}` | `accounts.read` | Expenses by status; new expense (head, vendor, GST rate or amount, TDS, bills); one expense with its approval history, budget check, submit, approve or pass on, reject, mark paid, attachments |
| `/app/accounts/approvals` | `accounts.approve` | The approval queue with the approver's limit; Approve (or Pass on) and Reject on each row |
| `/app/accounts/income`, `/app/accounts/income/{id}` | `accounts.read` | Receipts of a period; new receipt; the printed receipt (fee receipt style), cancel |
| `/app/accounts/petty-cash` | `accounts.read` | Float, box balance, top-up needed; the book; top up and spend |
| `/app/accounts/budgets` | `accounts.read` | Budget vs actual per head for a year; set a budget (even or entered months) |
| `/app/accounts/bank` | `accounts.read` | Accounts (masked), cash position, opening balances per year; cash and bank books; reconciliation with tick-to-clear and statement CSV import with preview |
| `/app/accounts/vendors`, `/app/accounts/vendors/{id}` | `accounts.read` | Vendors (masked), new and edit with TDS defaults ("verify with your accountant"); a vendor's ledger |
| `/app/accounts/setup` | `accounts.read` | Heads (add standard), cost centres, recurring expenses (create this month's drafts), settings (budget mode, 80G, petty float) and approvers with limits |
| `/app/reports/accounts-*` | `accounts.read` | The eight reports in the hub's Accounts group, each with CSV and print |

Every accounts screen carries the note that these are management accounts, not the statutory books.
