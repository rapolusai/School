"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import { Pill, type PillTone } from "@/components/ui/pill";
import { useAuth } from "@/lib/auth";
import { translateOr, useI18n, type MessageKey } from "@/lib/i18n";
import { hasPermission, PERMISSIONS } from "@/lib/permissions";
import type { DueStatus, InstalmentDue, PaymentMode, ReceiptStatus } from "@/lib/types";

const DUE_TONES: Record<DueStatus, PillTone> = {
  UPCOMING: "neutral",
  DUE: "info",
  PARTIAL: "warn",
  OVERDUE: "bad",
  PAID: "good",
};

export function dueStatusTone(status: DueStatus): PillTone {
  return DUE_TONES[status] ?? "neutral";
}

export function DueStatusPill({ status }: { status: DueStatus }) {
  const { t } = useI18n();
  return (
    <Pill tone={dueStatusTone(status)} dot>
      {translateOr(t, `fees.status.${status}`, status)}
    </Pill>
  );
}

export function ReceiptStatusPill({ status }: { status: ReceiptStatus }) {
  const { t } = useI18n();
  return (
    <Pill tone={status === "CANCELLED" ? "bad" : "good"} dot>
      {translateOr(t, `fees.receiptStatus.${status}`, status)}
    </Pill>
  );
}

export function useModeLabel() {
  const { t } = useI18n();
  return (mode: PaymentMode) => translateOr(t, `fees.mode.${mode}`, mode);
}

/** What is still to pay on an instalment now: its balance plus any late fee. */
export function payableOf(instalment: InstalmentDue, includeLateFee = true): number {
  return instalment.balancePaise + (includeLateFee ? instalment.lateFeePaise : 0);
}

/** Instalments with something left to pay (balance or late fee), oldest first as the API sends them. */
export function openInstalments(instalments: InstalmentDue[]): InstalmentDue[] {
  return instalments.filter((i) => i.balancePaise > 0 || i.lateFeePaise > 0);
}

type FeesLink = { href: string; labelKey: MessageKey; permission: string; exact?: boolean };

const FEES_LINKS: FeesLink[] = [
  { href: "/app/fees", labelKey: "fees.nav.overview", permission: PERMISSIONS.feesRead, exact: true },
  { href: "/app/fees/collect", labelKey: "fees.nav.collect", permission: PERMISSIONS.feesCollect },
  { href: "/app/fees/dues", labelKey: "fees.nav.dues", permission: PERMISSIONS.feesRead },
  { href: "/app/fees/receipts", labelKey: "fees.nav.receipts", permission: PERMISSIONS.feesRead },
  { href: "/app/fees/structures", labelKey: "fees.nav.structures", permission: PERMISSIONS.feesRead },
  { href: "/app/fees/concessions", labelKey: "fees.nav.concessions", permission: PERMISSIONS.feesRead },
  { href: "/app/fees/settings", labelKey: "fees.nav.settings", permission: PERMISSIONS.feesRead },
];

/** The fees section's own navigation, shown under each fees page's heading. */
export function FeesNav() {
  const { t } = useI18n();
  const { me } = useAuth();
  const pathname = usePathname() ?? "";
  const links = FEES_LINKS.filter((link) => hasPermission(me, link.permission));
  return (
    <nav className="subnav" aria-label={t("fees.nav.label")}>
      {links.map((link) => {
        const active = link.exact
          ? pathname === link.href
          : pathname === link.href || pathname.startsWith(`${link.href}/`);
        return (
          <Link key={link.href} href={link.href} aria-current={active ? "page" : undefined}>
            {t(link.labelKey)}
          </Link>
        );
      })}
    </nav>
  );
}

/** A labelled amount for the summary strips ("Balance ₹11,500"). */
export function Amount({ label, value, tone }: { label: string; value: string; tone?: "bad" | "good" }) {
  return (
    <div className="amount">
      <span>{label}</span>
      <b className={`num${tone === "bad" ? " text-bad" : tone === "good" ? " text-good" : ""}`}>{value}</b>
    </div>
  );
}
