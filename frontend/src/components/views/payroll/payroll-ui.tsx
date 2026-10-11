"use client";

import { ArrowLeft, Info } from "lucide-react";
import Link from "next/link";
import { usePathname } from "next/navigation";
import { Pill } from "@/components/ui/pill";
import { useAuth } from "@/lib/auth";
import { localeFor, translateOr, useI18n, type MessageKey } from "@/lib/i18n";
import { formatMonth, runStatusTone } from "@/lib/payroll";
import { hasPermission, PERMISSIONS } from "@/lib/permissions";
import type { RunStatus } from "@/lib/types";

type PayrollLink = { href: string; labelKey: MessageKey; exact?: boolean; also?: string };

const PAYROLL_LINKS: PayrollLink[] = [
  { href: "/app/payroll", labelKey: "payroll.nav.runs", exact: true, also: "/app/payroll/runs" },
  { href: "/app/payroll/staff", labelKey: "payroll.nav.staff" },
  { href: "/app/payroll/components", labelKey: "payroll.nav.components" },
  { href: "/app/payroll/settings", labelKey: "payroll.nav.settings" },
];

/** The payroll section's own navigation, shown under each payroll page's heading. */
export function PayrollNav() {
  const { t } = useI18n();
  const { me } = useAuth();
  const pathname = usePathname() ?? "";
  if (!hasPermission(me, PERMISSIONS.payrollRead)) return null;
  return (
    <nav className="subnav" aria-label={t("payroll.nav.label")}>
      {PAYROLL_LINKS.map((link) => {
        const active =
          (link.exact ? pathname === link.href : pathname === link.href || pathname.startsWith(`${link.href}/`)) ||
          (link.also !== undefined && pathname.startsWith(link.also)) ||
          (link.href === "/app/payroll" && pathname.startsWith("/app/payroll/payslips"));
        return (
          <Link key={link.href} href={link.href} aria-current={active ? "page" : undefined}>
            {t(link.labelKey)}
          </Link>
        );
      })}
    </nav>
  );
}

export function RunStatusPill({ status }: { status: RunStatus }) {
  const { t } = useI18n();
  return (
    <Pill tone={runStatusTone(status)} dot>
      {translateOr(t, `payroll.status.${status}`, status)}
    </Pill>
  );
}

/** "September 2026" in the reader's language. */
export function useMonthLabel(): (month: string | null | undefined) => string {
  const { lang } = useI18n();
  const locale = localeFor(lang);
  return (month) => formatMonth(month, locale);
}

/** Statutory rates and limits come from data and change: the screens say to confirm them with the accountant. */
export function AccountantNote({ children }: { children?: React.ReactNode }) {
  const { t } = useI18n();
  return (
    <div className="alert alert-info" role="note" data-testid="payroll-accountant-note">
      <Info size={18} aria-hidden="true" className="mt-0.5 flex-none" />
      <span>
        {t("payroll.confirmWithAccountant")}
        {children ? <> {children}</> : null}
      </span>
    </div>
  );
}

export function BackLink({ href, label }: { href: string; label: string }) {
  return (
    <Link href={href} className="link no-print inline-flex items-center gap-1 text-[13.5px]">
      <ArrowLeft size={16} aria-hidden="true" />
      {label}
    </Link>
  );
}

/** A yes/no value in a details list. */
export function yesNo(t: (key: "payroll.yes" | "payroll.no") => string, value: boolean): string {
  return value ? t("payroll.yes") : t("payroll.no");
}
