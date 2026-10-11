import type { MessageKey } from "./i18n/en";
import { hasPermission, PERMISSIONS } from "./permissions";
import type { AdjustmentKind, Me, RunDetail, RunStatus, RunWarningCode } from "./types";
import type { Problems } from "./use-form";

/* Pure payroll helpers shared by the payroll screens (docs/api/phase-2-payroll.md). Exported for tests. */

export const MONTH_PATTERN = /^\d{4}-(0[1-9]|1[0-2])$/;
export const ACCOUNT_PATTERN = /^\d{6,18}$/;
export const IFSC_PATTERN = /^[A-Za-z]{4}0[A-Za-z0-9]{6}$/;
export const PAN_PATTERN = /^[A-Za-z]{5}\d{4}[A-Za-z]$/;
export const TAN_PATTERN = /^[A-Za-z]{4}\d{5}[A-Za-z]$/;
export const UAN_PATTERN = /^\d{12}$/;
export const ESI_PATTERN = /^\d{10}$/;
export const COMPONENT_CODE_PATTERN = /^[A-Za-z][A-Za-z0-9_]{0,19}$/;
export const REGISTRATION_PATTERN = /^[A-Za-z0-9/ -]{1,30}$/;

/** Codes payroll uses for its own payslip lines (statutory deductions, employer contributions, adjustments). */
export const RESERVED_COMPONENT_CODES = [
  "PF",
  "ESI",
  "PT",
  "TDS",
  "ARREARS",
  "BONUS",
  "DEDUCTION",
  "EPF_ER",
  "EPS",
  "EDLI",
  "PF_ADMIN",
  "ESI_ER",
] as const;

type Subject = Pick<Me, "permissions" | "platformAdmin"> | null | undefined;

/** "2026-09" → "September 2026" (or "सितंबर 2026"). */
export function formatMonth(month: string | null | undefined, locale = "en-IN"): string {
  if (!month || !MONTH_PATTERN.test(month)) return month ?? "";
  const date = new Date(Date.UTC(Number(month.slice(0, 4)), Number(month.slice(5, 7)) - 1, 1));
  return new Intl.DateTimeFormat(locale, { month: "long", year: "numeric", timeZone: "UTC" }).format(date);
}

/** "2026-09" → "2026-27": the financial year (April to March) a month falls in. */
export function financialYearOf(month: string): string {
  const year = Number(month.slice(0, 4));
  const start = Number(month.slice(5, 7)) >= 4 ? year : year - 1;
  return `${start}-${String((start + 1) % 100).padStart(2, "0")}`;
}

/** This financial year and the ones before it, newest first. */
export function financialYearsUpTo(month: string, count = 5): string[] {
  const start = Number(financialYearOf(month).slice(0, 4));
  return Array.from({ length: count }, (_, i) => {
    const y = start - i;
    return `${y}-${String((y + 1) % 100).padStart(2, "0")}`;
  });
}

/** The month before "2026-01" is "2025-12". */
export function previousMonth(month: string): string {
  const year = Number(month.slice(0, 4));
  const m = Number(month.slice(5, 7));
  return m === 1 ? `${year - 1}-12` : `${year}-${String(m - 1).padStart(2, "0")}`;
}

/** 28 → "28", 1.5 → "1.5": payroll days are whole or half days. */
export function formatPayDays(days: number): string {
  return Number.isInteger(days) ? String(days) : days.toFixed(1);
}

export function runStatusTone(status: RunStatus): "warn" | "info" | "good" {
  return status === "DRAFT" ? "warn" : status === "APPROVED" ? "info" : "good";
}

/** A warning's message key: per person or for the run. */
export function warningKey(code: RunWarningCode): MessageKey {
  return `payroll.warning.${code}` as MessageKey;
}

/** Warnings that stop nothing but are worth a look; the per-person ones grouped by code. */
export function groupWarnings(warnings: RunDetail["warnings"]): { code: RunWarningCode; names: string[]; detail: string | null }[] {
  const groups = new Map<RunWarningCode, { code: RunWarningCode; names: string[]; detail: string | null }>();
  for (const w of warnings) {
    const group = groups.get(w.code) ?? { code: w.code, names: [], detail: w.detail };
    if (w.name) group.names.push(w.name);
    groups.set(w.code, group);
  }
  return [...groups.values()];
}

export type RunActions = {
  recalculate: boolean;
  adjust: boolean;
  remove: boolean;
  approve: boolean;
  markPaid: boolean;
  files: boolean;
};

/** What the signed-in person may do with a run as it stands. A locked month never changes. */
export function runActions(run: Pick<RunDetail, "status" | "totals">, me: Subject): RunActions {
  const manage = hasPermission(me, PERMISSIONS.payrollManage);
  const draft = run.status === "DRAFT";
  return {
    recalculate: manage && draft,
    adjust: manage && draft,
    remove: manage && draft,
    approve: hasPermission(me, PERMISSIONS.payrollApprove) && draft && run.totals.staffCount > 0,
    markPaid: manage && run.status === "APPROVED",
    files: manage && !draft,
  };
}

/** "1", "-0.5": whole or half days between -31 and 31, not zero; null otherwise. */
export function parseLopDays(text: string): number | null {
  const value = text.trim();
  if (!/^-?\d{1,2}(\.\d)?$/.test(value)) return null;
  const n = Number(value);
  if (!Number.isFinite(n) || n === 0 || Math.abs(n) > 31 || !Number.isInteger(n * 2)) return null;
  return n;
}

export function adjustmentIsMoney(kind: AdjustmentKind): boolean {
  return kind !== "LOP_CORRECTION";
}

export type AdjustmentValues = { userId: string; kind: AdjustmentKind; amount: string; days: string; reason: string };

/** The checks the API makes on an adjustment, as message keys. */
export function validateAdjustment(values: AdjustmentValues, parseRupees: (text: string) => number | null): Problems {
  const problems: Problems = {};
  if (!values.userId) problems.userId = "validation.choose";
  if (adjustmentIsMoney(values.kind)) {
    const paise = parseRupees(values.amount);
    if (paise === null || paise <= 0) problems.amount = "payroll.v.amount";
  } else if (parseLopDays(values.days) === null) {
    problems.days = "payroll.v.days";
  }
  if (!values.reason.trim()) problems.reason = "validation.required";
  else if (values.reason.trim().length > 500) problems.reason = "validation.tooLong";
  return problems;
}

export type ProfileValues = {
  bankAccountNumber: string;
  bankIfsc: string;
  accountHolderName: string;
  pan: string;
  uan: string;
  esiNumber: string;
  pfMember: boolean;
  ptWomenSlab: boolean;
};

/** The shapes the API accepts for pay details; the account number and IFSC go together. */
export function validateProfile(values: ProfileValues): Problems {
  const problems: Problems = {};
  const account = values.bankAccountNumber.trim();
  const ifsc = values.bankIfsc.trim();
  if (account && !ACCOUNT_PATTERN.test(account)) problems.bankAccountNumber = "payroll.v.account";
  if (ifsc && !IFSC_PATTERN.test(ifsc)) problems.bankIfsc = "payroll.v.ifsc";
  if (account && !ifsc) problems.bankIfsc = "payroll.v.together";
  if (ifsc && !account) problems.bankAccountNumber = "payroll.v.together";
  if (values.pan.trim() && !PAN_PATTERN.test(values.pan.trim())) problems.pan = "payroll.v.pan";
  if (values.uan.trim() && !UAN_PATTERN.test(values.uan.trim())) problems.uan = "payroll.v.uan";
  if (values.esiNumber.trim() && !ESI_PATTERN.test(values.esiNumber.trim())) problems.esiNumber = "payroll.v.esi";
  if (values.accountHolderName.trim().length > 200) problems.accountHolderName = "validation.tooLong";
  return problems;
}

/** Trimmed, upper case when asked, null when blank. */
export function blankToNull(value: string, upper = false): string | null {
  const v = value.trim();
  if (!v) return null;
  return upper ? v.toUpperCase() : v;
}

/** Sum of the amounts typed for earnings and deductions of a structure being written. */
export function structureTotals(
  lines: { kind: "EARNING" | "DEDUCTION"; amount: string }[],
  parseRupees: (text: string) => number | null,
): { grossPaise: number; deductionsPaise: number; invalid: boolean } {
  let gross = 0;
  let deductions = 0;
  let invalid = false;
  for (const line of lines) {
    if (!line.amount.trim()) continue;
    const paise = parseRupees(line.amount);
    if (paise === null || paise <= 0) {
      invalid = true;
      continue;
    }
    if (line.kind === "EARNING") gross += paise;
    else deductions += paise;
  }
  return { grossPaise: gross, deductionsPaise: deductions, invalid };
}

/** Indian states and union territories by their two-letter code, for professional tax. */
export const STATE_CODES = [
  "AP", "AR", "AS", "BR", "CG", "DL", "GA", "GJ", "HP", "HR", "JH", "JK", "KA", "KL", "LA", "MH", "ML", "MN", "MP",
  "MZ", "NL", "OD", "PB", "PY", "RJ", "SK", "TG", "TN", "TR", "UK", "UP", "WB",
] as const;
