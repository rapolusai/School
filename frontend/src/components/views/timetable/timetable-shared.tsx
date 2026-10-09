"use client";

import { Coffee } from "lucide-react";
import { useId, useState, useSyncExternalStore } from "react";
import { Pill } from "@/components/ui/pill";
import { useI18n, type MessageKey, type Translate } from "@/lib/i18n";
import type { BellSchedule, DayPeriod, WeekDay } from "@/lib/types";
import { gridRows, hasPeriod, periodsOf, workingDays } from "./timetable-logic";

export const DAY_LABEL: Record<WeekDay, MessageKey> = {
  MONDAY: "timetable.day.MONDAY",
  TUESDAY: "timetable.day.TUESDAY",
  WEDNESDAY: "timetable.day.WEDNESDAY",
  THURSDAY: "timetable.day.THURSDAY",
  FRIDAY: "timetable.day.FRIDAY",
  SATURDAY: "timetable.day.SATURDAY",
  SUNDAY: "timetable.day.SUNDAY",
};

export const DAY_SHORT: Record<WeekDay, MessageKey> = {
  MONDAY: "timetable.dayShort.MONDAY",
  TUESDAY: "timetable.dayShort.TUESDAY",
  WEDNESDAY: "timetable.dayShort.WEDNESDAY",
  THURSDAY: "timetable.dayShort.THURSDAY",
  FRIDAY: "timetable.dayShort.FRIDAY",
  SATURDAY: "timetable.dayShort.SATURDAY",
  SUNDAY: "timetable.dayShort.SUNDAY",
};

/** "Monday, period 3" for labels of cells. */
export function cellName(t: Translate, day: WeekDay, period: number): string {
  return t("timetable.cellName", { day: t(DAY_LABEL[day]), period });
}

const PHONE_QUERY = "(max-width: 639px)";

function subscribePhone(listener: () => void) {
  if (typeof window === "undefined" || !window.matchMedia) return () => {};
  const media = window.matchMedia(PHONE_QUERY);
  media.addEventListener("change", listener);
  return () => media.removeEventListener("change", listener);
}

/** True on phone-sized screens (one day at a time instead of the whole week). */
export function useIsPhone(): boolean {
  return useSyncExternalStore(
    subscribePhone,
    () => (typeof window !== "undefined" && window.matchMedia ? window.matchMedia(PHONE_QUERY).matches : false),
    () => false,
  );
}

/** The day to show first on a phone: today when the school works today, else the first working day. */
export function firstDay(bells: BellSchedule, today?: WeekDay): WeekDay {
  const days = workingDays(bells);
  return today && days.includes(today) ? today : (days[0] ?? "MONDAY");
}

/** Picks one working day (phones, and the editor's day filter). */
export function DayPicker({
  days,
  value,
  onChange,
  label,
}: {
  days: WeekDay[];
  value: WeekDay;
  onChange: (day: WeekDay) => void;
  label: string;
}) {
  const { t } = useI18n();
  const name = useId();
  return (
    <div className="seg tt-days" role="radiogroup" aria-label={label}>
      {days.map((day) => (
        <label key={day}>
          <input
            type="radio"
            name={name}
            value={day}
            checked={value === day}
            onChange={() => onChange(day)}
          />
          <span aria-hidden="true">{t(DAY_SHORT[day])}</span>
          <span className="sr-only">{t(DAY_LABEL[day])}</span>
        </label>
      ))}
    </div>
  );
}

function Time({ startsAt, endsAt }: { startsAt: string; endsAt: string }) {
  return (
    <span className="tt-time">
      {startsAt}–{endsAt}
    </span>
  );
}

/**
 * A week of periods: days across, periods down, on tablets and computers; one day at a time on
 * phones. `cell` renders what happens in a period of a day (null for a free period).
 */
export function WeekGrid({
  bells,
  cell,
  caption,
  today,
  testId,
}: {
  bells: BellSchedule;
  cell: (day: WeekDay, period: number) => React.ReactNode;
  caption: string;
  today?: WeekDay;
  testId?: string;
}) {
  const { t } = useI18n();
  const phone = useIsPhone();
  const days = workingDays(bells);
  const [chosen, setChosen] = useState<WeekDay | null>(null);
  const day = chosen && days.includes(chosen) ? chosen : firstDay(bells, today);

  if (days.length === 0 || bells.weekday.length === 0) {
    return <p className="empty">{t("timetable.noBells")}</p>;
  }

  if (phone) {
    return (
      <div className="flex flex-col gap-3" data-testid={testId}>
        <DayPicker days={days} value={day} onChange={setChosen} label={t("timetable.pickDay")} />
        <ol className="tt-daylist" aria-label={`${caption} · ${t(DAY_LABEL[day])}`}>
          {periodsOf(bells, day).map((p, i) =>
            p.breakTime || p.number === null ? (
              <li key={`b${i}`} className="tt-dl-break">
                <Coffee size={16} aria-hidden="true" />
                <span>{p.label}</span>
                <Time startsAt={p.startsAt} endsAt={p.endsAt} />
              </li>
            ) : (
              <li key={p.number} className="tt-dl-row">
                <div className="tt-dl-head">
                  <b>{p.label}</b>
                  <Time startsAt={p.startsAt} endsAt={p.endsAt} />
                </div>
                <div className="min-w-0">{cell(day, p.number) ?? <span className="tt-free">{t("timetable.free")}</span>}</div>
              </li>
            ),
          )}
        </ol>
      </div>
    );
  }

  const rows = gridRows(bells);
  return (
    <div className="table-wrap" data-testid={testId}>
      <table className="tt-table">
        <caption className="sr-only">{caption}</caption>
        <thead>
          <tr>
            <th scope="col" className="tt-corner">
              <span className="sr-only">{t("timetable.period")}</span>
            </th>
            {days.map((d) => (
              <th key={d} scope="col" className={d === today ? "tt-today" : undefined}>
                <abbr title={t(DAY_LABEL[d])}>{t(DAY_SHORT[d])}</abbr>
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {rows.map((row, i) =>
            row.kind === "break" ? (
              <tr key={`b${i}`} className="tt-break">
                <th scope="row">
                  <span className="tt-plabel">{row.label}</span>
                  <Time startsAt={row.startsAt} endsAt={row.endsAt} />
                </th>
                <td colSpan={days.length}>
                  <span className="inline-flex items-center gap-1.5">
                    <Coffee size={14} aria-hidden="true" />
                    {row.label}
                  </span>
                </td>
              </tr>
            ) : (
              <tr key={row.number}>
                <th scope="row">
                  <span className="tt-plabel">{row.label}</span>
                  <Time startsAt={row.startsAt} endsAt={row.endsAt} />
                </th>
                {days.map((d) =>
                  hasPeriod(bells, d, row.number) ? (
                    <td key={d} className={d === today ? "tt-today" : undefined}>
                      {cell(d, row.number)}
                    </td>
                  ) : (
                    <td key={d} className="tt-none" aria-label={t("timetable.noPeriod")}>
                      —
                    </td>
                  ),
                )}
              </tr>
            ),
          )}
        </tbody>
      </table>
      {bells.saturdaySchedule && days.includes("SATURDAY") ? (
        <p className="mt-2 text-[12.5px] text-ink-3">{t("timetable.saturdayTimes")}</p>
      ) : null}
    </div>
  );
}

const SLOT_COLOURS = 7;

/** A steady colour (0–6) for a subject, so it looks the same in every grid. Pure. */
export function slotColour(key: string): number {
  let hash = 0;
  for (let i = 0; i < key.length; i++) hash = (hash * 31 + key.charCodeAt(i)) >>> 0;
  return hash % SLOT_COLOURS;
}

/** A period's subject and who teaches it, inside a grid cell. */
export function SlotChip({
  title,
  sub,
  room,
  tone,
  colourKey,
}: {
  title: string;
  sub?: string | null;
  room?: string | null;
  tone?: "warn" | "bad" | "info";
  /** Colours the chip by subject (ignored when a tone is given). */
  colourKey?: string;
}) {
  const colour = tone ? ` tt-slot-${tone}` : colourKey ? ` tt-c${slotColour(colourKey)}` : "";
  return (
    <span className={`tt-slot${colour}`}>
      <b>{title}</b>
      {sub ? <span>{sub}</span> : null}
      {room ? <span className="tt-room">{room}</span> : null}
    </span>
  );
}

/** One day as a list: each period with its class (and substitutions), for dashboards and "My day". */
export function DayPeriods({
  periods,
  emptyLabel,
  showBreaks = true,
  testId,
}: {
  periods: DayPeriod[];
  emptyLabel: string;
  showBreaks?: boolean;
  testId?: string;
}) {
  const { t } = useI18n();
  const teaching = periods.filter((p) => !p.breakTime && p.entries.length > 0);
  if (teaching.length === 0) return <p className="text-sm text-ink-2">{emptyLabel}</p>;
  return (
    <ol className="tt-daylist" data-testid={testId}>
      {periods.map((p, i) => {
        if (p.breakTime || p.number === null) {
          return showBreaks ? (
            <li key={`b${i}`} className="tt-dl-break">
              <Coffee size={16} aria-hidden="true" />
              <span>{p.label}</span>
              <Time startsAt={p.startsAt} endsAt={p.endsAt} />
            </li>
          ) : null;
        }
        return (
          <li key={p.number} className="tt-dl-row">
            <div className="tt-dl-head">
              <b>{p.label}</b>
              <Time startsAt={p.startsAt} endsAt={p.endsAt} />
            </div>
            <div className="flex min-w-0 flex-col gap-1">
              {p.entries.length === 0 ? <span className="tt-free">{t("timetable.free")}</span> : null}
              {p.entries.map((e, j) => (
                <div key={j} className="flex flex-wrap items-center gap-x-2 gap-y-1">
                  <span className="min-w-0">
                    <b className="text-ink">{e.subjectName}</b>
                    <span className="text-ink-2">
                      {" · "}
                      {e.sectionLabel}
                      {e.room ? ` · ${e.room}` : ""}
                    </span>
                  </span>
                  {e.kind === "SUBSTITUTION" ? (
                    <Pill tone="info">{t("timetable.day.substituting", { name: e.absentTeacherName ?? "—" })}</Pill>
                  ) : e.kind === "COVERED" ? (
                    <Pill tone="warn">
                      {e.substituteName
                        ? t("timetable.day.coveredBy", { name: e.substituteName })
                        : t("timetable.day.notCovered")}
                    </Pill>
                  ) : e.teacherName ? (
                    <span className="text-[12.5px] text-ink-3">{e.teacherName}</span>
                  ) : null}
                </div>
              ))}
            </div>
          </li>
        );
      })}
    </ol>
  );
}
