"use client";

import { Info } from "lucide-react";
import Link from "next/link";
import { usePathname } from "next/navigation";
import { Pill } from "@/components/ui/pill";
import { DateFilter } from "@/components/views/reports/report-frame";
import { budgetTone, expenseTone } from "@/lib/accounts";
import { accountsApi } from "@/lib/accounts-api";
import { useAuth } from "@/lib/auth";
import { todayInIndia } from "@/lib/format";
import { translateOr, useI18n, type MessageKey } from "@/lib/i18n";
import { financialYearsUpTo } from "@/lib/payroll";
import { hasAnyPermission, hasPermission, PERMISSIONS } from "@/lib/permissions";
import type { BudgetStatus, ExpenseStatus, PayMode } from "@/lib/types";
import { useApiData } from "@/lib/use-api-data";

type AccountsLink = { href: string; labelKey: MessageKey; exact?: boolean; permission?: string };

const ACCOUNTS_LINKS: AccountsLink[] = [
  { href: "/app/accounts", labelKey: "accounts.nav.overview", exact: true },
  { href: "/app/accounts/expenses", labelKey: "accounts.nav.expenses" },
  { href: "/app/accounts/approvals", labelKey: "accounts.nav.approvals", permission: PERMISSIONS.accountsApprove },
  { href: "/app/accounts/income", labelKey: "accounts.nav.income", permission: PERMISSIONS.accountsRead },
  { href: "/app/accounts/petty-cash", labelKey: "accounts.nav.pettyCash", permission: PERMISSIONS.accountsRead },
  { href: "/app/accounts/budgets", labelKey: "accounts.nav.budgets" },
  { href: "/app/accounts/bank", labelKey: "accounts.nav.bank", permission: PERMISSIONS.accountsRead },
  { href: "/app/accounts/vendors", labelKey: "accounts.nav.vendors" },
  { href: "/app/accounts/setup", labelKey: "accounts.nav.setup" },
];

/** The accounts section's own navigation, shown under each accounts page's heading. */
export function AccountsNav() {
  const { t } = useI18n();
  const { me } = useAuth();
  const pathname = usePathname() ?? "";
  if (!hasAnyPermission(me, [PERMISSIONS.accountsRead, PERMISSIONS.accountsApprove])) return null;
  return (
    <nav className="subnav" aria-label={t("accounts.nav.label")}>
      {ACCOUNTS_LINKS.filter((link) => !link.permission || hasPermission(me, link.permission)).map((link) => {
        const active = link.exact ? pathname === link.href : pathname === link.href || pathname.startsWith(`${link.href}/`);
        return (
          <Link key={link.href} href={link.href} aria-current={active ? "page" : undefined}>
            {t(link.labelKey)}
          </Link>
        );
      })}
    </nav>
  );
}

export function ExpenseStatusPill({ status }: { status: ExpenseStatus }) {
  const { t } = useI18n();
  return (
    <Pill tone={expenseTone(status)} dot>
      {translateOr(t, `accounts.status.${status}`, status)}
    </Pill>
  );
}

export function BudgetPill({ status, percent }: { status: BudgetStatus; percent?: number }) {
  const { t } = useI18n();
  if (status === "NONE") return <span className="text-ink-3">{t("accounts.budget.none")}</span>;
  return (
    <Pill tone={budgetTone(status)} dot>
      {translateOr(t, `accounts.budgetStatus.${status}`, status)}
      {percent !== undefined ? ` · ${percent}%` : ""}
    </Pill>
  );
}

/** "Cash", "Bank transfer", "UPI", "Cheque" in the reader's language. */
export function useModeLabel(): (mode: PayMode | null | undefined) => string {
  const { t } = useI18n();
  return (mode) => (mode ? translateOr(t, `accounts.mode.${mode}`, mode) : "—");
}

/** These are management accounts, not the school's statutory books: every accounts screen says so once. */
export function ManagementNote() {
  const { t } = useI18n();
  return (
    <div className="alert alert-info" role="note" data-testid="accounts-management-note">
      <Info size={18} aria-hidden="true" className="mt-0.5 flex-none" />
      <span>{t("accounts.managementNote")}</span>
    </div>
  );
}

export function useHeads() {
  return useApiData("accounts:heads", accountsApi.heads);
}

export function useVendors() {
  return useApiData("accounts:vendors", accountsApi.vendors);
}

export function useBankAccounts() {
  return useApiData("accounts:bank-accounts", accountsApi.bankAccounts);
}

export function useCostCentres() {
  return useApiData("accounts:cost-centres", accountsApi.costCentres);
}

/** A from–to pair of date inputs for the books and reports. */
export function PeriodFilter({
  from,
  to,
  onChange,
}: {
  from: string;
  to: string;
  onChange: (from: string, to: string) => void;
}) {
  const { t } = useI18n();
  const today = todayInIndia();
  return (
    <>
      <DateFilter name="from" label={t("reports.filter.from")} value={from} max={today} onChange={(v) => onChange(v, to)} />
      <DateFilter name="to" label={t("reports.filter.to")} value={to} max={today} onChange={(v) => onChange(from, v)} />
    </>
  );
}

/** This financial year and the ones before it. */
export function YearFilter({ value, onChange }: { value: string; onChange: (year: string) => void }) {
  const { t } = useI18n();
  const years = financialYearsUpTo(todayInIndia().slice(0, 7));
  return (
    <label className="field w-full sm:w-auto sm:min-w-40">
      <span className="field-label">{t("accounts.financialYear")}</span>
      <select className="input" name="financialYear" value={value} onChange={(e) => onChange(e.target.value)}>
        {years.map((y) => (
          <option key={y} value={y}>
            {y}
          </option>
        ))}
      </select>
    </label>
  );
}

/** A labelled figure in a summary strip (a list of these sits in a `rep-kpis` grid). */
export function Stat({
  label,
  value,
  sub,
  testId,
  tone,
  attention,
}: {
  label: string;
  value: string;
  sub?: string;
  testId?: string;
  tone?: "bad" | "good";
  attention?: boolean;
}) {
  return (
    <li className={`rep-kpi${attention ? " is-attention" : ""}`} data-testid={testId}>
      <span className="text-[13px] font-medium text-ink-2">{label}</span>
      <span className={`kpi-value${tone === "bad" ? " text-bad" : tone === "good" ? " text-good" : ""}`}>{value}</span>
      {sub ? <span className="text-[12.5px] text-ink-3">{sub}</span> : null}
    </li>
  );
}
