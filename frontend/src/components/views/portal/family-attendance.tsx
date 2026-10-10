"use client";

import { ChevronLeft, ChevronRight } from "lucide-react";
import { useState } from "react";
import { ErrorState, LoadingRows, PageHead } from "@/components/ui/states";
import {
  CountsBar,
  formatPercent,
  MarkBadge,
  MarkLegend,
  STATUS_LABEL,
  statusMark,
} from "@/components/views/attendance/attendance-shared";
import { attendanceApi } from "@/lib/attendance-api";
import { useAuth } from "@/lib/auth";
import { isParent } from "@/lib/family";
import { classLabel, formatPlainDate, todayInIndia } from "@/lib/format";
import { localeFor, useI18n } from "@/lib/i18n";
import { portalApi } from "@/lib/portal-api";
import type { AttendanceStatus, ChildAttendance } from "@/lib/types";
import { useApiData } from "@/lib/use-api-data";
import { ChildSwitcher, shiftMonth, TodayPill, useFamilyChildren } from "./family-shared";

const WEEKDAYS = [1, 2, 3, 4, 5, 6, 0] as const;

export type MonthCell = {
  date: string;
  day: number;
  status: AttendanceStatus | null;
  holiday: string | null;
  leave: "full" | "half" | null;
  isToday: boolean;
};

/**
 * The month as calendar cells, Monday first: `blanks` empty cells before the 1st, then one cell per
 * day with its mark, whole-school holiday and approved leave. Pure, exported for tests.
 */
export function monthCells(data: ChildAttendance, today: string): { blanks: number; cells: MonthCell[] } {
  const [y, m] = data.month.split("-").map(Number);
  const first = new Date(Date.UTC(y, m - 1, 1));
  const length = new Date(Date.UTC(y, m, 0)).getUTCDate();
  const marks = new Map(data.days.map((d) => [d.date, d.status]));
  const holidays = new Map((data.holidays ?? []).map((h) => [h.date, h.title]));
  const leave = new Map((data.leaveDays ?? []).map((l) => [l.date, l.halfDay]));
  const cells: MonthCell[] = [];
  for (let day = 1; day <= length; day++) {
    const date = `${data.month}-${String(day).padStart(2, "0")}`;
    const half = leave.get(date);
    cells.push({
      date,
      day,
      status: marks.get(date) ?? null,
      holiday: holidays.get(date) ?? null,
      leave: half === undefined ? null : half ? "half" : "full",
      isToday: date === today,
    });
  }
  return { blanks: (first.getUTCDay() + 6) % 7, cells };
}

/** /app/family/attendance: a child's (or the student's own) month, day by day. */
export function FamilyAttendanceView({ initialChildId }: { initialChildId?: string }) {
  const { me } = useAuth();
  return isParent(me) ? <ParentAttendance initialChildId={initialChildId} /> : <StudentAttendance />;
}

function ParentAttendance({ initialChildId }: { initialChildId?: string }) {
  const { t } = useI18n();
  const { children, list, child, choose } = useFamilyChildren(initialChildId);
  return (
    <>
      <PageHead
        eyebrow={child ? [child.fullName, classLabel(child.className, child.sectionName)].filter(Boolean).join(" · ") : undefined}
        title={t("nav.attendance")}
      />
      {children.error && !children.data ? (
        <ErrorState error={children.error} onRetry={children.reload} />
      ) : !children.data ? (
        <LoadingRows rows={5} />
      ) : !child ? (
        <p className="empty">{t("children.empty")}</p>
      ) : (
        <>
          <ChildSwitcher list={list} value={child.id} onChange={choose} />
          <MonthView key={child.id} load={(month) => attendanceApi.child(child.id, month)} cacheKey={`attendance:child:${child.id}`} />
        </>
      )}
    </>
  );
}

function StudentAttendance() {
  const { t } = useI18n();
  return (
    <>
      <PageHead title={t("nav.attendance")} />
      <MonthView load={(month) => portalApi.myAttendance(month)} cacheKey="portal:attendance:me" />
    </>
  );
}

function MonthView({ load, cacheKey }: { load: (month: string) => Promise<ChildAttendance>; cacheKey: string }) {
  const { t, lang } = useI18n();
  const locale = localeFor(lang);
  const [today] = useState(() => todayInIndia());
  const current = today.slice(0, 7);
  const [month, setMonth] = useState(current);
  const data = useApiData(`${cacheKey}:${month}`, () => load(month));
  const view = data.data?.month === month ? data.data : undefined;
  const monthName = new Intl.DateTimeFormat(locale, { month: "long", year: "numeric", timeZone: "UTC" }).format(
    new Date(`${month}-01T00:00:00Z`),
  );
  const weekday = new Intl.DateTimeFormat(locale, { weekday: "short", timeZone: "UTC" });
  // 2026-06-01 is a Monday: the column headings start there.
  const headings = WEEKDAYS.map((d) => weekday.format(new Date(Date.UTC(2026, 5, d === 0 ? 7 : d))));

  return (
    <section className="card flex flex-col gap-3.5" aria-labelledby="family-month-title">
      <div className="flex items-center gap-2">
        <button
          type="button"
          className="btn btn-sm"
          onClick={() => setMonth((m) => shiftMonth(m, -1))}
          aria-label={t("portal.attendance.previous")}
        >
          <ChevronLeft size={16} aria-hidden="true" />
        </button>
        <h2 id="family-month-title" className="min-w-0 flex-1 text-center" aria-live="polite">
          {monthName}
        </h2>
        <button
          type="button"
          className="btn btn-sm"
          onClick={() => setMonth((m) => shiftMonth(m, 1))}
          disabled={month >= current}
          aria-label={t("portal.attendance.next")}
        >
          <ChevronRight size={16} aria-hidden="true" />
        </button>
      </div>

      {data.error?.status === 404 ? (
        <p className="empty">{t("children.notLinked")}</p>
      ) : data.error && !view ? (
        <ErrorState error={data.error} onRetry={data.reload} />
      ) : !view ? (
        <LoadingRows rows={5} />
      ) : (
        <>
          <div className="flex flex-wrap items-center justify-between gap-2">
            <div>
              <p className="font-display text-3xl font-bold num" data-testid="family-month-percent">
                {formatPercent(view.presentPercent)}
              </p>
              <p className="text-[13px] text-ink-2">
                {view.daysMarked > 0
                  ? t("attendance.child.days", { present: view.counts.present + view.counts.late, total: view.daysMarked })
                  : t("attendance.child.noneYet")}
              </p>
            </div>
            {month === current ? <TodayPill attendance={view} /> : null}
          </div>
          {view.daysMarked > 0 ? <CountsBar counts={view.counts} /> : null}
          <MonthGrid data={view} today={today} headings={headings} />
          <MarkLegend />
          <DayNotes data={view} locale={locale} />
        </>
      )}
    </section>
  );
}

function MonthGrid({ data, today, headings }: { data: ChildAttendance; today: string; headings: string[] }) {
  const { t, lang } = useI18n();
  const locale = localeFor(lang);
  const { blanks, cells } = monthCells(data, today);
  const describe = (cell: MonthCell) =>
    [
      formatPlainDate(cell.date, locale),
      cell.holiday ? t("portal.today.holiday", { name: cell.holiday }) : null,
      cell.status ? t(STATUS_LABEL[cell.status]) : null,
      cell.leave ? (cell.leave === "half" ? t("portal.attendance.halfDayLeave") : t("portal.attendance.leave")) : null,
    ]
      .filter(Boolean)
      .join(": ");
  return (
    <div data-testid="family-month-grid">
      <div className="grid grid-cols-7 gap-1 pb-1 text-center text-[11.5px] font-semibold uppercase text-ink-3" aria-hidden="true">
        {headings.map((h) => (
          <span key={h}>{h}</span>
        ))}
      </div>
      <ol className="grid grid-cols-7 gap-1" aria-label={t("portal.attendance.days")}>
        {Array.from({ length: blanks }, (_, i) => (
          <li key={`b${i}`} aria-hidden="true" />
        ))}
        {cells.map((cell) => (
          <li
            key={cell.date}
            aria-label={describe(cell)}
            title={describe(cell)}
            className="flex min-h-[46px] flex-col items-center justify-start gap-0.5 rounded-md border px-0.5 py-1 text-[12px]"
            style={{
              borderColor: cell.isToday ? "var(--accent)" : "var(--line)",
              background: cell.holiday ? "var(--surface-2)" : undefined,
            }}
            data-date={cell.date}
          >
            <span className="num text-ink-2" aria-hidden="true">
              {cell.day}
            </span>
            {cell.status ? (
              <span aria-hidden="true">
                <MarkBadge mark={statusMark(cell.status)} />
              </span>
            ) : cell.holiday ? (
              <span className="text-[10.5px] text-ink-3" aria-hidden="true">
                {t("portal.attendance.holidayShort")}
              </span>
            ) : cell.leave ? (
              <span className="text-[10.5px] font-semibold" style={{ color: "var(--accent)" }} aria-hidden="true">
                {cell.leave === "half" ? t("portal.attendance.halfLeaveShort") : t("portal.attendance.leaveShort")}
              </span>
            ) : null}
          </li>
        ))}
      </ol>
    </div>
  );
}

function DayNotes({ data, locale }: { data: ChildAttendance; locale: string }) {
  const { t } = useI18n();
  const absences = data.days.filter((d) => d.status === "ABSENT");
  const holidays = data.holidays ?? [];
  const leave = data.leaveDays ?? [];
  return (
    <dl className="kv text-[13.5px]">
      <dt>{t("portal.attendance.absences")}</dt>
      <dd data-testid="family-month-absences">
        {absences.length === 0 ? t("portal.attendance.none") : absences.map((d) => formatPlainDate(d.date, locale)).join(", ")}
      </dd>
      <dt>{t("portal.attendance.leaveDays")}</dt>
      <dd>
        {leave.length === 0
          ? t("portal.attendance.none")
          : leave
              .map((d) => formatPlainDate(d.date, locale) + (d.halfDay ? ` (${t("portal.attendance.halfDay")})` : ""))
              .join(", ")}
      </dd>
      <dt>{t("portal.attendance.holidays")}</dt>
      <dd>
        {holidays.length === 0
          ? t("portal.attendance.none")
          : holidays.map((h) => `${formatPlainDate(h.date, locale)} (${h.title})`).join(", ")}
      </dd>
    </dl>
  );
}
