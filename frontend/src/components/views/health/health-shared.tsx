"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import { Pill, type PillTone } from "@/components/ui/pill";
import { useAuth } from "@/lib/auth";
import { formatDateTime } from "@/lib/format";
import { translateOr, useI18n, type MessageKey, type Translate } from "@/lib/i18n";
import { hasAnyPermission, hasPermission, PERMISSIONS } from "@/lib/permissions";
import type { AllergyBadge, AllergySeverity, Due, HealthItem, VerificationStatus, VisitOutcome } from "@/lib/types";

/* Parts shared by the health and safety screens (docs/api/phase-3-health.md). */

type HealthLink = { href: string; labelKey: MessageKey; anyOf: readonly string[]; exact?: boolean };

const READ = [PERMISSIONS.healthRead, PERMISSIONS.healthManage] as const;
const SAFETY = [PERMISSIONS.safetyManage] as const;

const HEALTH_LINKS: HealthLink[] = [
  { href: "/app/health", labelKey: "health.nav.infirmary", anyOf: READ, exact: true },
  { href: "/app/health/medicines", labelKey: "health.nav.medicines", anyOf: READ },
  { href: "/app/health/safety", labelKey: "health.nav.safety", anyOf: SAFETY },
  { href: "/app/health/pocso", labelKey: "health.nav.pocso", anyOf: [PERMISSIONS.leaveRequest, ...SAFETY] },
  { href: "/app/health/staff", labelKey: "health.nav.staff", anyOf: SAFETY },
];

/** The health module's own navigation under each page's heading. */
export function HealthNav() {
  const { t } = useI18n();
  const { me } = useAuth();
  const pathname = usePathname() ?? "";
  const links = HEALTH_LINKS.filter((link) => hasAnyPermission(me, link.anyOf));
  if (links.length < 2) return null;
  return (
    <nav className="subnav no-print" aria-label={t("health.nav.label")}>
      {links.map((link) => {
        const active = link.exact
          ? pathname === link.href || pathname.startsWith("/app/health/students")
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

/** True when the signed-in person keeps the records and the infirmary (health.manage). */
export function useCanManageHealth(): boolean {
  const { me } = useAuth();
  return hasPermission(me, PERMISSIONS.healthManage);
}

/* ------------------------------------------------------------------ labels and pills */

export const severityLabel = (t: Translate, s: AllergySeverity) => translateOr(t, `health.severity.${s}`, s);
export const outcomeLabel = (t: Translate, o: VisitOutcome) => translateOr(t, `health.outcome.${o}`, o);
export const dueLabel = (t: Translate, d: Due) => translateOr(t, `health.due.${d}`, d);
export const verificationLabel = (t: Translate, s: VerificationStatus) => translateOr(t, `health.verification.${s}`, s);

export const SEVERITY_TONE: Record<AllergySeverity, PillTone> = { MILD: "info", MODERATE: "warn", SEVERE: "bad" };
export const OUTCOME_TONE: Record<VisitOutcome, PillTone> = { BACK_TO_CLASS: "good", SENT_HOME: "warn", HOSPITAL: "bad" };
export const DUE_TONE: Record<Due, PillTone> = { OK: "good", DUE_SOON: "warn", OVERDUE: "bad", MISSING: "neutral" };
export const VERIFICATION_TONE: Record<VerificationStatus, PillTone> = { PENDING: "neutral", REQUESTED: "info", CLEARED: "good", FLAGGED: "bad" };

export function DuePill({ due }: { due: Due }) {
  const { t } = useI18n();
  return (
    <Pill tone={DUE_TONE[due]} dot>
      {dueLabel(t, due)}
    </Pill>
  );
}

export function OutcomePill({ outcome }: { outcome: VisitOutcome | null }) {
  const { t } = useI18n();
  if (!outcome) {
    return (
      <Pill tone="info" dot>
        {t("health.visit.open")}
      </Pill>
    );
  }
  return <Pill tone={OUTCOME_TONE[outcome]}>{outcomeLabel(t, outcome)}</Pill>;
}

/**
 * The allergy badges `HealthAlerts.forStudents` gives every other module: the severity and a short label, never the
 * details. Shown on the infirmary log, the medical record and wherever a student is served food or driven.
 */
export function AllergyBadges({ badges, testId }: { badges: readonly AllergyBadge[]; testId?: string }) {
  const { t } = useI18n();
  if (badges.length === 0) return null;
  return (
    <ul className="flex flex-wrap gap-1" aria-label={t("health.allergies")} data-testid={testId ?? "allergy-badges"}>
      {badges.map((b) => (
        <li key={`${b.studentId}:${b.label}`}>
          <Pill tone={SEVERITY_TONE[b.severity]} dot title={severityLabel(t, b.severity)}>
            {b.label}
          </Pill>
        </li>
      ))}
    </ul>
  );
}

/** Turns a record's allergies into badges, the way the API does for other modules. */
export function badgesOf(studentId: string, allergies: readonly HealthItem[]): AllergyBadge[] {
  return allergies.map((a) => ({ studentId, severity: a.severity ?? "MILD", label: a.name }));
}

/** "22 Oct 2026, 10:15" or an em dash. */
export function when(value: string | null | undefined, locale: string): string {
  return value ? formatDateTime(value, locale) : "—";
}

/** A date-time input's value for now (or now plus some minutes) in the viewer's local time. */
export function localNow(minutes = 0, now: Date = new Date()): string {
  const d = new Date(now.getTime() + minutes * 60_000);
  d.setSeconds(0, 0);
  const pad = (n: number) => String(n).padStart(2, "0");
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

/** A date-time input's value as an instant, or null when empty or malformed. */
export function localToInstant(value: string): string | null {
  if (!value) return null;
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? null : date.toISOString();
}

export const DATE = /^\d{4}-\d{2}-\d{2}$/;

/** A number from an input, or null when blank; NaN when not a number. */
export function numberOrNull(text: string): number | null {
  const trimmed = text.trim();
  if (!trimmed) return null;
  return Number(trimmed);
}
