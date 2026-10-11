/** Types mirroring docs/api/phase-2-payroll.md (payroll). Keep in sync with the contract. Money is in paise. */

import type { PlainDate } from "./school";

/** "2026-09" */
export type PayrollMonth = string;

export type ProrationBasis = "CALENDAR_DAYS" | "WORKING_DAYS";
export type ComponentKind = "EARNING" | "DEDUCTION";
export type PtAppliesTo = "ALL" | "WOMEN" | "OTHERS";
export type RunStatus = "DRAFT" | "APPROVED" | "PAID";
export const ADJUSTMENT_KINDS = ["ARREARS", "BONUS", "DEDUCTION", "LOP_CORRECTION"] as const;
export type AdjustmentKind = (typeof ADJUSTMENT_KINDS)[number];

/* ------------------------------------------------------------------ settings and statutory figures */

export type StatutoryRateView = {
  effectiveFrom: PlainDate;
  pfEmployeePercent: number;
  pfEmployerPercent: number;
  pfWageCeilingPaise: number;
  epsPercent: number;
  epsMaxPaise: number;
  edliPercent: number;
  edliMaxPaise: number;
  pfAdminPercent: number;
  pfAdminMinPaise: number;
  esiEmployeePercent: number;
  esiEmployerPercent: number;
  esiWageCeilingPaise: number;
  note: string;
};

export type TaxSlabView = { fromPaise: number; toPaise: number | null; ratePercent: number };

export type TaxRuleView = {
  financialYear: string;
  startsOn: PlainDate;
  standardDeductionPaise: number;
  rebateIncomeLimitPaise: number;
  rebateMaxPaise: number;
  cessPercent: number;
  slabs: TaxSlabView[];
  note: string;
};

export type PtSlabView = {
  appliesTo: PtAppliesTo;
  fromPaise: number;
  toPaise: number | null;
  amountPaise: number;
  /** Months (1–12) the row applies in; empty for every month. */
  months: number[];
};

export type PtSlabsView = { state: string; custom: boolean; effectiveFrom: PlainDate | null; slabs: PtSlabView[] };

export type StatutoryView = {
  month: PayrollMonth;
  rate: StatutoryRateView | null;
  rates: StatutoryRateView[];
  financialYear: string;
  taxRule: TaxRuleView | null;
  /** false when the rules shown are an earlier year's */
  taxRulesForThisYear: boolean;
  taxRules: TaxRuleView[];
  ptStates: string[];
  ptSlabs: PtSlabsView | null;
};

export type PayrollSettingsView = {
  saved: boolean;
  payDay: number;
  prorationBasis: ProrationBasis;
  pfEnabled: boolean;
  pfOnFullWages: boolean;
  esiEnabled: boolean;
  ptEnabled: boolean;
  ptState: string | null;
  pfEstablishmentCode: string | null;
  esiEmployerCode: string | null;
  ptRegistration: string | null;
  tan: string | null;
  bankName: string | null;
  bankBranch: string | null;
  bankAccountNumber: string | null;
  bankIfsc: string | null;
  updatedAt: string | null;
  statutory: StatutoryView;
};

export type PayrollSettingsRequest = Omit<PayrollSettingsView, "saved" | "updatedAt" | "statutory">;

export type PtSlabInput = {
  appliesTo: PtAppliesTo;
  fromPaise: number;
  toPaise: number | null;
  amountPaise: number;
  months: number[];
};

export type PtSlabsRequest = { effectiveFrom: PlainDate; slabs: PtSlabInput[] };

/* ------------------------------------------------------------------ components */

export type SalaryComponentView = {
  id: string;
  code: string;
  name: string;
  kind: ComponentKind;
  pfWage: boolean;
  esiWage: boolean;
  taxable: boolean;
  prorated: boolean;
  displayOrder: number;
  active: boolean;
  inUse: boolean;
};

export type ComponentFields = {
  name: string;
  pfWage: boolean;
  esiWage: boolean;
  taxable: boolean;
  prorated: boolean;
  displayOrder: number;
  active: boolean;
};

export type NewComponentRequest = { code: string; kind: ComponentKind; fields: ComponentFields };

/* ------------------------------------------------------------------ staff pay details and structures */

export type PayPersonRef = { userId: string; name: string; employeeCode: string | null };

export type PayStaffRow = {
  userId: string;
  name: string;
  employeeCode: string | null;
  designation: string | null;
  departmentId: string | null;
  departmentName: string | null;
  active: boolean;
  profileComplete: boolean;
  dateOfJoining: PlainDate | null;
  dateOfLeaving: PlainDate | null;
  /** First day of the month the structure shown starts; null without a structure. */
  structureFrom: PlainDate | null;
  grossPaise: number;
  ctcPaise: number;
  hasBank: boolean;
  bankLast4: string | null;
  panMasked: string | null;
  hasPan: boolean;
  hasUan: boolean;
  hasEsiNumber: boolean;
};

export type PayStaffList = { month: PayrollMonth; staff: PayStaffRow[]; needStructure: number; needBankDetails: number };

/** Pay details with the account number and PAN masked. */
export type PayProfileView = {
  saved: boolean;
  bankAccountMasked: string | null;
  bankLast4: string | null;
  bankIfsc: string | null;
  accountHolderName: string | null;
  panMasked: string | null;
  uan: string | null;
  esiNumber: string | null;
  pfMember: boolean;
  ptWomenSlab: boolean;
  updatedAt: string | null;
};

/** In full: payroll managers only, and every view is audited. */
export type PayProfileFull = {
  bankAccountNumber: string | null;
  bankIfsc: string | null;
  accountHolderName: string | null;
  pan: string | null;
  uan: string | null;
  esiNumber: string | null;
  pfMember: boolean;
  ptWomenSlab: boolean;
};

export type PayProfileRequest = PayProfileFull;

export type StructureLineView = {
  componentId: string;
  code: string;
  name: string;
  kind: ComponentKind;
  monthlyPaise: number;
};

export type StructureView = {
  id: string;
  effectiveFrom: PayrollMonth;
  note: string | null;
  createdByName: string | null;
  createdAt: string;
  lines: StructureLineView[];
  grossPaise: number;
  fixedDeductionsPaise: number;
  employerPaise: number;
  ctcPaise: number;
  current: boolean;
  locked: boolean;
};

export type SalaryStructureRequest = {
  effectiveFrom: PayrollMonth;
  note: string | null;
  lines: { componentId: string; monthlyPaise: number }[];
};

export type PayslipRef = { id: string; month: PayrollMonth; status: RunStatus; grossPaise: number; netPaise: number };

export type PayStaffDetail = {
  userId: string;
  name: string;
  email: string;
  employeeCode: string | null;
  designation: string | null;
  departmentName: string | null;
  active: boolean;
  profileComplete: boolean;
  dateOfJoining: PlainDate | null;
  dateOfLeaving: PlainDate | null;
  payProfile: PayProfileView;
  structures: StructureView[];
  payslips: PayslipRef[];
};

/* ------------------------------------------------------------------ runs */

export type RunTotals = {
  staffCount: number;
  grossPaise: number;
  deductionsPaise: number;
  netPaise: number;
  employerPaise: number;
  pfEmployeePaise: number;
  pfEmployerPaise: number;
  pfAdminPaise: number;
  edliPaise: number;
  esiEmployeePaise: number;
  esiEmployerPaise: number;
  ptPaise: number;
  tdsPaise: number;
};

export type RunRow = {
  id: string;
  month: PayrollMonth;
  status: RunStatus;
  totals: RunTotals;
  calculatedAt: string;
  approvedByName: string | null;
  approvedAt: string | null;
  paidOn: PlainDate | null;
};

export type RunList = { runs: RunRow[]; suggestedMonth: PayrollMonth; needStructure: number };

export type PayslipRow = {
  id: string;
  userId: string;
  name: string;
  employeeCode: string | null;
  departmentName: string | null;
  payableDays: number;
  lopDays: number;
  grossPaise: number;
  pfEmployeePaise: number;
  esiEmployeePaise: number;
  ptPaise: number;
  tdsPaise: number;
  tdsOverridden: boolean;
  tdsOverrideReason: string | null;
  tdsCalculatedPaise: number;
  otherDeductionsPaise: number;
  deductionsPaise: number;
  netPaise: number;
  employerPaise: number;
  hasBank: boolean;
};

export type AdjustmentView = {
  id: string;
  userId: string;
  name: string | null;
  kind: AdjustmentKind;
  amountPaise: number | null;
  days: number | null;
  reason: string;
  createdByName: string | null;
  createdAt: string;
};

export const RUN_WARNING_CODES = [
  "NO_TAX_RULES",
  "TAX_RULES_EARLIER_YEAR",
  "NO_PT_STATE",
  "NO_PT_SLABS",
  "NO_STRUCTURE",
  "PROFILE_INCOMPLETE",
  "NO_BANK",
  "NO_PAN",
  "NO_UAN",
  "NO_ESI_NUMBER",
] as const;
export type RunWarningCode = (typeof RUN_WARNING_CODES)[number];

export type RunWarning = { code: RunWarningCode; userId: string | null; name: string | null; detail: string | null };

export type RunDetail = {
  id: string;
  month: PayrollMonth;
  status: RunStatus;
  prorationBasis: ProrationBasis;
  daysInMonth: number;
  workingDays: number;
  totals: RunTotals;
  calculatedAt: string;
  createdByName: string | null;
  approvedByName: string | null;
  approvedAt: string | null;
  paidOn: PlainDate | null;
  paymentReference: string | null;
  paidByName: string | null;
  /** The financial year whose tax rules were used; null when none. */
  taxFinancialYear: string | null;
  payslips: PayslipRow[];
  adjustments: AdjustmentView[];
  warnings: RunWarning[];
  version: number;
};

export type AdjustmentRequest = {
  userId: string;
  kind: AdjustmentKind;
  amountPaise: number | null;
  days: number | null;
  reason: string;
};

export type TdsOverrideRequest = { amountPaise: number; reason: string };
export type PaidRequest = { paidOn: PlainDate; reference: string | null };

/* ------------------------------------------------------------------ payslips */

export type PayrollSchoolView = {
  name: string;
  address: string | null;
  city: string | null;
  phone: string | null;
  email: string | null;
  pfEstablishmentCode: string | null;
  esiEmployerCode: string | null;
  tan: string | null;
};

export type PayslipPerson = {
  userId: string;
  name: string;
  employeeCode: string | null;
  designation: string | null;
  departmentName: string | null;
  dateOfJoining: PlainDate | null;
  dateOfLeaving: PlainDate | null;
  panMasked: string | null;
  bankLast4: string | null;
  bankIfsc: string | null;
  uan: string | null;
  esiNumber: string | null;
};

export type PayslipLineView = { code: string; name: string; fullPaise: number | null; amountPaise: number };

export type PayslipView = {
  id: string;
  runId: string;
  month: PayrollMonth;
  status: RunStatus;
  paidOn: PlainDate | null;
  school: PayrollSchoolView;
  person: PayslipPerson;
  prorationBasis: ProrationBasis;
  basisDays: number;
  daysOnRoll: number;
  lopDays: number;
  payableDays: number;
  earnings: PayslipLineView[];
  deductions: PayslipLineView[];
  employer: PayslipLineView[];
  grossPaise: number;
  deductionsPaise: number;
  netPaise: number;
  employerPaise: number;
  netInWords: string;
  taxableYearProjectedPaise: number;
  taxYearPaise: number;
};

export type MyPayslipRow = {
  id: string;
  month: PayrollMonth;
  status: RunStatus;
  paidOn: PlainDate | null;
  grossPaise: number;
  deductionsPaise: number;
  netPaise: number;
};

/* ------------------------------------------------------------------ files and summaries */

export type BankAdviceRow = {
  name: string;
  employeeCode: string | null;
  accountMasked: string;
  ifsc: string;
  amountPaise: number;
};

export type BankAdvice = {
  month: PayrollMonth;
  status: RunStatus;
  paidOn: PlainDate | null;
  school: PayrollSchoolView;
  bankName: string | null;
  bankBranch: string | null;
  debitAccountNumber: string | null;
  debitIfsc: string | null;
  count: number;
  totalPaise: number;
  totalInWords: string;
  rows: BankAdviceRow[];
  withoutBank: PayPersonRef[];
};

/* ------------------------------------------------------------------ reports */

export type RegisterRow = {
  userId: string;
  name: string;
  employeeCode: string | null;
  designation: string | null;
  payableDays: number;
  lopDays: number;
  grossPaise: number;
  pfEmployeePaise: number;
  esiEmployeePaise: number;
  ptPaise: number;
  tdsPaise: number;
  otherDeductionsPaise: number;
  deductionsPaise: number;
  netPaise: number;
  employerPaise: number;
};

export type RegisterTotals = {
  count: number;
  grossPaise: number;
  pfEmployeePaise: number;
  esiEmployeePaise: number;
  ptPaise: number;
  tdsPaise: number;
  otherDeductionsPaise: number;
  deductionsPaise: number;
  netPaise: number;
  employerPaise: number;
};

export type RegisterDepartment = {
  departmentId: string | null;
  departmentName: string | null;
  rows: RegisterRow[];
  totals: RegisterTotals;
};

export type PayrollRegister = {
  month: PayrollMonth;
  runId: string | null;
  status: RunStatus | null;
  departmentId: string | null;
  departments: RegisterDepartment[];
  totals: RegisterTotals;
};

export type YtdRow = {
  userId: string | null;
  name: string;
  employeeCode: string | null;
  departmentName: string | null;
  months: number;
  grossPaise: number;
  pfEmployeePaise: number;
  esiEmployeePaise: number;
  ptPaise: number;
  tdsPaise: number;
  otherDeductionsPaise: number;
  netPaise: number;
  employerPaise: number;
};

export type PayrollYearToDate = { financialYear: string; months: PayrollMonth[]; rows: YtdRow[]; totals: YtdRow };

export type StatutoryMonth = {
  month: PayrollMonth | null;
  status: RunStatus | null;
  staffCount: number;
  pfEmployeePaise: number;
  epfEmployerPaise: number;
  epsPaise: number;
  edliPaise: number;
  pfAdminPaise: number;
  esiEmployeePaise: number;
  esiEmployerPaise: number;
  ptPaise: number;
  tdsPaise: number;
  totalPaise: number;
};

export type PayrollStatutorySummary = { financialYear: string; months: StatutoryMonth[]; totals: StatutoryMonth };

export type PayrollDepartmentRef = { id: string; name: string };
