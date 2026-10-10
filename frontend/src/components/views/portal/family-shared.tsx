"use client";

import { useId, useState } from "react";
import { Pill, type PillTone } from "@/components/ui/pill";
import { api } from "@/lib/api";
import { pickChild, rememberChild, rememberedChild } from "@/lib/family";
import { formatPlainDate } from "@/lib/format";
import type { MessageKey, Translate } from "@/lib/i18n";
import { useI18n } from "@/lib/i18n";
import type { AttendanceStatus, Child, ChildAttendance, ChildLeave, ChildLeaveStatus } from "@/lib/types";
import { useApiData } from "@/lib/use-api-data";

/**
 * The parent's children and the one chosen, remembered on this device (an id in browser storage)
 * so every family page opens on the same child. `initialChildId` (from a link) wins.
 */
export function useFamilyChildren(initialChildId?: string) {
  const children = useApiData("me:children", api.myChildren);
  const [chosen, setChosen] = useState<string | null>(() => initialChildId || rememberedChild());
  const list = children.data ?? [];
  const child = pickChild(list, chosen);
  const choose = (childId: string) => {
    setChosen(childId);
    rememberChild(childId);
  };
  return { children, list, child, choose };
}

/** Chips to switch between children; nothing for a parent with one child. */
export function ChildSwitcher({
  list,
  value,
  onChange,
}: {
  list: readonly Child[];
  value: string | undefined;
  onChange: (childId: string) => void;
}) {
  const { t } = useI18n();
  const name = useId();
  if (list.length < 2) return null;
  return (
    <div className="seg self-start" role="radiogroup" aria-label={t("portal.switcher.label")} data-testid="child-switcher">
      {list.map((c) => (
        <label key={c.id}>
          <input type="radio" name={name} value={c.id} checked={c.id === value} onChange={() => onChange(c.id)} />
          {c.fullName}
        </label>
      ))}
    </div>
  );
}

/* ------------------------------------------------------------------ today */

export type TodayState =
  | { kind: "holiday"; name: string }
  | { kind: "marked"; status: AttendanceStatus; onLeave: boolean }
  | { kind: "leave"; halfDay: boolean }
  | { kind: "sunday" }
  | { kind: "unmarked" };

/** How today stands for a child: holiday, the register's mark, approved leave, Sunday or not marked yet. Pure. */
export function todayState(attendance: ChildAttendance): TodayState {
  if (attendance.todayHoliday) return { kind: "holiday", name: attendance.todayHoliday };
  if (attendance.todayStatus) {
    return { kind: "marked", status: attendance.todayStatus, onLeave: Boolean(attendance.todayLeave) };
  }
  if (attendance.todayLeave) return { kind: "leave", halfDay: attendance.todayLeave.halfDay };
  if (attendance.today && new Date(`${attendance.today}T00:00:00Z`).getUTCDay() === 0) return { kind: "sunday" };
  return { kind: "unmarked" };
}

const TODAY_STATUS: Record<AttendanceStatus, { key: MessageKey; tone: PillTone }> = {
  PRESENT: { key: "portal.today.PRESENT", tone: "good" },
  ABSENT: { key: "portal.today.ABSENT", tone: "bad" },
  LATE: { key: "portal.today.LATE", tone: "warn" },
  HALF_DAY: { key: "portal.today.HALF_DAY", tone: "warn" },
  LEAVE: { key: "portal.today.LEAVE", tone: "info" },
};

/** "Present today", "Holiday: Diwali", "On leave today"… as a pill. */
export function TodayPill({ attendance }: { attendance: ChildAttendance }) {
  const { t } = useI18n();
  const state = todayState(attendance);
  switch (state.kind) {
    case "holiday":
      return <Pill tone="info">{t("portal.today.holiday", { name: state.name })}</Pill>;
    case "marked":
      return (
        <Pill tone={TODAY_STATUS[state.status].tone} dot>
          {t(TODAY_STATUS[state.status].key)}
        </Pill>
      );
    case "leave":
      return <Pill tone="info">{state.halfDay ? t("portal.today.halfDayLeave") : t("portal.today.onLeave")}</Pill>;
    case "sunday":
      return <Pill>{t("portal.today.closed")}</Pill>;
    default:
      return <Pill>{t("portal.today.notMarked")}</Pill>;
  }
}

/* ------------------------------------------------------------------ leave */

export const LEAVE_TONE: Record<ChildLeaveStatus, PillTone> = {
  PENDING: "warn",
  APPROVED: "good",
  REJECTED: "bad",
  CANCELLED: "neutral",
};

export function LeaveStatusPill({ status }: { status: ChildLeaveStatus }) {
  const { t } = useI18n();
  return (
    <Pill tone={LEAVE_TONE[status]} dot>
      {t(`portal.leave.status.${status}` as MessageKey)}
    </Pill>
  );
}

/** "12 Oct 2026", "12 Oct 2026 – 14 Oct 2026" or "12 Oct 2026 (half day)". Pure. */
export function leaveDates(t: Translate, leave: Pick<ChildLeave, "fromDate" | "toDate" | "halfDay">, locale: string): string {
  const from = formatPlainDate(leave.fromDate, locale);
  if (leave.halfDay) return t("portal.leave.halfDayOn", { date: from });
  if (leave.fromDate === leave.toDate) return from;
  return `${from} – ${formatPlainDate(leave.toDate, locale)}`;
}

/** Calendar days from one date to another, both included. Pure. */
export function calendarDaysBetween(from: string, to: string): number {
  const start = Date.parse(`${from}T00:00:00Z`);
  const end = Date.parse(`${to}T00:00:00Z`);
  return Math.round((end - start) / 86_400_000) + 1;
}

/** "2026-10" moved by `delta` months. Pure. */
export function shiftMonth(month: string, delta: number): string {
  const [y, m] = month.split("-").map(Number);
  const date = new Date(Date.UTC(y, m - 1 + delta, 1));
  return `${date.getUTCFullYear()}-${String(date.getUTCMonth() + 1).padStart(2, "0")}`;
}
