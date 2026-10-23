# Phase 2 API: payroll

Same conventions as [Phase 0](phase-0.md) and [Phase 1](phase-1.md): JSON under `/api`, a Bearer access token,
RFC 9457 problem details with `errors.<field>`, ids from another school in a path answer `404` and in a body answer
`400` on that field, conflicts answer `409`, dates are `"YYYY-MM-DD"` days in India (Asia/Kolkata), times are
instants (ISO-8601), and every change is written to the audit trail. Payroll adds: **money is in paise** (`long`),
**months are `"YYYY-MM"`**, and financial years are `"2026-27"` (April 2026 to March 2027).

**In this slice:** payroll settings (pay day, proration basis, PF/ESI/professional tax switches, registration
numbers, the school's own bank account), statutory reference data (PF/ESI rates, income tax rules per financial
year, professional tax slabs per state, with a school's own slabs on top), salary components, salary structures per
person with revisions from a month on, pay details (bank account, IFSC, PAN, UAN, ESI number; never Aadhaar), monthly
payroll runs (draft, recalculate, adjustments, TDS override, approve and lock, mark paid), payslips (the person's own
under "My payslips"), the bank transfer file and bank advice letter, the EPFO ECR file, the ESI contribution file,
professional tax and TDS summaries, three reports (register, year to date, statutory summary) with CSV downloads, and
the `PayrollApproved` event. Migration `V14__payroll.sql`; module `com.akshara.payroll`.

**Not in this slice:** the old tax regime (only the new regime, section 115BAC, is calculated: a payroll manager
overrides a month's TDS by hand for anyone on the old regime), Form 16 and quarterly TDS returns (24Q), investment
declarations, filing or uploading anything (every file is a download for a person to upload), sending money,
reimbursements and loans as a ledger (a recovery is a fixed deduction component), and posting to accounts (the
`PayrollApproved` event is there for a later accounts module).

## Permissions

| Permission | Who has it (seeded roles) | Allows |
| --- | --- | --- |
| `payroll.read` (new) | School Admin, Accountant | settings and statutory figures, components, the staff pay list and each person's structures and pay details **masked**, runs, payslips, PT/TDS summaries, the reports and their CSVs |
| `payroll.manage` (new) | School Admin, Accountant | changing settings, PT slabs, components, structures and pay details; seeing pay details in full (audited); creating, recalculating and deleting drafts; adjustments and TDS overrides; marking a month paid; the bank, ECR, ESI, PT and TDS files and the bank advice |
| `payroll.approve` (new) | School Admin | approving (locking) a month |
| any staff role | everyone whose sign-in has a role other than Parent and Student | `/api/me/payslips`: their own payslips of approved and paid months |

The Principal has no payroll permission. `V14__payroll.sql` appends the codes to the School Admin and Accountant
roles of existing schools (idempotent); new schools get them from `RoleCatalog`.

## Settings and statutory data — `/api/payroll/settings`

| Method | Path | Permission | Success |
| --- | --- | --- | --- |
| GET | `/settings` | `payroll.read` | `200 SettingsView` (defaults with `saved: false` until first saved) |
| PUT | `/settings` | `payroll.manage` | `200 SettingsView` |
| GET | `/settings/pt-slabs/{state}` | `payroll.read` | `200 PtSlabsView`: the school's own slabs for the state, else the published ones |
| PUT | `/settings/pt-slabs/{state}` | `payroll.manage` | `200 PtSlabsView`: the school's own slabs replace the published ones |
| DELETE | `/settings/pt-slabs/{state}` | `payroll.manage` | `200 PtSlabsView`: drops the school's own slabs |

```ts
type ProrationBasis = "CALENDAR_DAYS" | "WORKING_DAYS";   // WORKING_DAYS: Monday to Saturday less holidays
type SettingsForm = { payDay: number;                     // 1–31: paid on this day of the next month
  prorationBasis: ProrationBasis; pfEnabled: boolean;
  pfOnFullWages: boolean;                                 // false: PF on basic + DA up to the ceiling
  esiEnabled: boolean; ptEnabled: boolean;
  ptState: string | null;                                 // two letters, e.g. "TG"; required when ptEnabled
  pfEstablishmentCode: string | null; esiEmployerCode: string | null; ptRegistration: string | null;
  tan: string | null;                                     // AAAA99999A
  bankName: string | null; bankBranch: string | null;
  bankAccountNumber: string | null; bankIfsc: string | null };   // 6–18 digits and an IFSC, together or neither
type SettingsView = SettingsForm & { saved: boolean; updatedAt: string | null; statutory: StatutoryView };
type StatutoryView = { month: string; rate: RateView | null; rates: RateView[];
  financialYear: string; taxRule: TaxRuleView | null;
  taxRulesForThisYear: boolean;                           // false: an earlier year's rules are being used
  taxRules: TaxRuleView[]; ptStates: string[]; ptSlabs: PtSlabsView | null };
type PtSlab = { appliesTo: "ALL" | "WOMEN" | "OTHERS"; fromPaise: number; toPaise: number | null;
  amountPaise: number; months: number[] };                // months 1–12 it applies in; [] = every month
type PtSlabsForm = { effectiveFrom: string; slabs: PtSlab[] };   // 1–40 slabs, amount ≤ ₹10,000; no two may
  // overlap (400 slabs[i].fromPaise): same pay range (limits included), same people (ALL overlaps WOMEN and OTHERS)
  // and both every-month or sharing a month. A month-specific slab on top of an every-month slab is allowed.
type PtSlabsView = { state: string; custom: boolean; effectiveFrom: string | null; slabs: PtSlab[] };
```

Statutory reference data is not school data (no `tenant_id`; the runtime role can only read it). Every row has the
date it applies from, so a new rate is a new row. Seeded: PF/ESI rates from 2019-07-01 (EPF 12% + 12% on wages up
to ₹15,000; EPS 8.33% at most ₹1,250; EDLI 0.5% at most ₹75; EPF administration 0.5%, at least ₹500 a month; ESI
0.75% + 3.25% up to ₹21,000 gross); the 2025-26 new-regime tax rules (standard deduction ₹75,000, 87A rebate up to
₹60,000 for taxable income up to ₹12 lakh with marginal relief, 4% cess); professional tax for Telangana, Andhra
Pradesh, Karnataka, Maharashtra (women's slabs, ₹300 in February), Tamil Nadu (half-yearly, in September and March)
and West Bengal. The screens say to verify these each year with the school's accountant.

## Components — `/api/payroll/components`

| Method | Path | Permission | Success |
| --- | --- | --- | --- |
| GET | `/components` | `payroll.read` | `200 ComponentView[]` by kind and order |
| POST | `/components` | `payroll.manage` | `201 ComponentView` |
| POST | `/components/standard` | `payroll.manage` | `200 ComponentView[]`: adds the standard ones the school lacks (BASIC, DA, HRA, CONVEYANCE, SPECIAL; LOAN, OTHER_DED) |
| PUT | `/components/{id}` | `payroll.manage` | `200 ComponentView` (code and kind never change) |

```ts
type ComponentFields = { name: string; pfWage: boolean; esiWage: boolean; taxable: boolean;
  prorated: boolean;                                      // reduced for loss of pay and part months
  displayOrder: number; active: boolean };
type NewComponent = { code: string;                      // ^[A-Za-z][A-Za-z0-9_]{0,19}$, stored upper case
  kind: "EARNING" | "DEDUCTION"; fields: ComponentFields };
type ComponentView = ComponentFields & { id: string; code: string; kind: "EARNING" | "DEDUCTION"; inUse: boolean };
```

`400 errors.code` for payroll's own line codes (PF, ESI, PT, TDS, ARREARS, BONUS, DEDUCTION, EPF_ER, EPS, EDLI,
PF_ADMIN, ESI_ER); `409` for a code or name (any case) the school already has.

## Staff pay — `/api/payroll/staff`, `/api/payroll/structures`

| Method | Path | Permission | Success |
| --- | --- | --- | --- |
| GET | `/staff` | `payroll.read` | `200 PayStaffList`: active staff and anyone with a structure |
| GET | `/staff/{userId}` | `payroll.read` | `200 PayStaffDetail` (masked) |
| GET | `/staff/{userId}/pay-profile/full` | `payroll.manage` | `200 PayProfileFull`; **audited every time** (`payroll_pay_profile.viewed`) |
| PUT | `/staff/{userId}/pay-profile` | `payroll.manage` | `200 PayProfileView` |
| POST | `/staff/{userId}/structures` | `payroll.manage` | `201 StructureView` |
| DELETE | `/structures/{id}` | `payroll.manage` | `204`; `409` once an approved month used it |

```ts
type PayProfileForm = { bankAccountNumber: string | null; bankIfsc: string | null;   // together or neither
  accountHolderName: string | null; pan: string | null;   // ABCDE1234F
  uan: string | null;                                     // 12 digits
  esiNumber: string | null;                               // 10 digits
  pfMember: boolean; ptWomenSlab: boolean };
type PayProfileView = { saved: boolean; bankAccountMasked: string | null;   // "XXXXXXXX9012"
  bankLast4: string | null; bankIfsc: string | null; accountHolderName: string | null;
  panMasked: string | null;                               // "XXXXXX234F"
  uan: string | null; esiNumber: string | null; pfMember: boolean; ptWomenSlab: boolean; updatedAt: string | null };
type PayProfileFull = PayProfileForm;                     // unmasked
type StructureForm = { effectiveFrom: string;             // "YYYY-MM"
  note: string | null; lines: { componentId: string; monthlyPaise: number }[] };   // 1–30, positive, ≤ ₹10 crore
type StructureView = { id: string; effectiveFrom: string; note: string | null; createdByName: string;
  createdAt: string; lines: { componentId: string; code: string; name: string; kind: string; monthlyPaise: number }[];
  grossPaise: number; fixedDeductionsPaise: number; employerPaise: number;
  ctcPaise: number;                                       // gross + employer contributions, full month, today's rates
  current: boolean; locked: boolean };
type PayStaffRow = { userId: string; name: string; employeeCode: string | null; designation: string | null;
  departmentId: string | null; departmentName: string | null; active: boolean; profileComplete: boolean;
  dateOfJoining: string | null; dateOfLeaving: string | null; structureFrom: string | null;
  grossPaise: number; ctcPaise: number; hasBank: boolean; bankLast4: string | null; panMasked: string | null;
  hasPan: boolean; hasUan: boolean; hasEsiNumber: boolean };
type PayStaffList = { month: string; staff: PayStaffRow[]; needStructure: number; needBankDetails: number };
type PayStaffDetail = { userId: string; name: string; email: string; employeeCode: string | null;
  designation: string | null; departmentName: string | null; active: boolean; profileComplete: boolean;
  dateOfJoining: string | null; dateOfLeaving: string | null; payProfile: PayProfileView;
  structures: StructureView[];                            // newest first
  payslips: { id: string; month: string; status: RunStatus; grossPaise: number; netPaise: number }[] };
```

A revision is a new structure from a later month; the one in force for a month is the latest starting on or before
it. Rules: at least one earning (`400 errors.lines`), each active component once (`400 errors["lines[i].componentId"]`),
a month that already has a structure `409 errors.effectiveFrom`, and a month on or before the latest approved payroll
month `409 errors.effectiveFrom` (pay owed for an approved month goes in as arrears). The staff id is the user id;
parents, students and other schools' people are `404`. Saving pay details is audited with the fields that changed,
never their values.

## Runs — `/api/payroll/runs`

| Method | Path | Permission | Success |
| --- | --- | --- | --- |
| GET | `/runs` | `payroll.read` | `200 RunList` newest first |
| POST | `/runs` | `payroll.manage` | `201 RunDetail`: a draft, calculated at once. `{ month: "YYYY-MM" }`; `400` after the current month, `409 errors.month` when the month has a run |
| GET | `/runs/{id}` | `payroll.read` | `200 RunDetail` |
| POST | `/runs/{id}/recalculate` | `payroll.manage` | `200 RunDetail` |
| DELETE | `/runs/{id}` | `payroll.manage` | `204` (drafts only) |
| POST | `/runs/{id}/adjustments` | `payroll.manage` | `201 RunDetail`; `400 amountPaise` when the person's `DEDUCTION` adjustments would add up to more than the payslip's gross |
| DELETE | `/runs/{id}/adjustments/{adjustmentId}` | `payroll.manage` | `200 RunDetail` |
| PUT | `/runs/{id}/payslips/{payslipId}/tds` | `payroll.manage` | `200 RunDetail`: `{ amountPaise, reason }` replaces the month's calculated TDS; `400 amountPaise` above the payslip's gross |
| DELETE | `/runs/{id}/payslips/{payslipId}/tds` | `payroll.manage` | `200 RunDetail`: back to the calculated TDS |
| POST | `/runs/{id}/approve` | `payroll.approve` | `200 RunDetail`, status `APPROVED`; publishes `PayrollApproved` |
| POST | `/runs/{id}/paid` | `payroll.manage` | `200 RunDetail`: `{ paidOn, reference? }`, status `PAID` |

```ts
type RunStatus = "DRAFT" | "APPROVED" | "PAID";
type RunTotals = { staffCount: number; grossPaise: number; deductionsPaise: number; netPaise: number;
  employerPaise: number; pfEmployeePaise: number; pfEmployerPaise: number; pfAdminPaise: number; edliPaise: number;
  esiEmployeePaise: number; esiEmployerPaise: number; ptPaise: number; tdsPaise: number };
type RunList = { runs: { id: string; month: string; status: RunStatus; totals: RunTotals; calculatedAt: string;
    approvedByName: string | null; approvedAt: string | null; paidOn: string | null }[];
  suggestedMonth: string;                                 // the month after the latest run, at most this month
  needStructure: number };
type AdjustmentForm = { userId: string; kind: "ARREARS" | "BONUS" | "DEDUCTION" | "LOP_CORRECTION";
  amountPaise?: number;                                   // ARREARS, BONUS, DEDUCTION
  days?: number;                                          // LOP_CORRECTION: whole or half days, -31..31, not 0
  reason: string };
type RunDetail = { id: string; month: string; status: RunStatus; prorationBasis: ProrationBasis;
  daysInMonth: number; workingDays: number; totals: RunTotals; calculatedAt: string; createdByName: string;
  approvedByName: string | null; approvedAt: string | null; paidOn: string | null;
  paymentReference: string | null; paidByName: string | null; taxFinancialYear: string | null;
  payslips: { id: string; userId: string; name: string; employeeCode: string | null; departmentName: string | null;
    payableDays: number; lopDays: number; grossPaise: number; pfEmployeePaise: number; esiEmployeePaise: number;
    ptPaise: number; tdsPaise: number; tdsOverridden: boolean; tdsOverrideReason: string | null;
    tdsCalculatedPaise: number; otherDeductionsPaise: number; deductionsPaise: number; netPaise: number;
    employerPaise: number; hasBank: boolean }[];
  adjustments: { id: string; userId: string; name: string; kind: string; amountPaise: number | null;
    days: number | null; reason: string; createdByName: string; createdAt: string }[];
  warnings: { code: "NO_STRUCTURE" | "NO_BANK" | "NO_PAN" | "NO_UAN" | "NO_ESI_NUMBER" | "PROFILE_INCOMPLETE"
      | "TAX_RULES_EARLIER_YEAR" | "NO_TAX_RULES" | "NO_PT_STATE" | "NO_PT_SLABS";
    userId: string | null; name: string | null; detail: string | null }[];
  version: number };
```

**Who is in a month:** every member of staff with a structure in force on the 1st who is on the roll in the month
(joined by its last day; not left before its first day; active, or left during it). **Calculation**, per person, in
this order:

1. Days: the basis is the month's calendar days or working days. Days on the roll run from the joining date (or the
   1st) to the leaving date (or the last day). Loss of pay is approved leave of a loss-of-pay leave type in the month
   (working days, half days as 0.5, from the leave module) plus `LOP_CORRECTION` adjustments, never below 0 or above
   the days on the roll. Payable days = days on the roll − loss of pay.
2. Earnings: prorated components are paid for payable days out of the basis days, to the nearest rupee (halves up);
   a full month is the monthly amount exactly. Arrears and bonus are added.
3. PF (members only, when enabled): on the PF wages earned (basic + DA components), capped at the ₹15,000 ceiling
   unless the school contributes on full wages. Employee 12%; employer 12% split into EPS (8.33% of capped wages, at
   most ₹1,250) and EPF (the rest); EDLI 0.5% (capped); administration 0.5% with a ₹500 minimum for the run. Each to
   the nearest rupee.
4. ESI (when enabled): covered when gross ESI wages are within ₹21,000, or were at the start of the contribution
   period (April–September, October–March) or when the person joined in it; covered people stay covered to the end of
   the period. 0.75% and 3.25%, each **up** to the next rupee.
5. Professional tax on the month's gross by the state's slabs (women's slabs where the state has them and
   `ptWomenSlab` is set; month-specific slabs replace the every-month slab in those months).
6. TDS (new regime): projected taxable income for the year = taxable pay in earlier approved months of the financial
   year + this month + this month's full taxable pay × the months left (to the leaving month if one is recorded);
   less the standard deduction, taxable income to the nearest ₹10; slab tax, 87A rebate and marginal relief, 4% cess,
   the year's tax to the nearest ₹10; the month's TDS = (year's tax − TDS already deducted) ÷ months left including
   this one, to the nearest rupee. When the year has no rules, the latest earlier year's are used (warning
   `TAX_RULES_EARLIER_YEAR`); with none at all there is no TDS (`NO_TAX_RULES`). An override replaces the month's
   TDS and is audited with its reason.
7. Deductions are taken in that order, then fixed deduction components and `DEDUCTION` adjustments, none beyond what
   is left: net pay is never negative.

Each payslip keeps a snapshot of the person (name, code, designation, department, dates, PAN masked, bank last four
and IFSC, UAN, ESI number) as of its calculation. **Locking:** everything on a run except reading answers `409`
("Payroll locked") once it is approved. Approval recalculates first: when the totals changed since the last
calculation (leave, structures, pay details), nothing is approved and the answer is `409` ("Figures changed") so the
approver recalculates and reviews again; a month with nobody in it is `409`. Marking paid: `409` for a draft or a
month already paid, `400 errors.paidOn` before the month starts or in the future.

## Payslips — `/api/payroll/payslips`, `/api/me/payslips`

| Method | Path | Permission | Success |
| --- | --- | --- | --- |
| GET | `/api/payroll/runs/{id}/payslips` | `payroll.read` | `200 PayslipView[]` by name (to print them all) |
| GET | `/api/payroll/payslips/{id}` | `payroll.read` | `200 PayslipView` (drafts too) |
| GET | `/api/me/payslips` | any staff role | `200 MyPayslipRow[]`: own payslips of approved and paid months, newest first; parents and students `403` |
| GET | `/api/me/payslips/{id}` | any staff role | `200 PayslipView`: own and approved or paid only; anything else `404` |

```ts
type LineView = { code: string; name: string; fullPaise: number | null; amountPaise: number };
type PayslipView = { id: string; runId: string; month: string; status: RunStatus; paidOn: string | null;
  school: { name: string; address: string | null; city: string | null; phone: string | null; email: string | null;
    pfEstablishmentCode: string | null; esiEmployerCode: string | null; tan: string | null };
  person: { userId: string; name: string; employeeCode: string | null; designation: string | null;
    departmentName: string | null; dateOfJoining: string | null; dateOfLeaving: string | null;
    panMasked: string | null; bankLast4: string | null; bankIfsc: string | null; uan: string | null;
    esiNumber: string | null };
  prorationBasis: ProrationBasis; basisDays: number; daysOnRoll: number; lopDays: number; payableDays: number;
  earnings: LineView[]; deductions: LineView[];           // PF, ESI, PT, TDS, fixed and one-off deductions
  employer: LineView[];                                   // EPF_ER, EPS, EDLI, PF_ADMIN, ESI_ER
  grossPaise: number; deductionsPaise: number; netPaise: number; employerPaise: number;
  netInWords: string;                                     // "Rupees Twenty Six Thousand Only"
  taxableYearProjectedPaise: number; taxYearPaise: number };
type MyPayslipRow = { id: string; month: string; status: RunStatus; paidOn: string | null; grossPaise: number;
  deductionsPaise: number; netPaise: number };
```

A payslip never shows a full account number or PAN.

## Files and summaries — `/api/payroll/runs/{id}/…`

All files need an approved or paid run (`409` for a draft) and are audited as `payroll.exported` with the kind and
row count. Each has `Content-Disposition: attachment` with the file name below and `Cache-Control: no-store`.

| Method | Path | Permission | Success |
| --- | --- | --- | --- |
| GET | `/bank-file.csv` | `payroll.manage` | `bank-transfer-YYYY-MM.csv`: Beneficiary name, Account number (full), IFSC, Amount (rupees, 2 decimals), Narration ("Salary Oct 2026"); UTF-8 without a byte order mark, CRLF; everyone with net pay and an account |
| GET | `/bank-advice` | `payroll.manage` | `200 BankAdvice`: the covering letter, accounts masked, total in words, who has no account |
| GET | `/ecr.txt` | `payroll.manage` | `ecr-YYYY-MM.txt`: EPFO ECR 2.0, one line per PF member with a UAN, 11 fields joined by `#~#` (UAN, name, gross, EPF, EPS and EDLI wages, EPF and EPS contributions, EPF−EPS difference, NCP days, refunds) in whole rupees |
| GET | `/esi.csv` | `payroll.manage` | `esi-YYYY-MM.csv`: IP number, name, days paid, wages, reason code (2 left service with last working day dd/MM/yyyy, 1 no days, 0 otherwise) for everyone covered with an ESI number |
| GET | `/professional-tax` | `payroll.read` | `200 PtSummary` (any run): by slab amount and by person |
| GET | `/professional-tax.csv` | `payroll.manage` | `professional-tax-YYYY-MM.csv` with a total row |
| GET | `/tds` | `payroll.read` | `200 TdsSummary` (any run): per person with the projected year, PAN masked |
| GET | `/tds.csv` | `payroll.manage` | `tds-YYYY-MM.csv` with a total row |

```ts
type BankAdvice = { month: string; status: RunStatus; paidOn: string | null; school: PayslipView["school"];
  bankName: string | null; bankBranch: string | null; debitAccountNumber: string | null; debitIfsc: string | null;
  count: number; totalPaise: number; totalInWords: string;
  rows: { name: string; employeeCode: string | null; accountMasked: string; ifsc: string; amountPaise: number }[];
  withoutBank: { userId: string; name: string; employeeCode: string | null }[] };
type PtSummary = { month: string; state: string | null; slabs: { amountPaise: number; count: number;
  totalPaise: number }[]; rows: { name: string; employeeCode: string | null; grossPaise: number; ptPaise: number }[];
  totalPaise: number };
type TdsSummary = { month: string; financialYear: string; rows: { name: string; employeeCode: string | null;
  panMasked: string | null; taxablePaise: number; projectedIncomePaise: number; annualTaxPaise: number;
  calculatedPaise: number; tdsPaise: number; overridden: boolean }[]; totalPaise: number };
```

The bank file and the bank advice use each person's pay details **at download time** (so an account corrected after
approval is paid to the right place); the payslip keeps the snapshot from the calculation. The bank file is the only
place a full account number leaves the system. CSV text cells starting with `= + - @` get a leading apostrophe.
Report and PT/TDS/ESI CSVs start with a UTF-8 byte order mark for Excel.

## Reports — `/api/payroll/reports` (all `payroll.read`)

| Method | Path | Success |
| --- | --- | --- |
| GET | `/register?month=&departmentId=` | `200 Register`: a month's payslips by department (default the latest month with a run; drafts included and marked) |
| GET | `/register.csv?month=&departmentId=` | CSV of the same |
| GET | `/year-to-date?financialYear=` | `200 YearToDate`: per person over approved and paid months (default the current year) |
| GET | `/year-to-date.csv?financialYear=` | CSV of the same |
| GET | `/statutory?financialYear=` | `200 StatutorySummary`: PF (employee, EPF, EPS, EDLI, admin), ESI, PT and TDS by month |
| GET | `/statutory.csv?financialYear=` | CSV of the same |
| GET | `/departments` | `200 {id, name}[]` for the register filter |

CSV downloads are audited as `payroll.exported` on `payroll_report` with their filters and row count. A
department of another school is `404`; a bad month or financial year is `400`. In the register, a person's employer
column is their own contributions; the EPF administration minimum top-up is a run-level amount in the totals only.

## Event

`PayrollApproved` (Spring application event, published inside the approving transaction):
`{ tenantId, runId, month: YearMonth, staffCount, grossPaise, deductionsPaise, netPaise, employerContributionsPaise,
pfEmployeePaise, pfEmployerPaise, edliPaise, pfAdminPaise, esiEmployeePaise, esiEmployerPaise, professionalTaxPaise,
tdsPaise, approvedById, approvedAt }`. For a later accounts module to post the salary journal; listeners that must
not act on a rolled-back approval use `@TransactionalEventListener`. Payroll reads staff (people, dates, departments)
and approved loss-of-pay leave through `staff.StaffPayrollInputs` (read-only) and changes nothing in other modules.

## Audit actions

`payroll_settings.updated`, `payroll_pt_slabs.updated`, `payroll_pt_slabs.reset`, `salary_component.created`,
`salary_component.updated`, `salary_component.standard_added`, `payroll_pay_profile.updated` (field names only),
`payroll_pay_profile.viewed` (every full view), `salary_structure.created`, `salary_structure.deleted`,
`payroll_run.created`, `payroll_run.recalculated`, `payroll_run.deleted`, `payroll_adjustment.added`,
`payroll_adjustment.removed`, `payroll_tds.overridden`, `payroll_tds.override_cleared`, `payroll_run.approved`,
`payroll_run.paid`, `payroll.exported` (files and report CSVs).

## Data and isolation

School tables in schema `payroll` (`settings`, `pt_slab`, `salary_component`, `salary_structure`,
`salary_structure_line`, `staff_pay_profile`, `payroll_run`, `payslip`, `payslip_line`, `adjustment`) carry
`tenant_id` with the same row-level security policy as every school table and composite `(tenant_id, id)` foreign
keys. Reference tables (`statutory_rate`, `tax_rule`, `tax_slab`, `pt_slab_default`) are read-only to the runtime
role. Aadhaar is never stored.

## Web

| Route | Permission | Screen |
| --- | --- | --- |
| `/app/payroll` | `payroll.read` | Monthly payroll: runs, "Work out a month" (manage), staff without a structure |
| `/app/payroll/runs/{id}` | `payroll.read` | Run review: totals, statutory, warnings, payslips, adjustments, TDS override, approve, mark paid, files |
| `/app/payroll/runs/{id}/payslips` | `payroll.read` | Every payslip of the month, one A4 page each when printed |
| `/app/payroll/runs/{id}/bank-advice` | `payroll.manage` | Bank advice letter (print) |
| `/app/payroll/payslips/{id}` | `payroll.read` | One payslip (print) |
| `/app/payroll/staff`, `/app/payroll/staff/{userId}` | `payroll.read` | Staff pay list (filters for missing structure or bank details); a person's structures, pay details and payslips |
| `/app/payroll/components`, `/app/payroll/settings` | `payroll.read` | Components; settings, statutory figures ("Verify each year") and PT slabs |
| `/app/my-payslips`, `/app/my-payslips/{id}` | any staff role | My payslips (Staff menu, for everyone with `leave.request`, which every staff role has) |
| `/app/reports/payroll-register`, `…/payroll-ytd`, `…/payroll-statutory` | `payroll.read` | Reports hub, Staff group, with CSV download and print |
