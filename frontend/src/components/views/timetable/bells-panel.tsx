"use client";

import { Coffee, Plus, Trash2 } from "lucide-react";
import { useState } from "react";
import { ErrorState, FormAlert, LoadingRows } from "@/components/ui/states";
import { useToast } from "@/components/ui/toast";
import { toApiError } from "@/lib/api";
import { errorMessage } from "@/lib/error-message";
import { useI18n, type MessageKey } from "@/lib/i18n";
import { timetableApi } from "@/lib/timetable-api";
import { WEEK_DAYS, type BellSchedule, type PeriodRow, type PeriodView, type WeekDay } from "@/lib/types";
import { useApiData } from "@/lib/use-api-data";
import { bellRowProblems, minutes, type RowProblem } from "./timetable-logic";
import { DAY_LABEL } from "./timetable-shared";

const ROW_PROBLEM: Record<RowProblem, MessageKey> = {
  label: "timetable.bells.v.label",
  time: "timetable.bells.v.time",
  order: "timetable.bells.v.order",
  overlap: "timetable.bells.v.overlap",
};

const toRow = (p: PeriodView): PeriodRow => ({
  label: p.label,
  startsAt: p.startsAt,
  endsAt: p.endsAt,
  breakTime: p.breakTime,
});

function addMinutes(time: string, add: number): string {
  const start = minutes(time);
  const total = Number.isNaN(start) ? 8 * 60 : Math.min(start + add, 23 * 60 + 59);
  return `${String(Math.floor(total / 60)).padStart(2, "0")}:${String(total % 60).padStart(2, "0")}`;
}

/** Errors the API returned for one schedule's rows ("weekday[2].startsAt"), by row index. */
function serverRowErrors(errors: Record<string, string>, list: "weekday" | "saturday"): Record<number, string> {
  const out: Record<number, string> = {};
  for (const [key, message] of Object.entries(errors)) {
    const match = new RegExp(`^${list}\\[(\\d+)\\]`).exec(key);
    if (match) out[Number(match[1])] ??= message;
  }
  return out;
}

function RowsEditor({
  name,
  rows,
  onChange,
  errors,
}: {
  name: "weekday" | "saturday";
  rows: PeriodRow[];
  onChange: (rows: PeriodRow[]) => void;
  errors: Record<number, string>;
}) {
  const { t } = useI18n();
  let number = 0;
  const update = (i: number, patch: Partial<PeriodRow>) => onChange(rows.map((r, j) => (j === i ? { ...r, ...patch } : r)));
  const add = (breakTime: boolean) => {
    const last = rows[rows.length - 1];
    const teaching = rows.filter((r) => !r.breakTime).length;
    const startsAt = last ? last.endsAt : "08:00";
    onChange([
      ...rows,
      {
        label: breakTime ? t("timetable.bells.break") : t("timetable.bells.periodN", { n: teaching + 1 }),
        startsAt,
        endsAt: addMinutes(startsAt, breakTime ? 15 : 40),
        breakTime,
      },
    ]);
  };
  return (
    <div className="flex flex-col gap-2">
      <ol className="bell-rows" aria-label={t(name === "weekday" ? "timetable.bells.weekday" : "timetable.bells.saturday")}>
        {rows.map((row, i) => {
          if (!row.breakTime) number++;
          const label = row.breakTime ? t("timetable.bells.break") : t("timetable.bells.periodN", { n: number });
          return (
            <li key={i} className={`bell-row${row.breakTime ? " bell-row-break" : ""}`} data-testid={`${name}-row`}>
              <span className="bell-no" aria-hidden="true">
                {row.breakTime ? <Coffee size={16} /> : number}
              </span>
              <input
                className="input bell-label"
                value={row.label}
                maxLength={40}
                aria-label={t("timetable.bells.labelFor", { row: label })}
                aria-invalid={errors[i] ? true : undefined}
                onChange={(e) => update(i, { label: e.target.value })}
              />
              <input
                type="time"
                className="input bell-time"
                value={row.startsAt}
                aria-label={t("timetable.bells.startsFor", { row: label })}
                onChange={(e) => update(i, { startsAt: e.target.value })}
              />
              <input
                type="time"
                className="input bell-time"
                value={row.endsAt}
                aria-label={t("timetable.bells.endsFor", { row: label })}
                onChange={(e) => update(i, { endsAt: e.target.value })}
              />
              <label className="bell-check">
                <input type="checkbox" checked={row.breakTime} onChange={(e) => update(i, { breakTime: e.target.checked })} />
                {t("timetable.bells.isBreak")}
              </label>
              <button
                type="button"
                className="iconbtn"
                aria-label={t("timetable.bells.removeRow", { row: label })}
                onClick={() => onChange(rows.filter((_, j) => j !== i))}
              >
                <Trash2 size={18} aria-hidden="true" />
              </button>
              {errors[i] ? <p className="field-error bell-error">{errors[i]}</p> : null}
            </li>
          );
        })}
      </ol>
      <div className="flex flex-wrap gap-2">
        <button type="button" className="btn btn-sm" onClick={() => add(false)}>
          <Plus size={16} aria-hidden="true" />
          {t("timetable.bells.addPeriod")}
        </button>
        <button type="button" className="btn btn-sm" onClick={() => add(true)}>
          <Coffee size={16} aria-hidden="true" />
          {t("timetable.bells.addBreak")}
        </button>
      </div>
    </div>
  );
}

function BellsEditor({ bells, onSaved }: { bells: BellSchedule; onSaved: (bells: BellSchedule) => void }) {
  const { t } = useI18n();
  const { toast } = useToast();
  const [days, setDays] = useState<WeekDay[]>(bells.workingDays);
  const [saturdaySchedule, setSaturdaySchedule] = useState(bells.saturdaySchedule);
  const [weekday, setWeekday] = useState<PeriodRow[]>(bells.weekday.map(toRow));
  const [saturday, setSaturday] = useState<PeriodRow[]>(bells.saturday.map(toRow));
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [problem, setProblem] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  const worksSaturday = days.includes("SATURDAY");

  const clientErrors = (list: PeriodRow[]): Record<number, string> =>
    Object.fromEntries(Object.entries(bellRowProblems(list)).map(([i, p]) => [i, t(ROW_PROBLEM[p])]));

  // Client checks and the API's answers both arrive keyed "weekday[2]…" / "saturday[0]…".
  const weekdayErrors = serverRowErrors(errors, "weekday");
  const saturdayErrors = serverRowErrors(errors, "saturday");

  const onSubmit = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (saving) return;
    const local: Record<string, string> = {};
    for (const [i, message] of Object.entries(clientErrors(weekday))) local[`weekday[${i}]`] = message;
    if (saturdaySchedule && worksSaturday) {
      for (const [i, message] of Object.entries(clientErrors(saturday))) local[`saturday[${i}]`] = message;
    }
    if (days.length === 0) local.workingDays = t("timetable.bells.v.days");
    if (!weekday.some((r) => !r.breakTime)) local.weekday = t("timetable.bells.v.noPeriods");
    if (Object.keys(local).length > 0) {
      setErrors(local);
      setProblem(t("validation.fixErrors"));
      return;
    }
    setSaving(true);
    setProblem(null);
    try {
      const saved = await timetableApi.saveBellSchedule({
        workingDays: WEEK_DAYS.filter((d) => days.includes(d)),
        saturdaySchedule: saturdaySchedule && worksSaturday,
        weekday,
        saturday: saturdaySchedule && worksSaturday ? saturday : [],
      });
      setErrors({});
      toast(t("timetable.bells.saved"));
      onSaved(saved);
    } catch (caught) {
      const error = toApiError(caught);
      setErrors(error.errors ?? {});
      setProblem(errorMessage(error, t));
    } finally {
      setSaving(false);
    }
  };

  return (
    <form method="post" onSubmit={onSubmit} noValidate className="flex flex-col gap-4" aria-label={t("timetable.bells.title")}>
      <fieldset className="fieldset">
        <legend>{t("timetable.bells.days")}</legend>
        <div className="flex flex-wrap gap-2">
          {WEEK_DAYS.map((day) => (
            <label key={day} className="check">
              <input
                type="checkbox"
                name="workingDays"
                value={day}
                checked={days.includes(day)}
                onChange={(e) => {
                  setErrors({});
                  setDays((prev) => (e.target.checked ? [...prev, day] : prev.filter((d) => d !== day)));
                }}
              />
              {t(DAY_LABEL[day])}
            </label>
          ))}
        </div>
        {errors.workingDays ? <p className="field-error">{errors.workingDays}</p> : null}
      </fieldset>

      <fieldset className="fieldset">
        <legend>{t(worksSaturday && saturdaySchedule ? "timetable.bells.weekdayOnly" : "timetable.bells.weekday")}</legend>
        <RowsEditor name="weekday" rows={weekday} onChange={setWeekday} errors={weekdayErrors} />
        {errors.weekday ? <p className="field-error">{errors.weekday}</p> : null}
      </fieldset>

      {worksSaturday ? (
        <fieldset className="fieldset">
          <legend>{t("timetable.bells.saturday")}</legend>
          <label className="check self-start">
            <input
              type="checkbox"
              name="saturdaySchedule"
              checked={saturdaySchedule}
              onChange={(e) => {
                setSaturdaySchedule(e.target.checked);
                if (e.target.checked && saturday.length === 0) setSaturday(weekday.slice(0, 6));
              }}
            />
            {t("timetable.bells.ownSaturday")}
          </label>
          {saturdaySchedule ? (
            <RowsEditor name="saturday" rows={saturday} onChange={setSaturday} errors={saturdayErrors} />
          ) : (
            <p className="text-[13px] text-ink-3">{t("timetable.bells.sameSaturday")}</p>
          )}
          {errors.saturday || errors.saturdaySchedule ? (
            <p className="field-error">{errors.saturday ?? errors.saturdaySchedule}</p>
          ) : null}
        </fieldset>
      ) : null}

      <div className="att-savebar" style={{ marginTop: 0 }}>
        <FormAlert message={problem} />
        <p className="text-[13px] text-ink-3">{t("timetable.bells.hint")}</p>
        <button type="submit" className="btn btn-primary" disabled={saving}>
          {saving ? t("common.saving") : t("timetable.bells.save")}
        </button>
      </div>
    </form>
  );
}

function BellsTable({ bells }: { bells: BellSchedule }) {
  const { t } = useI18n();
  const lists: { key: string; title: MessageKey; rows: PeriodView[] }[] = [
    {
      key: "weekday",
      title: bells.saturdaySchedule ? "timetable.bells.weekdayOnly" : "timetable.bells.weekday",
      rows: bells.weekday,
    },
  ];
  if (bells.saturdaySchedule) lists.push({ key: "saturday", title: "timetable.bells.saturday", rows: bells.saturday });
  return (
    <div className="flex flex-col gap-4">
      <p className="text-[13.5px] text-ink-2">
        {t("timetable.bells.workingDays", { days: bells.workingDays.map((d) => t(DAY_LABEL[d])).join(", ") })}
      </p>
      <div className="grid gap-4 md:grid-cols-2">
        {lists.map((list) => (
          <section key={list.key} aria-label={t(list.title)}>
            <h3 className="mb-1 text-[15px] font-semibold">{t(list.title)}</h3>
            <table className="table">
              <tbody>
                {list.rows.map((p, i) => (
                  <tr key={i} className={p.breakTime ? "text-ink-3" : undefined}>
                    <td>{p.label}</td>
                    <td className="r num">
                      {p.startsAt}–{p.endsAt}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </section>
        ))}
      </div>
    </div>
  );
}

/** The bell schedule: periods and breaks of the day, and an optional Saturday schedule. */
export function BellsPanel({ canManage }: { canManage: boolean }) {
  const { t } = useI18n();
  const bells = useApiData("timetable:bells", timetableApi.bellSchedule);
  const [saved, setSaved] = useState<{ bells: BellSchedule; version: number } | null>(null);
  const view = saved?.bells ?? bells.data;
  if (bells.error && !view) return <ErrorState error={bells.error} onRetry={bells.reload} />;
  if (!view) return <LoadingRows rows={6} />;
  if (!canManage) {
    return view.weekday.length === 0 ? <p className="empty">{t("timetable.noBells")}</p> : <BellsTable bells={view} />;
  }
  return (
    <BellsEditor
      key={saved?.version ?? 0}
      bells={view}
      onSaved={(next) => setSaved((prev) => ({ bells: next, version: (prev?.version ?? 0) + 1 }))}
    />
  );
}
