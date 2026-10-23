"use client";

import { TriangleAlert } from "lucide-react";
import Link from "next/link";
import { usePathname } from "next/navigation";
import { SelectField } from "@/components/ui/field";
import { api } from "@/lib/api";
import { useAuth } from "@/lib/auth";
import { govreportApi } from "@/lib/govreport-api";
import {
  translateOr,
  useI18n,
  type MessageKey,
  type Translate,
} from "@/lib/i18n";
import { hasPermission, PERMISSIONS } from "@/lib/permissions";
import type { CodeListType, CodeView } from "@/lib/types";
import { useApiData } from "@/lib/use-api-data";

/* Shared pieces of the government reporting screens (docs/api/phase-3-govreport.md). */

const LINKS: { href: string; labelKey: MessageKey }[] = [
  { href: "/app/govreport", labelKey: "govreport.nav.profile" },
  { href: "/app/govreport/students", labelKey: "govreport.nav.students" },
  { href: "/app/govreport/exports", labelKey: "govreport.nav.exports" },
  { href: "/app/govreport/rte", labelKey: "govreport.nav.rte" },
  { href: "/app/govreport/schemes", labelKey: "govreport.nav.schemes" },
  { href: "/app/govreport/code-lists", labelKey: "govreport.nav.codeLists" },
];

export function GovReportNav() {
  const { t } = useI18n();
  const pathname = usePathname() ?? "";
  return (
    <nav className="subnav no-print" aria-label={t("govreport.nav.label")}>
      {LINKS.map((link) => {
        const active =
          link.href === "/app/govreport"
            ? pathname === link.href
            : pathname === link.href || pathname.startsWith(`${link.href}/`);
        return (
          <Link
            key={link.href}
            href={link.href}
            aria-current={active ? "page" : undefined}
          >
            {t(link.labelKey)}
          </Link>
        );
      })}
    </nav>
  );
}

/** Every file here is prepared for the school to upload by hand; the portal's template may have changed. */
export function VerifyNote({ className }: { className?: string }) {
  const { t } = useI18n();
  return (
    <div
      className={`alert alert-warn ${className ?? ""}`}
      role="note"
      data-testid="govreport-verify"
    >
      <TriangleAlert
        size={18}
        aria-hidden="true"
        className="mt-0.5 flex-none"
      />
      <span>{t("govreport.verify")}</span>
    </div>
  );
}

export function useCanManage(): boolean {
  const { me } = useAuth();
  return hasPermission(me, PERMISSIONS.govreportManage);
}

/** The academic years, and the current one's id (the default everywhere). */
export function useYears() {
  const years = useApiData("govreport:years", api.listYears);
  const list = years.data ?? [];
  const current = list.find((y) => y.current)?.id ?? list[0]?.id ?? "";
  return { years: list, current, loading: years.loading };
}

export function YearSelect({
  value,
  onChange,
}: {
  value: string;
  onChange: (id: string) => void;
}) {
  const { t } = useI18n();
  const { years } = useYears();
  return (
    <SelectField
      label={t("govreport.year")}
      name="yearId"
      value={value}
      onChange={(e) => onChange(e.target.value)}
      options={years.map((y) => ({ value: y.id, label: y.name }))}
      className="w-full sm:w-auto sm:min-w-36"
    />
  );
}

/** The codes of a list in force today, for a select. */
export function useCodes(list: CodeListType): CodeView[] {
  const codes = useApiData(`govreport:codes:${list}`, () =>
    govreportApi.codes(list),
  );
  return (codes.data ?? []).filter((c) => c.inForce);
}

export function codeOptions(
  codes: CodeView[],
  emptyLabel: string,
): { value: string; label: string }[] {
  return [
    { value: "", label: emptyLabel },
    ...codes.map((c) => ({ value: c.code, label: c.label })),
  ];
}

export function labelOf(
  codes: CodeView[],
  code: string | null | undefined,
): string {
  if (!code) return "—";
  return codes.find((c) => c.code === code)?.label ?? code;
}

/** "" / "Y" / "N" for a yes-no select. */
export function yesNo(value: boolean | null | undefined): string {
  return value === true ? "Y" : value === false ? "N" : "";
}

export function fromYesNo(value: string): boolean | null {
  return value === "Y" ? true : value === "N" ? false : null;
}

export function yesNoOptions(t: Translate) {
  return [
    { value: "", label: t("govreport.notSet") },
    { value: "Y", label: t("govreport.yes") },
    { value: "N", label: t("govreport.no") },
  ];
}

/** The readable name of a missing field ("apaarId" → "APAAR ID"). */
export function fieldLabel(t: Translate, field: string): string {
  return translateOr(t, `govreport.field.${field}`, field);
}
