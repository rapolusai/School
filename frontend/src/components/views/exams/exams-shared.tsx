"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import { AccessDenied } from "@/components/access";
import { Pill, type PillTone } from "@/components/ui/pill";
import { useAuth } from "@/lib/auth";
import { formatPlainDate } from "@/lib/format";
import { translateOr, useI18n, type MessageKey, type Translate } from "@/lib/i18n";
import { hasAnyPermission, PERMISSIONS } from "@/lib/permissions";
import type { ExamPaper, ExamYearRef, MarkCode, OverallResult, SheetProgress } from "@/lib/types";

/* Shared parts of the exams screens (docs/api/phase-2-exams.md). */

/** Who may open the marks entry list: readers, teachers who enter marks and the publishers. */
export const MARKS_PERMISSIONS = [PERMISSIONS.examsRead, PERMISSIONS.examsManage, PERMISSIONS.examsPublish] as const;

/** Every exams permission: holding any of them opens the exams area. */
export const EXAMS_PERMISSIONS = [
  PERMISSIONS.examsRead,
  PERMISSIONS.examsManage,
  PERMISSIONS.examsSetup,
  PERMISSIONS.examsPublish,
] as const;

/** Renders children only when the signed-in person holds any of the permissions. */
export function RequireAny({ permissions, children }: { permissions: readonly string[]; children: React.ReactNode }) {
  const { me } = useAuth();
  if (!hasAnyPermission(me, permissions)) return <AccessDenied />;
  return <>{children}</>;
}

/** 37 → "37", 37.5 → "37.5", 46.25 → "46.25" (at most two decimals); null → "—". Pure, exported for tests. */
export function formatScore(value: number | null | undefined): string {
  if (value === null || value === undefined || Number.isNaN(value)) return "—";
  return String(Math.round(value * 100) / 100);
}

/** 89.5 → "89.5%"; null → "—". */
export function formatPct(value: number | null | undefined): string {
  return value === null || value === undefined || Number.isNaN(value) ? "—" : `${formatScore(value)}%`;
}

/** "09:30:00" or "09:30" → "09:30"; null → "". */
export function shortTime(value: string | null | undefined): string {
  return value ? value.slice(0, 5) : "";
}

/** "Theory 80 + Internal 20" for a paper's components (only those it has). */
export function maxParts(t: Translate, paper: Pick<ExamPaper, "maxTheory" | "maxPractical" | "maxInternal">): string {
  const parts: string[] = [];
  if (paper.maxTheory > 0) parts.push(t("exams.part.theoryMax", { max: formatScore(paper.maxTheory) }));
  if (paper.maxPractical > 0) parts.push(t("exams.part.practicalMax", { max: formatScore(paper.maxPractical) }));
  if (paper.maxInternal > 0) parts.push(t("exams.part.internalMax", { max: formatScore(paper.maxInternal) }));
  return parts.join(" + ");
}

/** "Mon, 14 Sept 2026 · 09:30–11:30", or "Not scheduled". */
export function paperWhen(t: Translate, paper: ExamPaper, locale: string): string {
  if (!paper.date) return t("exams.paper.unscheduled");
  const time = paper.startTime ? ` · ${shortTime(paper.startTime)}–${shortTime(paper.endTime)}` : "";
  return `${formatPlainDate(paper.date, locale)}${time}`;
}

export function codeLabel(t: Translate, code: MarkCode): string {
  return translateOr(t, `exams.code.${code}`, code);
}

const RESULT_TONES: Record<OverallResult, PillTone> = { PASS: "good", NEEDS_IMPROVEMENT: "warn", INCOMPLETE: "neutral" };

export function ResultPill({ result }: { result: OverallResult }) {
  const { t } = useI18n();
  return (
    <Pill tone={RESULT_TONES[result] ?? "neutral"} dot>
      {translateOr(t, `exams.result.${result}`, result)}
    </Pill>
  );
}

const SHEET_TONES: Record<SheetProgress, PillTone> = { LOCKED: "good", DRAFT: "warn", NOT_STARTED: "neutral" };

export function SheetPill({ status }: { status: SheetProgress }) {
  const { t } = useI18n();
  return (
    <Pill tone={SHEET_TONES[status] ?? "neutral"} dot>
      {translateOr(t, `exams.sheet.${status}`, status)}
    </Pill>
  );
}

/** A choice of academic year; nothing when the school has only one. */
export function YearSelect({
  years,
  value,
  onChange,
}: {
  years: ExamYearRef[];
  value: string;
  onChange: (yearId: string) => void;
}) {
  const { t } = useI18n();
  if (years.length < 2) return null;
  return (
    <label className="field w-full sm:w-auto sm:min-w-40">
      <span className="field-label">{t("exams.year")}</span>
      <select className="input" name="yearId" value={value} onChange={(e) => onChange(e.target.value)}>
        {years.map((y) => (
          <option key={y.id} value={y.id}>
            {y.current ? t("exams.yearCurrent", { name: y.name }) : y.name}
          </option>
        ))}
      </select>
    </label>
  );
}

type ExamsLink = { href: string; labelKey: MessageKey; permissions: readonly string[]; exact?: boolean };

const EXAMS_LINKS: ExamsLink[] = [
  { href: "/app/exams", labelKey: "exams.nav.exams", permissions: [PERMISSIONS.examsRead], exact: true },
  { href: "/app/exams/marks", labelKey: "exams.nav.marks", permissions: MARKS_PERMISSIONS },
  { href: "/app/exams/report-cards", labelKey: "exams.nav.reportCards", permissions: [PERMISSIONS.examsRead] },
  { href: "/app/exams/grading", labelKey: "exams.nav.grading", permissions: [PERMISSIONS.examsRead] },
];

/** The exams area's own navigation, under each exams page's heading. */
export function ExamsNav() {
  const { t } = useI18n();
  const { me } = useAuth();
  const pathname = usePathname() ?? "";
  const links = EXAMS_LINKS.filter((link) => hasAnyPermission(me, link.permissions));
  if (links.length < 2) return null;
  return (
    <nav className="subnav no-print" aria-label={t("exams.nav.label")}>
      {links.map((link) => {
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

/** A number typed into a marks or weight field: "" → null, "37.5" → 37.5, anything else → NaN. Pure. */
export function parseDecimal(text: string): number | null {
  const trimmed = text.trim();
  if (trimmed === "") return null;
  if (!/^\d+(\.\d{1,2})?$/.test(trimmed)) return Number.NaN;
  return Number(trimmed);
}

/** A number as an input's text: null → "". */
export function decimalText(value: number | null | undefined): string {
  return value === null || value === undefined ? "" : formatScore(value);
}
