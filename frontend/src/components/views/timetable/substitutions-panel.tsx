"use client";

import { Printer, Trash2, UserX } from "lucide-react";
import Link from "next/link";
import { useState } from "react";
import { ConfirmDialog } from "@/components/ui/confirm-dialog";
import { Pill } from "@/components/ui/pill";
import { ErrorState, FormAlert, LoadingRows } from "@/components/ui/states";
import { useToast } from "@/components/ui/toast";
import { toApiError } from "@/lib/api";
import { errorMessage } from "@/lib/error-message";
import { formatPlainDate, todayInIndia } from "@/lib/format";
import { localeFor, plural, useI18n } from "@/lib/i18n";
import { timetableApi } from "@/lib/timetable-api";
import type { AbsenceView, AffectedPeriod, SubstitutionDay } from "@/lib/types";
import { useApiData } from "@/lib/use-api-data";
import { DAY_LABEL } from "./timetable-shared";

const PLAIN_DATE = /^\d{4}-\d{2}-\d{2}$/;

function CoverRow({
  date,
  period,
  canManage,
  onDay,
}: {
  date: string;
  period: AffectedPeriod;
  canManage: boolean;
  onDay: (day: SubstitutionDay) => void;
}) {
  const { t } = useI18n();
  const { toast } = useToast();
  const [teacherId, setTeacherId] = useState(period.suggestions[0]?.id ?? "");
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const chosen = period.suggestions.some((s) => s.id === teacherId) ? teacherId : (period.suggestions[0]?.id ?? "");

  const run = async (action: () => Promise<SubstitutionDay>, message: string) => {
    if (busy) return;
    setBusy(true);
    setError(null);
    try {
      onDay(await action());
      toast(message);
    } catch (caught) {
      const failure = toApiError(caught);
      setError(failure.errors ? (Object.values(failure.errors)[0] ?? errorMessage(failure, t)) : errorMessage(failure, t));
    } finally {
      setBusy(false);
    }
  };

  return (
    <li className="sub-row" data-testid="cover-row">
      <div className="sub-when">
        <b>{period.label}</b>
        <span className="tt-time">
          {period.startsAt}–{period.endsAt}
        </span>
      </div>
      <div className="min-w-0 flex-1">
        <b className="block">
          {period.sectionLabel} · {period.subjectName}
        </b>
        {period.room ? <span className="text-[12.5px] text-ink-3">{period.room}</span> : null}
      </div>
      {period.substitute ? (
        <div className="sub-cover">
          <Pill tone="good" dot>
            {t("timetable.subs.coveredBy", { name: period.substitute.teacherName })}
          </Pill>
          {canManage ? (
            <button
              type="button"
              className="iconbtn"
              disabled={busy}
              aria-label={t("timetable.subs.removeCover", { name: period.substitute.teacherName, period: period.label })}
              onClick={() =>
                run(
                  () => timetableApi.removeSubstitution(period.substitute?.id ?? ""),
                  t("timetable.subs.coverRemoved"),
                )
              }
            >
              <Trash2 size={18} aria-hidden="true" />
            </button>
          ) : null}
        </div>
      ) : canManage ? (
        <form
          method="post"
          className="sub-cover"
          onSubmit={(e) => {
            e.preventDefault();
            if (!chosen) return;
            const name = period.suggestions.find((s) => s.id === chosen)?.name ?? "";
            void run(
              () => timetableApi.assignSubstitute({ date, sectionId: period.sectionId, period: period.period, teacherId: chosen }),
              t("timetable.subs.assigned", { name, period: period.label }),
            );
          }}
        >
          {period.suggestions.length === 0 ? (
            <Pill tone="bad">{t("timetable.subs.nobodyFree")}</Pill>
          ) : (
            <>
              <select
                className="input"
                aria-label={t("timetable.subs.pickFor", { period: period.label, section: period.sectionLabel })}
                value={chosen}
                onChange={(e) => setTeacherId(e.target.value)}
              >
                {period.suggestions.map((s) => (
                  <option key={s.id} value={s.id}>
                    {`${s.name}${s.teachesSubject ? ` · ${t("timetable.subs.teachesSubject")}` : ""} · ${plural(t, "timetable.free.periods", s.periodsThatDay)}`}
                  </option>
                ))}
              </select>
              <button type="submit" className="btn btn-primary btn-sm" disabled={busy}>
                {t("timetable.subs.assign")}
              </button>
            </>
          )}
        </form>
      ) : (
        <Pill tone="warn">{t("timetable.day.notCovered")}</Pill>
      )}
      {error ? <p className="field-error w-full">{error}</p> : null}
    </li>
  );
}

function AbsenceForm({ day, onDay }: { day: SubstitutionDay; onDay: (day: SubstitutionDay) => void }) {
  const { t } = useI18n();
  const { toast } = useToast();
  const away = new Set(day.absences.map((a) => a.teacherId));
  const options = day.teachers.filter((x) => !away.has(x.id));
  const [teacherId, setTeacherId] = useState("");
  const [reason, setReason] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const submit = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (busy) return;
    if (!teacherId) {
      setError(t("timetable.subs.v.teacher"));
      return;
    }
    setBusy(true);
    setError(null);
    try {
      const next = await timetableApi.recordAbsence({ date: day.date, teacherId, reason: reason.trim() || null });
      const name = day.teachers.find((x) => x.id === teacherId)?.name ?? "";
      setTeacherId("");
      setReason("");
      toast(t("timetable.subs.absenceSaved", { name }));
      onDay(next);
    } catch (caught) {
      const failure = toApiError(caught);
      setError(failure.errors ? (Object.values(failure.errors)[0] ?? errorMessage(failure, t)) : errorMessage(failure, t));
    } finally {
      setBusy(false);
    }
  };

  return (
    <form method="post" onSubmit={submit} noValidate className="card flex flex-col gap-3" aria-labelledby="tt-absent">
      <h2 id="tt-absent">{t("timetable.subs.markAbsent")}</h2>
      <div className="toolbar" style={{ marginBottom: 0 }}>
        <label className="field min-w-0 flex-1 sm:max-w-xs">
          <span className="field-label">{t("timetable.teacher")}</span>
          <select className="input" name="teacherId" value={teacherId} onChange={(e) => setTeacherId(e.target.value)}>
            <option value="">{t("common.choose")}</option>
            {options.map((x) => (
              <option key={x.id} value={x.id}>
                {x.name}
              </option>
            ))}
          </select>
        </label>
        <label className="field min-w-0 flex-1 sm:max-w-sm">
          <span className="field-label">{t("timetable.subs.reason")}</span>
          <input
            className="input"
            name="reason"
            maxLength={200}
            value={reason}
            placeholder={t("timetable.subs.reasonHint")}
            onChange={(e) => setReason(e.target.value)}
          />
        </label>
        <button type="submit" className="btn btn-primary self-end" disabled={busy}>
          <UserX size={18} aria-hidden="true" />
          {t("timetable.subs.markAbsentButton")}
        </button>
      </div>
      <FormAlert message={error} />
    </form>
  );
}

/** Absent teachers on a day, the periods they would teach, and who covers each one. */
export function SubstitutionsPanel({ canManage, initialDate }: { canManage: boolean; initialDate?: string }) {
  const { t, lang } = useI18n();
  const { toast } = useToast();
  const locale = localeFor(lang);
  const [date, setDate] = useState(() => (initialDate && PLAIN_DATE.test(initialDate) ? initialDate : todayInIndia()));
  const [fresh, setFresh] = useState<SubstitutionDay | null>(null);
  const [removing, setRemoving] = useState<AbsenceView | null>(null);
  const loaded = useApiData(`timetable:subs:${date}`, () => timetableApi.substitutions(date));
  const day = fresh?.date === date ? fresh : loaded.data?.date === date ? loaded.data : undefined;

  return (
    <div className="flex flex-col gap-3.5">
      <div className="toolbar" style={{ marginBottom: 0 }}>
        <label className="field">
          <span className="field-label">{t("timetable.subs.date")}</span>
          <input
            type="date"
            className="input"
            name="date"
            value={date}
            onChange={(e) => {
              if (PLAIN_DATE.test(e.target.value)) {
                setDate(e.target.value);
                setFresh(null);
              }
            }}
          />
        </label>
        {day ? (
          <p className="text-[13.5px] text-ink-2 sm:self-end" data-testid="subs-summary">
            {`${t(DAY_LABEL[day.day])} · ${t("timetable.subs.covered", { covered: day.periodsCovered, total: day.periodsToCover })}`}
          </p>
        ) : null}
        <Link
          href={`/app/timetable/substitutions/print?date=${encodeURIComponent(date)}`}
          className="btn sm:ml-auto sm:self-end"
        >
          <Printer size={18} aria-hidden="true" />
          {t("timetable.subs.print")}
        </Link>
      </div>

      {loaded.error && !day ? (
        <ErrorState error={loaded.error} onRetry={loaded.reload} />
      ) : !day ? (
        <LoadingRows rows={4} />
      ) : !day.workingDay ? (
        <p className="empty">{t("timetable.subs.closed", { date: formatPlainDate(day.date, locale) })}</p>
      ) : (
        <>
          {canManage ? <AbsenceForm day={day} onDay={setFresh} /> : null}
          {day.absences.length === 0 ? (
            <p className="empty" data-testid="subs-empty">
              {t("timetable.subs.none", { date: formatPlainDate(day.date, locale) })}
            </p>
          ) : (
            day.absences.map((absence) => (
              <section key={absence.id} className="card" aria-label={absence.teacherName} data-testid="absence">
                <div className="card-head">
                  <div className="min-w-0">
                    <h2>{absence.teacherName}</h2>
                    <p className="mt-1 text-[13px] text-ink-3">
                      {[absence.reason, plural(t, "timetable.subs.periods", absence.periods.length)]
                        .filter(Boolean)
                        .join(" · ")}
                    </p>
                  </div>
                  {canManage ? (
                    <button type="button" className="btn btn-sm" onClick={() => setRemoving(absence)}>
                      {t("timetable.subs.notAbsent")}
                    </button>
                  ) : null}
                </div>
                {absence.periods.length === 0 ? (
                  <p className="text-sm text-ink-2">{t("timetable.subs.noPeriods")}</p>
                ) : (
                  <ul className="sub-list">
                    {absence.periods.map((period) => (
                      <CoverRow
                        key={`${period.sectionId}:${period.period}:${period.substitute?.id ?? ""}`}
                        date={day.date}
                        period={period}
                        canManage={canManage}
                        onDay={setFresh}
                      />
                    ))}
                  </ul>
                )}
              </section>
            ))
          )}
        </>
      )}

      <ConfirmDialog
        open={removing !== null}
        title={t("timetable.subs.removeTitle", { name: removing?.teacherName ?? "" })}
        body={t("timetable.subs.removeBody")}
        confirmLabel={t("timetable.subs.notAbsent")}
        onClose={() => setRemoving(null)}
        onConfirm={async () => {
          if (!removing) return;
          const next = await timetableApi.removeAbsence(removing.id);
          toast(t("timetable.subs.absenceRemoved", { name: removing.teacherName }));
          setRemoving(null);
          setFresh(next);
        }}
      />
    </div>
  );
}
