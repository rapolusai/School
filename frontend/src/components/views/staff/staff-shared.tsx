"use client";

import { useId, useRef } from "react";
import type { PillTone } from "@/components/ui/pill";
import { DISPLAY_TIME_ZONE } from "@/lib/format";
import { plural, translateOr, useI18n, type MessageKey, type Translate } from "@/lib/i18n";
import {
  STAFF_MARK_OF,
  type LeaveStatus,
  type StaffDayCounts,
  type StaffDayStatus,
  type StaffMark,
} from "@/lib/types";

export const STAFF_DAY_LABEL: Record<StaffDayStatus, MessageKey> = {
  PRESENT: "staff.day.PRESENT",
  ABSENT: "staff.day.ABSENT",
  HALF_DAY: "staff.day.HALF_DAY",
  ON_LEAVE: "staff.day.ON_LEAVE",
};

const MARK_LABEL: Record<StaffMark, MessageKey> = {
  P: "staff.day.PRESENT",
  A: "staff.day.ABSENT",
  H: "staff.day.HALF_DAY",
  L: "staff.day.ON_LEAVE",
};

/** Staff "on leave" (L) uses the leave colour, which the student register calls E. */
const MARK_CLASS: Record<StaffMark, string> = { P: "mark-P", A: "mark-A", H: "mark-H", L: "mark-E" };

export function staffMark(status: StaffDayStatus): StaffMark {
  return STAFF_MARK_OF[status];
}

export function staffDayTone(status: StaffDayStatus | null): PillTone {
  if (status === "PRESENT") return "good";
  if (status === "ABSENT") return "bad";
  if (status === "HALF_DAY") return "info";
  if (status === "ON_LEAVE") return "accent";
  return "neutral";
}

export function leaveStatusTone(status: LeaveStatus): PillTone {
  if (status === "APPROVED") return "good";
  if (status === "PENDING") return "warn";
  if (status === "REJECTED") return "bad";
  return "neutral";
}

/** "8:31 am" in India time. Returns "" for missing input. */
export function formatClock(iso: string | null | undefined, locale = "en-IN"): string {
  if (!iso) return "";
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) return "";
  return new Intl.DateTimeFormat(locale, { hour: "numeric", minute: "2-digit", timeZone: DISPLAY_TIME_ZONE }).format(
    date,
  );
}

/** 1 → "1 day", 2.5 → "2.5 days". */
export function formatDays(t: Translate, days: number): string {
  return plural(t, "staff.days", days, { count: formatNumber(days) });
}

/** 12 → "12", 1.5 → "1.5", null → "—". */
export function formatNumber(value: number | null | undefined): string {
  if (value === null || value === undefined || Number.isNaN(value)) return "—";
  return Number.isInteger(value) ? String(value) : value.toFixed(1);
}

export function employmentLabel(t: Translate, type: string | null | undefined): string {
  return type ? translateOr(t, `staff.employment.${type}`, type) : "—";
}

/** A coloured P / A / H / L square. */
export function StaffMarkBadge({ mark }: { mark: StaffMark }) {
  const { t } = useI18n();
  return (
    <span className={`mark ${MARK_CLASS[mark]}`} title={t(MARK_LABEL[mark])}>
      {mark}
    </span>
  );
}

export function StaffMarkLegend() {
  const { t } = useI18n();
  return (
    <ul className="legend" aria-label={t("staff.legend")}>
      {(["P", "H", "A", "L"] as const).map((mark) => (
        <li key={mark}>
          <span className={`mark ${MARK_CLASS[mark]}`} aria-hidden="true">
            {mark}
          </span>
          {t(MARK_LABEL[mark])}
        </li>
      ))}
    </ul>
  );
}

/** "Present 8 · Half day 1 · Absent 0 · On leave 2 · Not marked 3" */
export function StaffCountsBar({
  counts,
  notMarked,
  testId,
}: {
  counts: StaffDayCounts;
  notMarked?: number;
  testId?: string;
}) {
  const { t } = useI18n();
  const items = [
    { key: "P", label: t("staff.day.PRESENT"), value: counts.present },
    { key: "H", label: t("staff.day.HALF_DAY"), value: counts.halfDay },
    { key: "A", label: t("staff.day.ABSENT"), value: counts.absent },
    { key: "L", label: t("staff.day.ON_LEAVE"), value: counts.onLeave },
  ];
  if (notMarked !== undefined) items.push({ key: "U", label: t("staff.notMarked"), value: notMarked });
  return (
    <dl className="att-counts" aria-live="polite" data-testid={testId}>
      {items.map((item) => (
        <div key={item.key} className={item.key === "U" && item.value > 0 ? "att-count-todo" : undefined}>
          <dt>{item.label}</dt>
          <dd className="num">{item.value}</dd>
        </div>
      ))}
    </dl>
  );
}

/** Accessible tabs (arrow keys move between them), as on the attendance reports page. */
export function Tabs<T extends string>({
  tabs,
  value,
  onChange,
  label,
  children,
}: {
  tabs: { key: T; label: string }[];
  value: T;
  onChange: (key: T) => void;
  label: string;
  children: React.ReactNode;
}) {
  const baseId = useId();
  const refs = useRef<Partial<Record<T, HTMLButtonElement | null>>>({});
  const keys = tabs.map((tab) => tab.key);
  const onKeyDown = (event: React.KeyboardEvent<HTMLDivElement>) => {
    const index = keys.indexOf(value);
    let next: T | undefined;
    if (event.key === "ArrowRight") next = keys[(index + 1) % keys.length];
    else if (event.key === "ArrowLeft") next = keys[(index - 1 + keys.length) % keys.length];
    if (!next) return;
    event.preventDefault();
    onChange(next);
    refs.current[next]?.focus();
  };
  return (
    <>
      <div role="tablist" aria-label={label} className="tabs" onKeyDown={onKeyDown}>
        {tabs.map((tab) => (
          <button
            key={tab.key}
            ref={(el) => {
              refs.current[tab.key] = el;
            }}
            type="button"
            role="tab"
            id={`${baseId}-tab-${tab.key}`}
            aria-selected={value === tab.key}
            aria-controls={`${baseId}-panel`}
            tabIndex={value === tab.key ? 0 : -1}
            onClick={() => onChange(tab.key)}
          >
            {tab.label}
          </button>
        ))}
      </div>
      <div role="tabpanel" id={`${baseId}-panel`} aria-labelledby={`${baseId}-tab-${value}`} className="flex flex-col gap-3.5">
        {children}
      </div>
    </>
  );
}

/** Calendar days from `from` to `to` inclusive ("YYYY-MM-DD"); 0 when either is missing. */
export function calendarDays(from: string, to: string): number {
  const a = Date.parse(`${from}T00:00:00Z`);
  const b = Date.parse(`${to}T00:00:00Z`);
  if (Number.isNaN(a) || Number.isNaN(b)) return 0;
  return Math.round((b - a) / 86_400_000) + 1;
}

/** One date, or "first – last", each written by `format`. */
export function dateRange(from: string, to: string, format: (date: string) => string): string {
  return from === to ? format(from) : `${format(from)} – ${format(to)}`;
}
