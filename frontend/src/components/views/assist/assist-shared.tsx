"use client";

import { Sparkles } from "lucide-react";
import Link from "next/link";
import { usePathname } from "next/navigation";
import { Pill, type PillTone } from "@/components/ui/pill";
import { ASSIST_PROBLEMS, assistApi } from "@/lib/assist-api";
import type { ApiError } from "@/lib/api";
import { useAuth } from "@/lib/auth";
import { errorMessage } from "@/lib/error-message";
import { useI18n, type MessageKey, type Translate } from "@/lib/i18n";
import { hasPermission, PERMISSIONS } from "@/lib/permissions";
import type { AssistSettings, RiskLevel, WatchStatus } from "@/lib/types";
import { useApiData } from "@/lib/use-api-data";

type AssistLink = { href: string; labelKey: MessageKey; permission: string; exact?: boolean };

const ASSIST_LINKS: AssistLink[] = [
  { href: "/app/assist", labelKey: "assist.nav.remarks", permission: PERMISSIONS.assistUse, exact: true },
  { href: "/app/assist/watch-list", labelKey: "assist.nav.watchList", permission: PERMISSIONS.assistUse },
  { href: "/app/assist/papers", labelKey: "assist.nav.papers", permission: PERMISSIONS.assistUse },
  { href: "/app/assist/writing", labelKey: "assist.nav.writing", permission: PERMISSIONS.assistUse },
  { href: "/app/assist/settings", labelKey: "assist.nav.settings", permission: PERMISSIONS.assistManage },
  { href: "/app/assist/usage", labelKey: "assist.nav.usage", permission: PERMISSIONS.assistManage },
];

/** The assistance area's own navigation, under each page's heading. */
export function AssistNav() {
  const { t } = useI18n();
  const { me } = useAuth();
  const pathname = usePathname() ?? "";
  return (
    <nav className="subnav no-print" aria-label={t("assist.nav.label")}>
      {ASSIST_LINKS.filter((link) => hasPermission(me, link.permission)).map((link) => {
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

/** "Simulated draft": shown next to every draft while the simulated provider is in use. */
export function SimulatedLabel({ simulated = true }: { simulated?: boolean }) {
  const { t } = useI18n();
  if (!simulated) return null;
  return (
    <span data-testid="simulated-label" title={t("assist.simulated.hint")}>
      <Pill tone="info">
        <Sparkles size={12} aria-hidden="true" className="mr-1 inline" />
        {t("assist.simulated")}
      </Pill>
    </span>
  );
}

export function useAssistSettings() {
  return useApiData("assist:settings", () => assistApi.settings());
}

/** A notice when assistance (or this feature) is switched off, with a link to the settings for School Admins. */
export function AssistOffNotice({ settings, feature }: { settings: AssistSettings; feature?: keyof AssistSettings }) {
  const { t } = useI18n();
  const { me } = useAuth();
  const off = !settings.enabled || (feature !== undefined && settings[feature] === false);
  if (!off) return null;
  return (
    <div className="card flex flex-col gap-2" role="status" data-testid="assist-off">
      <p>{settings.enabled ? t("assist.off.feature") : t("assist.off.school")}</p>
      {hasPermission(me, PERMISSIONS.assistManage) ? (
        <Link className="btn btn-sm self-start" href="/app/assist/settings">
          {t("assist.off.openSettings")}
        </Link>
      ) : null}
    </div>
  );
}

/** The message for an assistance error: the switches and the monthly limit in plain words, else the server's. */
export function assistError(error: ApiError, t: Translate): string {
  if (error.type === ASSIST_PROBLEMS.limit) return t("assist.error.limit");
  if (error.type === ASSIST_PROBLEMS.off) return t("assist.off.school");
  if (error.type === ASSIST_PROBLEMS.featureOff) return t("assist.off.feature");
  return errorMessage(error, t);
}

const LEVEL_TONES: Record<RiskLevel, PillTone> = { HIGH: "bad", MEDIUM: "warn", LOW: "info" };

export function RiskPill({ level }: { level: RiskLevel }) {
  const { t } = useI18n();
  return (
    <Pill tone={LEVEL_TONES[level]} dot>
      {t(`assist.level.${level}` as MessageKey)}
    </Pill>
  );
}

const STATUS_TONES: Record<WatchStatus, PillTone> = { OPEN: "neutral", FOLLOWING: "accent", RESOLVED: "good" };

export function WatchStatusPill({ status }: { status: WatchStatus }) {
  const { t } = useI18n();
  return <Pill tone={STATUS_TONES[status]}>{t(`assist.status.${status}` as MessageKey)}</Pill>;
}

/** "2026-10" for a date in India: the current month for the usage page. */
export function currentMonth(now: Date = new Date()): string {
  const parts = new Intl.DateTimeFormat("en-CA", { timeZone: "Asia/Kolkata", year: "numeric", month: "2-digit" }).format(now);
  return parts.slice(0, 7);
}
