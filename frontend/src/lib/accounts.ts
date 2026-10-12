import type { PillTone } from "@/components/ui/pill";
import { parseRupees } from "./format";
import { financialYearOf } from "./payroll";
import type { BudgetStatus, ExpenseStatus, PayMode, PlainDate } from "./types";
import type { Problems } from "./use-form";

/* Pure helpers for the accounts screens (docs/api/phase-2-accounts.md). Money is in paise. */

export const GSTIN_PATTERN = /^[0-9]{2}[A-Za-z]{5}[0-9]{4}[A-Za-z][1-9A-Za-z][Zz][0-9A-Za-z]$/;
export const HEAD_CODE_PATTERN = /^[A-Za-z][A-Za-z0-9-]{1,19}$/;
export const PHONE_PATTERN = /^\d{8,15}$/;
export const FY_PATTERN = /^\d{4}-\d{2}$/;
export const MAX_ATTACHMENTS = 5;

/** The Indian financial year a date falls in: "2026-10-11" → "2026-27". */
export function financialYearOfDate(date: PlainDate): string {
  return financialYearOf(date.slice(0, 7));
}

/** "2026-27" → its first and last day. */
export function financialYearRange(year: string): { from: PlainDate; to: PlainDate } {
  const start = Number(year.slice(0, 4));
  return { from: `${start}-04-01`, to: `${start + 1}-03-31` };
}

/** The twelve months of "2026-27" as "2026-04" … "2027-03". */
export function financialYearMonths(year: string): string[] {
  const start = Number(year.slice(0, 4));
  return Array.from({ length: 12 }, (_, i) => {
    const m = ((i + 3) % 12) + 1;
    const y = m >= 4 ? start : start + 1;
    return `${y}-${String(m).padStart(2, "0")}`;
  });
}

/** GST at a rate on an amount, rounded to the nearest paisa (half up), as the API computes it. */
export function gstAt(amountPaise: number, ratePercent: number): number {
  return Math.round((amountPaise * ratePercent) / 100);
}

/** TDS on the amount before GST (CBDT circular 23/2017), rounded to the nearest paisa. */
export function tdsOn(amountPaise: number, ratePercent: number | null): number {
  if (ratePercent === null || ratePercent <= 0 || amountPaise <= 0) return 0;
  return Math.round((amountPaise * ratePercent) / 100);
}

export const GST_RATES = [0, 5, 12, 18, 28] as const;

export function expenseTone(status: ExpenseStatus): PillTone {
  switch (status) {
    case "DRAFT":
      return "neutral";
    case "SUBMITTED":
      return "warn";
    case "APPROVED":
      return "info";
    case "PAID":
      return "good";
    case "REJECTED":
      return "bad";
  }
}

export function budgetTone(status: BudgetStatus): PillTone {
  switch (status) {
    case "OK":
      return "good";
    case "NEAR":
      return "warn";
    case "OVER":
      return "bad";
    case "NONE":
      return "neutral";
  }
}

export type ExpenseValues = {
  headId: string;
  costCentreId: string;
  vendorId: string;
  vendorName: string;
  expenseDate: string;
  amount: string;
  gstRate: string;
  gst: string;
  deductTds: boolean;
  description: string;
  billNumber: string;
};

/** The same checks POST /api/accounts/expenses makes, before anything is sent. Exported for tests. */
export function validateExpense(values: ExpenseValues, today: PlainDate, gstApplicable: boolean): Problems {
  const problems: Problems = {};
  if (!values.headId) problems.headId = "accounts.v.head";
  if (!values.expenseDate) problems.expenseDate = "accounts.v.date";
  else if (values.expenseDate > today) problems.expenseDate = "accounts.v.futureDate";
  const amount = parseRupees(values.amount);
  if (amount === null || amount <= 0) problems.amount = "accounts.v.amount";
  const gst = values.gst.trim() === "" ? 0 : parseRupees(values.gst);
  if (gst === null) problems.gst = "accounts.v.gst";
  else if (gst > 0 && !gstApplicable) problems.gst = "accounts.v.gstHead";
  else if (amount !== null && gst > amount) problems.gst = "accounts.v.gstTooMuch";
  if (values.deductTds && !values.vendorId) problems.deductTds = "accounts.v.tdsVendor";
  if (!values.description.trim()) problems.description = "accounts.v.description";
  else if (values.description.trim().length > 500) problems.description = "accounts.v.descriptionLong";
  if (!values.vendorId && values.vendorName.trim().length > 120) problems.vendorName = "accounts.v.vendorNameLong";
  return problems;
}

export type PaidValues = { paidOn: string; mode: PayMode; bankAccountId: string; reference: string };

/** The same checks POST /api/accounts/expenses/{id}/paid makes. Exported for tests. */
export function validatePaid(values: PaidValues, expenseDate: PlainDate, today: PlainDate): Problems {
  const problems: Problems = {};
  if (!values.paidOn) problems.paidOn = "accounts.v.date";
  else if (values.paidOn > today) problems.paidOn = "accounts.v.futureDate";
  else if (values.paidOn < expenseDate) problems.paidOn = "accounts.v.paidBeforeBill";
  if (values.mode !== "CASH" && !values.bankAccountId) problems.bankAccountId = "accounts.v.bankAccount";
  if (values.mode === "CHEQUE" && !values.reference.trim()) problems.reference = "accounts.v.chequeNumber";
  return problems;
}

export type VendorValues = {
  name: string;
  gstin: string;
  pan: string;
  phone: string;
  email: string;
  address: string;
  bankAccountNumber: string;
  bankIfsc: string;
  tdsSection: string;
  tdsRatePercent: string;
  active: boolean;
};

const PAN = /^[A-Za-z]{5}\d{4}[A-Za-z]$/;
const IFSC = /^[A-Za-z]{4}0[A-Za-z0-9]{6}$/;
const ACCOUNT = /^\d{6,18}$/;

/** The same checks the vendor endpoints make. Exported for tests. */
export function validateVendor(values: VendorValues): Problems {
  const problems: Problems = {};
  if (!values.name.trim()) problems.name = "accounts.v.name";
  if (values.gstin.trim() && !GSTIN_PATTERN.test(values.gstin.trim())) problems.gstin = "accounts.v.gstin";
  if (values.pan.trim() && !PAN.test(values.pan.trim())) problems.pan = "accounts.v.pan";
  if (values.phone.trim() && !PHONE_PATTERN.test(values.phone.trim())) problems.phone = "accounts.v.phone";
  const account = values.bankAccountNumber.trim();
  const ifsc = values.bankIfsc.trim();
  if (account && !ACCOUNT.test(account)) problems.bankAccountNumber = "accounts.v.account";
  if (ifsc && !IFSC.test(ifsc)) problems.bankIfsc = "accounts.v.ifsc";
  if (Boolean(account) !== Boolean(ifsc)) {
    if (!account) problems.bankAccountNumber = "accounts.v.accountAndIfsc";
    else problems.bankIfsc = "accounts.v.accountAndIfsc";
  }
  const rate = values.tdsRatePercent.trim();
  if (values.tdsSection && !rate) problems.tdsRatePercent = "accounts.v.tdsRate";
  if (rate) {
    const n = Number(rate);
    if (!Number.isFinite(n) || n < 0 || n > 30) problems.tdsRatePercent = "accounts.v.tdsRate";
    if (!values.tdsSection) problems.tdsSection = "accounts.v.tdsSection";
  }
  return problems;
}

/** The standard TDS rates as data (verify with the accountant): 194C 1% (individual/HUF) or 2%, 194J 10%. */
export const DEFAULT_TDS_RATES: Record<string, number> = { "194C": 2, "194J": 10, "194H": 5, "194I": 10, "194Q": 0.1 };

export type IncomeValues = {
  kind: string;
  headId: string;
  receivedOn: string;
  amount: string;
  payerName: string;
  payerPan: string;
  payerAddress: string;
  mode: PayMode;
  bankAccountId: string;
  reference: string;
  description: string;
};

/** The same checks POST /api/accounts/income makes. Exported for tests. */
export function validateIncome(values: IncomeValues, today: PlainDate, needsPan: boolean): Problems {
  const problems: Problems = {};
  if (!values.headId) problems.headId = "accounts.v.head";
  if (!values.receivedOn) problems.receivedOn = "accounts.v.date";
  else if (values.receivedOn > today) problems.receivedOn = "accounts.v.futureDate";
  const amount = parseRupees(values.amount);
  if (amount === null || amount <= 0) problems.amount = "accounts.v.amount";
  if (!values.payerName.trim()) problems.payerName = "accounts.v.payer";
  if (values.payerPan.trim() && !PAN.test(values.payerPan.trim())) problems.payerPan = "accounts.v.pan";
  if (needsPan && values.kind === "DONATION" && !values.payerPan.trim()) problems.payerPan = "accounts.v.panFor80g";
  if (values.mode !== "CASH" && !values.bankAccountId) problems.bankAccountId = "accounts.v.bankAccount";
  return problems;
}

/** "" for nothing, else the trimmed text. */
export function blank(value: string, upper = false): string | null {
  const v = value.trim();
  if (!v) return null;
  return upper ? v.toUpperCase() : v;
}
