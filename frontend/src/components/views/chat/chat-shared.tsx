"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import { useEffect, useEffectEvent } from "react";
import { AccessDenied } from "@/components/access";
import { Pill, type PillTone } from "@/components/ui/pill";
import { useAuth } from "@/lib/auth";
import { formatDateTime, formatPlainDate, todayInIndia, DISPLAY_TIME_ZONE } from "@/lib/format";
import type { MessageKey, Translate } from "@/lib/i18n";
import { useI18n } from "@/lib/i18n";
import { hasAnyPermission, hasPermission, PERMISSIONS } from "@/lib/permissions";
import type { ChatAvailability, ChatMessage, PtmAttendance, ReadOnlyReason, WeekDay } from "@/lib/types";
import { WEEK_DAYS } from "@/lib/types";

/* Shared parts of the messages and meeting screens (docs/api/phase-2-chat.md). */

/** How often an open chat screen asks for new messages. */
export const POLL_MS = 15_000;
/** The API's limits, mirrored for the forms. */
export const MAX_MESSAGE_LENGTH = 2000;
export const MAX_ATTACHMENTS = 3;
export const MAX_REASON_LENGTH = 500;
export const MAX_NOTE_LENGTH = 1000;
export const MAX_AWAY_NOTE_LENGTH = 300;
export const MAX_AWAY_DAYS = 90;

/** "09:30:00" or "09:30" → "09:30"; null → "". Pure. */
export function hhmm(value: string | null | undefined): string {
  return value ? value.slice(0, 5) : "";
}

/** "09:30–09:40". Pure. */
export function timeRange(from: string | null | undefined, to: string | null | undefined): string {
  return `${hhmm(from)}–${hhmm(to)}`;
}

/**
 * Calls `tick` every `ms` while `enabled` and the tab is visible: it stops when the tab is hidden and, when the tab
 * comes back, checks at once and starts again. No sockets: the chat screens simply ask.
 */
export function usePolling(tick: () => void, ms: number, enabled: boolean) {
  const run = useEffectEvent(() => tick());
  useEffect(() => {
    if (!enabled) return;
    let timer: number | undefined;
    const start = () => {
      if (timer === undefined) timer = window.setInterval(() => run(), ms);
    };
    const stop = () => {
      if (timer !== undefined) window.clearInterval(timer);
      timer = undefined;
    };
    const onVisibility = () => {
      if (document.visibilityState === "hidden") {
        stop();
      } else {
        run();
        start();
      }
    };
    if (document.visibilityState !== "hidden") start();
    document.addEventListener("visibilitychange", onVisibility);
    return () => {
      stop();
      document.removeEventListener("visibilitychange", onVisibility);
    };
  }, [enabled, ms]);
}

/**
 * The conversation's messages with the changes merged in: changed ones replaced (read, deleted), new ones added,
 * oldest first. Pure, exported for tests.
 */
export function mergeMessages(current: readonly ChatMessage[], incoming: readonly ChatMessage[]): ChatMessage[] {
  if (incoming.length === 0) return [...current];
  const byId = new Map(current.map((m) => [m.id, m]));
  for (const message of incoming) byId.set(message.id, message);
  return [...byId.values()].sort((a, b) => a.createdAt.localeCompare(b.createdAt) || a.id.localeCompare(b.id));
}

const TIME_ONLY: Intl.DateTimeFormatOptions = { hour: "numeric", minute: "2-digit", timeZone: DISPLAY_TIME_ZONE };

/** "10:32 am" for today, "9 Oct 2026, 10:32 am" otherwise (India time). Pure. */
export function messageTime(iso: string, locale: string, now: Date = new Date()): string {
  const at = new Date(iso);
  if (Number.isNaN(at.getTime())) return "";
  if (todayInIndia(at) === todayInIndia(now)) return new Intl.DateTimeFormat(locale, TIME_ONLY).format(at);
  return formatDateTime(at, locale);
}

const READ_ONLY_KEYS: Record<ReadOnlyReason, MessageKey> = {
  CLOSED: "chat.readOnly.CLOSED",
  ERASED: "chat.readOnly.ERASED",
  STUDENT_LEFT: "chat.readOnly.STUDENT_LEFT",
  STAFF_LEFT: "chat.readOnly.STAFF_LEFT",
  PARENT_UNLINKED: "chat.readOnly.PARENT_UNLINKED",
};

/** Why a conversation can only be read, in a sentence. */
export function readOnlyText(t: Translate, reason: ReadOnlyReason | null): string {
  return t(reason ? READ_ONLY_KEYS[reason] : "chat.readOnly.CLOSED");
}

/** "Mon–Sat" for consecutive days, "Mon, Wed, Fri" otherwise. Pure. */
export function daysText(t: Translate, days: readonly WeekDay[]): string {
  const sorted = WEEK_DAYS.filter((d) => days.includes(d));
  if (sorted.length === 0) return "";
  const short = (d: WeekDay) => t(`chat.day.${d}` as MessageKey);
  const first = WEEK_DAYS.indexOf(sorted[0]);
  const consecutive = sorted.every((d, i) => WEEK_DAYS.indexOf(d) === first + i);
  if (consecutive && sorted.length > 2) return `${short(sorted[0])}–${short(sorted[sorted.length - 1])}`;
  return sorted.map(short).join(", ");
}

/**
 * What a parent sees about the teacher's hours: away until a day (with the teacher's note), or outside school hours
 * with when the teacher is back; null while the teacher is available. Pure.
 */
export function availabilityNotice(t: Translate, availability: ChatAvailability | null, locale: string): string | null {
  if (!availability) return null;
  if (availability.awayUntil) {
    const away = t("chat.notice.away", { date: formatPlainDate(availability.awayUntil, locale) });
    return availability.awayNote ? `${away} ${availability.awayNote}` : away;
  }
  if (availability.openNow) return null;
  const hours = t("chat.notice.hours", {
    days: daysText(t, availability.days),
    from: hhmm(availability.from),
    to: hhmm(availability.to),
  });
  return availability.nextOpenAt
    ? `${hours} ${t("chat.notice.next", { when: formatDateTime(availability.nextOpenAt, locale) })}`
    : hours;
}

export function ReadOnlyPill({ reason }: { reason: ReadOnlyReason | null }) {
  const { t } = useI18n();
  return <Pill tone={reason === "CLOSED" ? "warn" : "neutral"}>{t(reason === "CLOSED" ? "chat.closed" : "chat.readOnly")}</Pill>;
}

export const ATTENDANCE_TONE: Record<PtmAttendance, PillTone> = { ATTENDED: "good", NO_SHOW: "bad" };

export function AttendancePill({ attendance }: { attendance: PtmAttendance | null }) {
  const { t } = useI18n();
  if (!attendance) return <Pill>{t("ptm.attendance.none")}</Pill>;
  return (
    <Pill tone={ATTENDANCE_TONE[attendance]} dot>
      {t(`ptm.attendance.${attendance}` as MessageKey)}
    </Pill>
  );
}

/** Renders children only when the signed-in user holds one of `permissions`. */
export function RequireAnyOf({ permissions, children }: { permissions: readonly string[]; children: React.ReactNode }) {
  const { me } = useAuth();
  if (!hasAnyPermission(me, permissions)) return <AccessDenied />;
  return <>{children}</>;
}

/** The sub-pages of /app/chat a member of staff may open. */
export function ChatNav() {
  const { t } = useI18n();
  const { me } = useAuth();
  const pathname = usePathname() ?? "";
  const links = [
    { href: "/app/chat", label: t("chat.nav.conversations"), show: hasPermission(me, PERMISSIONS.chatUse) },
    { href: "/app/chat/oversight", label: t("chat.nav.oversight"), show: hasPermission(me, PERMISSIONS.chatOversee) },
    {
      href: "/app/chat/settings",
      label: t("chat.nav.settings"),
      show: hasAnyPermission(me, [PERMISSIONS.chatUse, PERMISSIONS.chatOversee]),
    },
  ].filter((link) => link.show);
  if (links.length < 2) return null;
  return (
    <nav className="subnav no-print" aria-label={t("chat.nav.label")}>
      {links.map((link) => {
        const active = link.href === "/app/chat" ? pathname === link.href : pathname.startsWith(link.href);
        return (
          <Link key={link.href} href={link.href} aria-current={active ? "page" : undefined}>
            {link.label}
          </Link>
        );
      })}
    </nav>
  );
}
